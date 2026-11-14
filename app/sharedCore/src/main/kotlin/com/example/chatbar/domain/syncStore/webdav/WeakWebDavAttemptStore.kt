package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
import java.io.*
import java.nio.channels.Channels
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** Provider-only physical namespaces; default full-ciphertext paths and bindings remain unchanged. */
enum class WeakWebDavNamespace(val collection: String) {
    CIPHERTEXT("weak-attempts-v1"), CHUNKS("weak-chunks-v1"), ASSEMBLIES("weak-assemblies-v1")
}

/** Untrusted physical locator. Neither its name nor its complete ciphertext authenticates content. */
data class WeakWebDavCandidate(val route: SyncStoreId, val key: CiphertextObjectKey,
    val descriptor: CiphertextDescriptor, val operationId: SyncStoreId, val attempt: SyncStoreId)

data class WeakWebDavCursor(val token: String)
data class WeakWebDavCandidatePage(val entries: List<WeakWebDavCandidate>, val nextCursor: WeakWebDavCursor?) {
    /** Observed claims only; callers merge pages and authenticate before deciding protocol conflicts. */
    val claimedDescriptorConflicts: Set<CiphertextObjectKey> get() = entries.groupBy { it.key }
        .filterValues { candidates -> candidates.map { it.descriptor }.distinct().size > 1 }.keys
    val claimedOperationConflicts: Set<SyncStoreId> get() = entries.groupBy { it.operationId }
        .filterValues { candidates -> candidates.map { it.key to it.descriptor }.distinct().size > 1 }.keys
}

enum class WeakWebDavObservationOrigin { NEW_ATTEMPT_READBACK, EXISTING_ATTEMPT_READBACK }
/** A past full GET observation, never a strict immutable write, authenticated fact or delivery receipt. */
class WeakWebDavObservation internal constructor(val candidate: WeakWebDavCandidate,
    val origin: WeakWebDavObservationOrigin, val observedAtMillis: Long)

/**
 * Writable candidate storage for explicitly weak DAV routes. It deliberately is not CiphertextStore.
 * Every attempt has a new physical nonce and at most one PUT; retry first checks old local locators.
 * The private, canonical root must be dedicated to this endpoint/account/route and outside outboxes.
 * Operation bindings and attempt records are forced locally before PUT. Frozen source bytes belong to
 * the caller; this class never re-encrypts, retires queues, imports a canonical mirror or authenticates.
 * Listing is bounded keyset discovery requiring rescans, not global uniqueness or freshness proof.
 */
class WeakWebDavAttemptStore(private val transport: WebDavHttpTransport, private val privateRoot: Path,
    private val route: SyncStoreId, private val budget: WebDavIoBudget = WebDavIoBudget(),
    private val namespace: WeakWebDavNamespace = WeakWebDavNamespace.CIPHERTEXT) {
    val routeId: SyncStoreId get() = route
    private val bindings: FileCiphertextStore
    private val attempts: FileCiphertextStore
    private val observations: FileCiphertextStore
    init {
        PrivateSyncFile.checked(privateRoot, true)
        val localPrefix = if (namespace == WeakWebDavNamespace.CIPHERTEXT) "weak" else namespace.collection
        bindings = FileCiphertextStore(privateRoot.resolve("$localPrefix-bindings"))
        attempts = FileCiphertextStore(privateRoot.resolve("$localPrefix-attempts"))
        observations = FileCiphertextStore(privateRoot.resolve("$localPrefix-observations"))
        val fixed = SyncStoreId.parse("0".repeat(32))
        val mode = if (namespace == WeakWebDavNamespace.CIPHERTEXT) "" else "\n${namespace.collection}"
        val bytes = "CCB-WEAK-DAV-ENDPOINT/1\n${route.value}\n${transport.endpointBinding}$mode".toByteArray(Charsets.US_ASCII)
        bindings.put(CiphertextPutRequest(CiphertextObjectKey(CiphertextObjectKind.COMMIT, fixed),
            WebDavStoreRecord.operationKey("WEAK-ENDPOINT", fixed), WebDavStoreRecord.checksum(bytes)), bytes.inputStream())
    }

    fun appendAttempt(request: CiphertextPutRequest, source: InputStream,
        checkActive: () -> Unit = {}): WeakWebDavObservation = safeIo {
        checkActive()
        WebDavStoreRecord.requireLength(request.descriptor.length)
        bindings.reserveOperation(request)
        for (candidate in retainedAttempts(request)) {
            try {
                observe(candidate, WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK, checkActive)?.let { return@safeIo it }
            } catch (failure: CiphertextStoreException) {
                if (failure.failure !in setOf(CiphertextStoreFailure.INTEGRITY, CiphertextStoreFailure.TRANSIENT)) throw failure
            }
        }
        requireSpace(request.descriptor.length)
        PrivateSyncFile.create(privateRoot).use { wire ->
            FileChannel.open(wire.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val output = Channels.newOutputStream(channel)
                val guardedOutput = checkedOutput(output, checkActive)
                guardedOutput.write(WebDavStoreRecord.header(CiphertextObjectMetadata(request.key, request.descriptor)))
                WebDavStoreRecord.transfer(checkedInput(source, checkActive), guardedOutput, request.descriptor, budget.bufferBytes)
                channel.force(true)
                checkActive()
            }
            var candidate: WeakWebDavCandidate
            while (true) {
                candidate = WeakWebDavCandidate(route, request.key, request.descriptor, request.operationId, SyncStoreId.random())
                val bytes = encodeAttempt(candidate)
                checkActive()
                val result = attempts.put(journalRequest(candidate.attempt, "WEAK-ATTEMPT", bytes), bytes.inputStream())
                // A collision/replayed journal entry is never permission to PUT an old locator again.
                if (result.disposition == CiphertextPutDisposition.CREATED) break
            }
            ensureCollections(candidate, checkActive)
            http("PUT", physicalPath(candidate), fileBody(wire.path,
                WebDavStoreRecord.requireLength(request.descriptor.length), checkActive), mapOf("If-None-Match" to "*"), checkActive).use {
                // This is defensive only. No conditional-create or complete-publication guarantee is inferred.
                if (it.code == 412) fail(CiphertextStoreFailure.OBJECT_CONFLICT)
                transport.requireStatus(it.code, setOf(200, 201, 204))
            }
            observe(candidate, WeakWebDavObservationOrigin.NEW_ATTEMPT_READBACK, checkActive)
                ?: fail(CiphertextStoreFailure.TRANSIENT)
        }
    }

    fun listCandidates(key: CiphertextObjectKey, cursor: WeakWebDavCursor? = null,
        pageSize: Int = 100, checkActive: () -> Unit = {}): WeakWebDavCandidatePage = safeIo {
        checkActive(); page(key, cursor, pageSize, checkActive)
    }
    fun listCommitCandidates(cursor: WeakWebDavCursor? = null, pageSize: Int = 100,
        checkActive: () -> Unit = {}): WeakWebDavCandidatePage = safeIo {
        checkActive(); page(null, cursor, pageSize, checkActive)
    }

    /** Full fresh GET; no HEAD, ETag or historical observation can skip verification. */
    fun readCandidate(candidate: WeakWebDavCandidate, destination: OutputStream,
        checkActive: () -> Unit = {}): WeakWebDavObservation? = safeIo {
        checkActive()
        requireCandidate(candidate)
        prepared(candidate, checkActive)?.use { file ->
            copyVerified(file.path, candidate.descriptor, destination, checkActive)
            remember(candidate, WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK, checkActive)
        }
    }

    /** Expected must come from the existing authenticated manifest. A bad first copy owns no key. */
    fun readExact(key: CiphertextObjectKey, expected: CiphertextDescriptor,
        destination: OutputStream, checkActive: () -> Unit = {}): WeakWebDavObservation? = safeIo {
        checkActive()
        var cursor: WeakWebDavCursor? = null
        var rejected: CiphertextStoreException? = null
        do {
            val page = listCandidates(key, cursor, checkActive = checkActive)
            checkActive()
            for (candidate in page.entries) {
                if (candidate.descriptor != expected) continue
                val file = try { prepared(candidate, checkActive) }
                catch (failure: CiphertextStoreException) {
                    if (failure.failure !in setOf(CiphertextStoreFailure.INTEGRITY, CiphertextStoreFailure.TRANSIENT)) throw failure
                    rejected = failure; null
                } ?: continue
                // Sink failures escape; they must never trigger a second candidate into partial output.
                file.use { copyVerified(it.path, expected, destination, checkActive) }
                return@safeIo remember(candidate, WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK, checkActive)
            }
            cursor = page.nextCursor
        } while (cursor != null)
        rejected?.let { throw it }
        null
    }

    private fun observe(candidate: WeakWebDavCandidate, origin: WeakWebDavObservationOrigin,
        checkActive: () -> Unit): WeakWebDavObservation? {
        prepared(candidate, checkActive)?.use { return remember(candidate, origin, checkActive) }
        return null
    }
    private fun remember(candidate: WeakWebDavCandidate, origin: WeakWebDavObservationOrigin,
        checkActive: () -> Unit): WeakWebDavObservation {
        checkActive()
        val bytes = encodeAttempt(candidate)
        val id = WebDavStoreRecord.opaqueKey("WEAK-OBSERVATION", physicalPath(candidate))
        observations.put(journalRequest(id, "WEAK-OBSERVED", bytes), bytes.inputStream())
        checkActive()
        return WeakWebDavObservation(candidate, origin, System.currentTimeMillis())
    }
    private fun prepared(candidate: WeakWebDavCandidate, checkActive: () -> Unit): PrivateSyncFile? {
        checkActive()
        requireCandidate(candidate)
        val file = PrivateSyncFile.create(privateRoot)
        try {
            http("GET", physicalPath(candidate), null, emptyMap(), checkActive).use { response ->
                if (response.code == 404) { file.close(); return null }
                transport.requireStatus(response.code, setOf(200))
                if (response.header("Content-Encoding")?.equals("identity", true) == false) fail(CiphertextStoreFailure.UNSUPPORTED)
                val body = response.body ?: fail(CiphertextStoreFailure.INTEGRITY)
                val input = checkedInput(transport.input(body), checkActive)
                val actual = WebDavStoreRecord.readHeader(input, candidate.key)
                if (actual.descriptor != candidate.descriptor) fail(CiphertextStoreFailure.INTEGRITY)
                if (body.contentLength() >= 0 && body.contentLength() != WebDavStoreRecord.requireLength(candidate.descriptor.length))
                    fail(CiphertextStoreFailure.INTEGRITY)
                requireSpace(candidate.descriptor.length)
                FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                    WebDavStoreRecord.transfer(input, checkedOutput(Channels.newOutputStream(channel), checkActive), candidate.descriptor, budget.bufferBytes)
                    channel.force(true)
                    checkActive()
                }
            }
            return file
        } catch (failure: Throwable) { file.close(); throw failure }
    }
    private fun copyVerified(file: Path, descriptor: CiphertextDescriptor, destination: OutputStream, checkActive: () -> Unit) {
        PrivateSyncFile.checked(file)
        Files.newInputStream(file, NOFOLLOW_LINKS).use {
            WebDavStoreRecord.transfer(checkedInput(it, checkActive), checkedOutput(destination, checkActive), descriptor, budget.bufferBytes)
        }
    }
    private fun retainedAttempts(request: CiphertextPutRequest): Sequence<WeakWebDavCandidate> = sequence {
        var cursor: CommitCursor? = null
        do {
            val page = attempts.listCommits(cursor)
            for (entry in page.entries) {
                if (entry.descriptor.length != ATTEMPT_BYTES.toLong()) fail(CiphertextStoreFailure.INTEGRITY)
                val output = ByteArrayOutputStream(ATTEMPT_BYTES)
                attempts.readTo(entry.key, output, entry.descriptor)
                val bytes = output.toByteArray()
                val operation = SyncStoreId.parse(String(bytes, WebDavStoreRecord.HEADER_BYTES.toInt(), 32, Charsets.US_ASCII))
                if (operation != request.operationId) continue
                val input = ByteArrayInputStream(bytes)
                val actual = WebDavStoreRecord.readHeader(input, request.key)
                if (actual.descriptor != request.descriptor) fail(CiphertextStoreFailure.OPERATION_CONFLICT)
                val nonce = SyncStoreId.parse(String(bytes, ATTEMPT_BYTES - 32, 32, Charsets.US_ASCII))
                if (nonce != entry.key.id) fail(CiphertextStoreFailure.INTEGRITY)
                yield(WeakWebDavCandidate(route, request.key, request.descriptor, operation, nonce))
            }
            cursor = page.nextCursor
        } while (cursor != null)
    }
    private fun page(key: CiphertextObjectKey?, cursor: WeakWebDavCursor?, pageSize: Int, checkActive: () -> Unit): WeakWebDavCandidatePage {
        require(pageSize in 1..1000) { "SYNC_DAV_PAGE_SIZE" }
        val mode = if (namespace == WeakWebDavNamespace.CIPHERTEXT) "wa1" else "wa1-${namespace.collection}"
        val prefix = "$mode:${route.value}:${transport.endpointBinding}:${key?.kind ?: "ALL_COMMITS"}:${key?.id?.value ?: "-"}:"
        val after = cursor?.token?.let {
            if (!it.startsWith(prefix) || it.length !in prefix.length + 169..prefix.length + 187) fail(CiphertextStoreFailure.INVALID_CURSOR)
            it.removePrefix(prefix).also { value ->
                val decoded = decodeRelative(value, key?.kind ?: CiphertextObjectKind.COMMIT)
                if (decoded == null || key != null && decoded.key != key) fail(CiphertextStoreFailure.INVALID_CURSOR)
            }
        }
        val selected = ArrayList<WeakWebDavCandidate>(pageSize + 1)
        val kind = key?.kind ?: CiphertextObjectKind.COMMIT
        val ids = key?.let { sequenceOf(it.id.value) } ?: names(kindPath(kind), WeakWebDavListingShape.KEYS, checkActive)
        outer@ for (id in ids) {
            checkActive()
            for (hash in names("${kindPath(kind)}$id/", WeakWebDavListingShape.HASHES, checkActive)) {
                for (leaf in names("${kindPath(kind)}$id/$hash/", WeakWebDavListingShape.ATTEMPTS, checkActive)) {
                    val relative = "$id/$hash/$leaf"
                    if (after != null && relative <= after) continue
                    selected += decodeRelative(relative, kind) ?: fail(CiphertextStoreFailure.INTEGRITY)
                    if (selected.size > pageSize) break@outer
                }
            }
        }
        val entries = selected.take(pageSize)
        return WeakWebDavCandidatePage(entries, if (selected.size > pageSize)
            WeakWebDavCursor(prefix + relativePath(entries.last())) else null)
    }
    private fun names(folder: String, shape: WeakWebDavListingShape, checkActive: () -> Unit): Sequence<String> = sequence {
        var after: String? = null
        while (true) {
            val values = http("PROPFIND", folder, WeakWebDavXmlListing.requestBytes.toRequestBody(XML), mapOf("Depth" to "1"), checkActive).use {
                if (it.code == 404) emptyList() else {
                    transport.requireStatus(it.code, setOf(207))
                    val body = it.body ?: fail(CiphertextStoreFailure.INTEGRITY)
                    WeakWebDavXmlListing.parse(checkedInput(transport.input(body), checkActive), transport.url(folder), shape, after, 101, budget.maximumListingBytes)
                }
            }
            for (name in values.take(100)) yield(name)
            if (values.size <= 100) break
            after = values[99]
        }
    }
    private fun ensureCollections(candidate: WeakWebDavCandidate, checkActive: () -> Unit) {
        for (folder in listOf("${namespace.collection}/", kindPath(candidate.key.kind),
            "${kindPath(candidate.key.kind)}${candidate.key.id.value}/",
            "${kindPath(candidate.key.kind)}${candidate.key.id.value}/${candidate.descriptor.sha256.hex}/")) {
            val created = http("MKCOL", folder, EMPTY, emptyMap(), checkActive).use {
                if (it.code == 405) false else { transport.requireStatus(it.code, setOf(201)); true }
            }
            if (created) continue
            http("PROPFIND", folder, WeakWebDavXmlListing.requestBytes.toRequestBody(XML), mapOf("Depth" to "0"), checkActive).use {
                transport.requireStatus(it.code, setOf(207))
                val body = it.body ?: fail(CiphertextStoreFailure.INTEGRITY)
                WeakWebDavXmlListing.parse(checkedInput(transport.input(body), checkActive), transport.url(folder), WeakWebDavListingShape.KEYS, null, 1, budget.maximumListingBytes)
            }
        }
    }
    private fun decodeRelative(value: String, kind: CiphertextObjectKind): WeakWebDavCandidate? = try {
        val parts = value.split('/')
        if (parts.size != 3) null else {
            val leaf = Regex("(0|[1-9][0-9]{0,18})-([a-f0-9]{32})-([a-f0-9]{32})\\.bin").matchEntire(parts[2])
            val length = leaf?.groupValues?.get(1)?.toLongOrNull()
            if (leaf == null || length == null || length > Long.MAX_VALUE - WebDavStoreRecord.HEADER_BYTES) null
            else WeakWebDavCandidate(route, CiphertextObjectKey(kind, SyncStoreId.parse(parts[0])),
                CiphertextDescriptor(length, CiphertextSha256.parse(parts[1])),
                SyncStoreId.parse(leaf.groupValues[2]), SyncStoreId.parse(leaf.groupValues[3]))
        }
    } catch (_: IllegalArgumentException) { null }
    private fun requireCandidate(candidate: WeakWebDavCandidate) {
        if (candidate.route != route) fail(CiphertextStoreFailure.UNSAFE_PATH)
        WebDavStoreRecord.requireLength(candidate.descriptor.length)
    }
    private fun kindPath(kind: CiphertextObjectKind) = "${namespace.collection}/${if (kind == CiphertextObjectKind.BLOB) "objects" else "commits"}/"
    private fun relativePath(candidate: WeakWebDavCandidate) = "${candidate.key.id.value}/${candidate.descriptor.sha256.hex}/${candidate.descriptor.length}-${candidate.operationId.value}-${candidate.attempt.value}.bin"
    private fun physicalPath(candidate: WeakWebDavCandidate) = kindPath(candidate.key.kind) + relativePath(candidate)
    private fun encodeAttempt(candidate: WeakWebDavCandidate) = WebDavStoreRecord.header(CiphertextObjectMetadata(candidate.key, candidate.descriptor)) +
        (candidate.operationId.value + candidate.attempt.value).toByteArray(Charsets.US_ASCII)
    private fun journalRequest(id: SyncStoreId, domain: String, bytes: ByteArray) = CiphertextPutRequest(
        CiphertextObjectKey(CiphertextObjectKind.COMMIT, id), WebDavStoreRecord.operationKey(domain, id), WebDavStoreRecord.checksum(bytes))
    private fun fileBody(path: Path, length: Long, checkActive: () -> Unit) = object : RequestBody() {
        override fun contentType() = BINARY
        override fun contentLength() = length
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            checkActive()
            PrivateSyncFile.checked(path)
            if (Files.size(path) != length) fail(CiphertextStoreFailure.INTEGRITY)
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(budget.bufferBytes)
                var remaining = length
                while (remaining > 0) {
                    WebDavStoreRecord.interrupted()
                    checkActive()
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    checkActive()
                    if (count <= 0) fail(CiphertextStoreFailure.INTEGRITY)
                    sink.write(buffer, 0, count); checkActive(); remaining -= count
                }
                if (input.read() != -1) fail(CiphertextStoreFailure.INTEGRITY)
                checkActive()
            }
        }
    }
    private fun http(method: String, path: String, body: RequestBody?, headers: Map<String, String>,
        checkActive: () -> Unit): okhttp3.Response {
        checkActive()
        val response = transport.execute(method, path, body, headers)
        try { checkActive() } catch (failure: Throwable) { response.close(); throw failure }
        return response
    }
    private fun checkedInput(source: InputStream, checkActive: () -> Unit) = object : FilterInputStream(source) {
        override fun read(): Int { checkActive(); val value = `in`.read(); checkActive(); return value }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkActive(); val count = `in`.read(bytes, offset, length); checkActive(); return count
        }
    }
    private fun checkedOutput(destination: OutputStream, checkActive: () -> Unit) = object : FilterOutputStream(destination) {
        override fun write(value: Int) { checkActive(); out.write(value); checkActive() }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkActive(); out.write(bytes, offset, length); checkActive()
        }
    }
    private fun requireSpace(length: Long) {
        val required = try { Math.addExact(WebDavStoreRecord.requireLength(length), budget.minimumFreeBytes) }
        catch (_: ArithmeticException) { fail(CiphertextStoreFailure.UNSUPPORTED) }
        if (privateRoot.toFile().usableSpace < required) fail(CiphertextStoreFailure.QUOTA)
    }
    private fun <T> safeIo(work: () -> T): T = try {
        WebDavStoreRecord.interrupted(); PrivateSyncFile.checked(privateRoot, true); work()
    } catch (failure: CiphertextStoreException) { throw failure }
    catch (failure: InterruptedIOException) { throw failure }
    catch (_: ClosedByInterruptException) { Thread.currentThread().interrupt(); throw InterruptedIOException("SYNC_DAV_INTERRUPTED") }
    catch (_: AccessDeniedException) { fail(CiphertextStoreFailure.PERMISSION) }
    catch (_: IOException) { fail(CiphertextStoreFailure.IO) }
    private companion object {
        const val ATTEMPT_BYTES = 142
        val BINARY = "application/octet-stream".toMediaType()
        val XML = "application/xml; charset=utf-8".toMediaType()
        val EMPTY = byteArrayOf().toRequestBody(null)
        fun fail(failure: CiphertextStoreFailure): Nothing = WebDavHttpTransport.fail(failure)
    }
}
