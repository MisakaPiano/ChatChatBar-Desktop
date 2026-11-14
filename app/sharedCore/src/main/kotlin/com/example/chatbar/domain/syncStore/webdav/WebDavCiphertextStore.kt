package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
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

/**
 * This is an operator/integration assertion of server semantics, not an OPTIONS/Allow inference.
 * VERIFIED_CONDITIONAL_CREATE requires that concurrent If-None-Match:* creates are enforced and
 * representations become visible only when complete. Unknown/weaker servers stay read-only.
 * A noncompliant/malicious server cannot be made atomic by a client-side HEAD-then-PUT check.
 */
enum class WebDavWriteSupport { UNVERIFIED_READ_ONLY, VERIFIED_CONDITIONAL_CREATE }

data class WebDavIoBudget(val bufferBytes: Int = 64 * 1024, val minimumFreeBytes: Long = 32L * 1024 * 1024,
    /** Single XML discovery-response budget, not an attachment or total-data size limit. */
    val maximumListingBytes: Long = 64L * 1024 * 1024) {
    init { require(bufferBytes in 1..1024 * 1024 && minimumFreeBytes >= 0 && maximumListingBytes > 0) { "SYNC_DAV_BUDGET" } }
}

/**
 * Immutable opaque records under one app-owned WebDAV collection. No latest archive, live database
 * directory, session merge, key handling, authority or cloud freshness decision lives here.
 *
 * privateRoot must be an existing private, canonical directory, outside all source/outbox trees.
 * It retains immutable endpoint/operation bindings and fully verified strong-ETag observations;
 * invocation staging holds ciphertext and is
 * cleaned on normal failure. Caller outboxes are never modified or retired. Block on an I/O executor.
 * Retries are explicit caller retries from byte zero, using the exact retained request/ciphertext.
 * The platform must allocate a different route/private root when switching cloud accounts, including
 * when the same URL serves multiple credentials. Strong validators rely on compliant server semantics.
 * Weak/missing ETags require a complete GET for each metadata lookup; listing rescans are O(N).
 */
class WebDavCiphertextStore(
    private val transport: WebDavHttpTransport,
    private val privateRoot: Path,
    private val route: SyncStoreId,
    private val writeSupport: WebDavWriteSupport = WebDavWriteSupport.UNVERIFIED_READ_ONLY,
    private val budget: WebDavIoBudget = WebDavIoBudget(),
) : CiphertextStore {
    private val intents: FileCiphertextStore
    private val observations: FileCiphertextStore
    override val capabilities = CiphertextStoreCapabilities(
        CommitListingSemantics.KEYSET_RESCAN_REQUIRED,
        conditionalCreate = writeSupport == WebDavWriteSupport.VERIFIED_CONDITIONAL_CREATE,
    )

    init {
        PrivateSyncFile.checked(privateRoot, true)
        intents = FileCiphertextStore(privateRoot.resolve("dav-intents"))
        observations = FileCiphertextStore(privateRoot.resolve("dav-observations"))
        val binding = "CCB-DAV-ENDPOINT/1\n${route.value}\n${transport.endpointBinding}".toByteArray(Charsets.US_ASCII)
        val fixed = SyncStoreId.parse("0".repeat(32))
        intents.put(CiphertextPutRequest(CiphertextObjectKey(CiphertextObjectKind.COMMIT, fixed),
            WebDavStoreRecord.operationKey("ENDPOINT", fixed), WebDavStoreRecord.checksum(binding)), ByteArrayInputStream(binding))
    }

    override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult = safeIo {
        if (writeSupport != WebDavWriteSupport.VERIFIED_CONDITIONAL_CREATE) fail(CiphertextStoreFailure.UNSUPPORTED)
        val expectedMetadata = CiphertextObjectMetadata(request.key, request.descriptor)
        val header = WebDavStoreRecord.header(expectedMetadata)
        bindLocally(request, header)
        requireSpace(request.descriptor.length)
        PrivateSyncFile.create(privateRoot).use { preparation ->
            FileChannel.open(preparation.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val output = Channels.newOutputStream(channel)
                output.write(header)
                WebDavStoreRecord.transfer(source, output, request.descriptor, budget.bufferBytes)
                channel.force(true)
            }
            ensureCollection("operations/")
            ensureCollection(collection(request.key.kind))
            bindRemotely(request, header)
            metadata(request.key)?.let {
                if (it != expectedMetadata) fail(CiphertextStoreFailure.OBJECT_CONFLICT)
                return@safeIo CiphertextPutResult(it, CiphertextPutDisposition.ALREADY_PRESENT)
            }
            val body = fileBody(preparation.path, WebDavStoreRecord.requireLength(request.descriptor.length))
            val code = transport.execute("PUT", path(request.key), body, mapOf("If-None-Match" to "*")).use { response ->
                transport.requireConditionalCreate(response.code)
                response.code
            }
            val observed = metadata(request.key) ?: fail(CiphertextStoreFailure.TRANSIENT)
            if (observed != expectedMetadata) fail(CiphertextStoreFailure.OBJECT_CONFLICT)
            CiphertextPutResult(observed, if (code == 201) CiphertextPutDisposition.CREATED else CiphertextPutDisposition.ALREADY_PRESENT)
        }
    }

    override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? = safeIo {
        // Cached bytes alone never establish current remote availability/access. HEAD is fresh and
        // a cache hit requires a strong representation validator plus the exact wire length.
        transport.execute("HEAD", path(key)).use { response ->
            if (response.code == 404) return@safeIo null
            if (response.code !in setOf(405, 501)) {
                transport.requireStatus(response.code, setOf(200))
                if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) fail(CiphertextStoreFailure.UNSUPPORTED)
                val textLength = response.header("Content-Length")
                val length = textLength?.toLongOrNull()
                if (textLength != null && (length == null || length < WebDavStoreRecord.HEADER_BYTES)) fail(CiphertextStoreFailure.INTEGRITY)
                val etag = strongEtag(response.header("ETag"))
                if (length != null && etag != null) {
                    observation(key, etag, length)?.let { return@safeIo it }
                }
            }
        }
        // Weak/absent validators or unsupported HEAD cannot authenticate an observation cache.
        download(key, null, null)
    }

    override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? = safeIo {
        PrivateSyncFile.create(privateRoot).use { preparation ->
            val metadata = FileChannel.open(preparation.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val result = download(key, expected, Channels.newOutputStream(channel))
                channel.force(true); result
            } ?: return@safeIo null
            // No response byte reaches caller output before the complete descriptor has verified.
            PrivateSyncFile.checked(preparation.path)
            Files.newInputStream(preparation.path, NOFOLLOW_LINKS).use { input ->
                WebDavStoreRecord.transfer(input, destination, metadata.descriptor, budget.bufferBytes)
            }
            metadata
        }
    }

    override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = safeIo {
        require(pageSize in 1..1000) { "SYNC_DAV_PAGE_SIZE" }
        val after = decodeCursor(cursor)
        val folder = "commits/"
        val ids = transport.execute("PROPFIND", folder, WebDavXmlListing.requestBytes.toRequestBody(XML),
            mapOf("Depth" to "1")).use { response ->
            if (response.code == 404) return@safeIo CommitPage(emptyList(), null)
            transport.requireStatus(response.code, setOf(207))
            val body = response.body ?: fail(CiphertextStoreFailure.INTEGRITY)
            WebDavXmlListing.parse(transport.input(body), transport.url(folder), after, pageSize + 1, budget.maximumListingBytes)
        }
        val entries = ids.take(pageSize).map { value ->
            metadata(CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.parse(value)))
                ?: fail(CiphertextStoreFailure.TRANSIENT)
        }
        CommitPage(entries, if (ids.size > pageSize) CommitCursor("d1:${route.value}:${transport.endpointBinding}:${ids[pageSize - 1]}") else null)
    }

    private fun download(key: CiphertextObjectKey, expected: CiphertextDescriptor?, sink: OutputStream?): CiphertextObjectMetadata? {
        transport.execute("GET", path(key)).use { response ->
            if (response.code == 404) return null
            transport.requireStatus(response.code, setOf(200))
            if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) fail(CiphertextStoreFailure.UNSUPPORTED)
            val body = response.body ?: fail(CiphertextStoreFailure.INTEGRITY)
            val input = transport.input(body)
            val metadata = WebDavStoreRecord.readHeader(input, key)
            if (expected != null && metadata.descriptor != expected) fail(CiphertextStoreFailure.INTEGRITY)
            if (body.contentLength() >= 0 && body.contentLength() != WebDavStoreRecord.requireLength(metadata.descriptor.length)) {
                fail(CiphertextStoreFailure.INTEGRITY)
            }
            if (sink != null) requireSpace(metadata.descriptor.length)
            WebDavStoreRecord.transfer(input, sink ?: WebDavStoreRecord.discard, metadata.descriptor, budget.bufferBytes)
            // Exact EOF has established the actual wire length even for a chunked HTTP response.
            remember(metadata, strongEtag(response.header("ETag")), WebDavStoreRecord.requireLength(metadata.descriptor.length))
            return metadata
        }
    }

    private fun bindLocally(request: CiphertextPutRequest, header: ByteArray) {
        val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, request.operationId)
        intents.put(CiphertextPutRequest(key, WebDavStoreRecord.operationKey("INTENT", request.operationId),
            WebDavStoreRecord.checksum(header)), ByteArrayInputStream(header))
    }

    private fun bindRemotely(request: CiphertextPutRequest, expected: ByteArray) {
        val path = "operations/${request.operationId.value}.op"
        transport.execute("PUT", path, expected.toRequestBody(BINARY), mapOf("If-None-Match" to "*")).use {
            transport.requireConditionalCreate(it.code)
        }
        transport.execute("GET", path).use { response ->
            if (response.code == 404) fail(CiphertextStoreFailure.TRANSIENT)
            transport.requireStatus(response.code, setOf(200))
            val body = response.body ?: fail(CiphertextStoreFailure.INTEGRITY)
            if (body.contentLength() >= 0 && body.contentLength() != expected.size.toLong()) fail(CiphertextStoreFailure.OPERATION_CONFLICT)
            val actual = ByteArray(expected.size)
            val input = transport.input(body)
            var count = 0
            while (count < actual.size) {
                WebDavStoreRecord.interrupted()
                val n = input.read(actual, count, actual.size - count)
                if (n <= 0) fail(CiphertextStoreFailure.INTEGRITY)
                count += n
            }
            if (input.read() != -1 || !actual.contentEquals(expected)) fail(CiphertextStoreFailure.OPERATION_CONFLICT)
        }
    }

    private fun ensureCollection(folder: String) {
        transport.execute("MKCOL", folder, EMPTY).use { response ->
            // 405 alone is not evidence that an existing occupant is a collection.
            if (response.code != 405) { transport.requireStatus(response.code, setOf(201)); return }
        }
        transport.execute("PROPFIND", folder, WebDavXmlListing.requestBytes.toRequestBody(XML), mapOf("Depth" to "0")).use { response ->
            transport.requireStatus(response.code, setOf(207))
            val body = response.body ?: fail(CiphertextStoreFailure.INTEGRITY)
            WebDavXmlListing.parse(transport.input(body), transport.url(folder), null, 1, budget.maximumListingBytes)
        }
    }

    private fun fileBody(file: Path, length: Long): RequestBody = object : RequestBody() {
        override fun contentType() = BINARY
        override fun contentLength() = length
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            PrivateSyncFile.checked(file)
            if (Files.size(file) != length) fail(CiphertextStoreFailure.INTEGRITY)
            Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(budget.bufferBytes)
                var remaining = length
                while (remaining > 0) {
                    WebDavStoreRecord.interrupted()
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count <= 0) fail(CiphertextStoreFailure.INTEGRITY)
                    sink.write(buffer, 0, count); remaining -= count.toLong()
                }
                if (input.read() != -1) fail(CiphertextStoreFailure.INTEGRITY)
            }
        }
    }

    private fun requireSpace(length: Long) {
        PrivateSyncFile.checked(privateRoot, true)
        val required = try { Math.addExact(Math.addExact(length, WebDavStoreRecord.HEADER_BYTES), budget.minimumFreeBytes) }
            catch (_: ArithmeticException) { fail(CiphertextStoreFailure.UNSUPPORTED) }
        if (privateRoot.toFile().usableSpace < required) fail(CiphertextStoreFailure.QUOTA)
    }

    private fun decodeCursor(cursor: CommitCursor?): String? {
        if (cursor == null) return null
        val prefix = "d1:${route.value}:${transport.endpointBinding}:"
        if (cursor.token.length != prefix.length + 32 || !cursor.token.startsWith(prefix)) fail(CiphertextStoreFailure.INVALID_CURSOR)
        return cursor.token.removePrefix(prefix).also { if (!it.matches(Regex("[a-f0-9]{32}"))) fail(CiphertextStoreFailure.INVALID_CURSOR) }
    }

    private fun collection(kind: CiphertextObjectKind) = if (kind == CiphertextObjectKind.BLOB) "objects/" else "commits/"
    private fun path(key: CiphertextObjectKey) = "${collection(key.kind)}${key.id.value}.bin"

    private fun cacheKey(key: CiphertextObjectKey, etag: String?, wireLength: Long = 0): CiphertextObjectKey {
        val binding = "${route.value}\n${transport.endpointBinding}\n${key.kind}\n${key.id.value}"
        return CiphertextObjectKey(if (etag == null) CiphertextObjectKind.BLOB else CiphertextObjectKind.COMMIT,
            WebDavStoreRecord.opaqueKey("OBSERVATION", if (etag == null) binding else "$binding\n$wireLength\n$etag"))
    }

    private fun observation(key: CiphertextObjectKey, etag: String, length: Long): CiphertextObjectMetadata? {
        val header = cachedHeader(cacheKey(key, etag, length)) ?: return null
        val baseline = cachedHeader(cacheKey(key, null)) ?: fail(CiphertextStoreFailure.INTEGRITY)
        if (!header.contentEquals(baseline)) fail(CiphertextStoreFailure.INTEGRITY)
        val metadata = WebDavStoreRecord.readHeader(ByteArrayInputStream(header), key)
        if (WebDavStoreRecord.requireLength(metadata.descriptor.length) != length) fail(CiphertextStoreFailure.INTEGRITY)
        return metadata
    }

    private fun cachedHeader(key: CiphertextObjectKey): ByteArray? {
        val metadata = observations.metadata(key) ?: return null
        if (metadata.descriptor.length != WebDavStoreRecord.HEADER_BYTES) fail(CiphertextStoreFailure.INTEGRITY)
        val output = ByteArrayOutputStream(WebDavStoreRecord.HEADER_BYTES.toInt())
        observations.readTo(key, output, metadata.descriptor)
        return output.toByteArray()
    }

    private fun remember(metadata: CiphertextObjectMetadata, etag: String?, wireLength: Long) {
        val header = WebDavStoreRecord.header(metadata)
        val baselineKey = cacheKey(metadata.key, null)
        cachedHeader(baselineKey)?.let { if (!it.contentEquals(header)) fail(CiphertextStoreFailure.OBJECT_CONFLICT) }
        fun retain(key: CiphertextObjectKey) {
            try {
                observations.put(CiphertextPutRequest(key, WebDavStoreRecord.operationKey("OBSERVED", key.id),
                    WebDavStoreRecord.checksum(header)), ByteArrayInputStream(header))
            } catch (failure: CiphertextStoreException) {
                if (failure.failure in setOf(CiphertextStoreFailure.OBJECT_CONFLICT, CiphertextStoreFailure.OPERATION_CONFLICT)) {
                    fail(CiphertextStoreFailure.OBJECT_CONFLICT)
                }
                throw failure
            }
        }
        retain(baselineKey)
        if (etag != null && wireLength == WebDavStoreRecord.requireLength(metadata.descriptor.length)) {
            retain(cacheKey(metadata.key, etag, wireLength))
        }
    }

    private fun strongEtag(value: String?): String? {
        if (value == null || value.length !in 2..8192 || value.first() != '"' || value.last() != '"') return null
        if (value.substring(1, value.lastIndex).any { it == '"' || it.code !in 0x21..0xff || it.code == 0x7f }) return null
        return value
    }

    private fun <T> safeIo(work: () -> T): T = try {
        WebDavStoreRecord.interrupted(); PrivateSyncFile.checked(privateRoot, true); work()
    } catch (failure: CiphertextStoreException) { throw failure }
    catch (failure: InterruptedIOException) { throw failure }
    catch (_: ClosedByInterruptException) { Thread.currentThread().interrupt(); throw InterruptedIOException("SYNC_DAV_INTERRUPTED") }
    catch (_: AccessDeniedException) { fail(CiphertextStoreFailure.PERMISSION) }
    catch (_: IOException) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_DAV_INTERRUPTED")
        fail(CiphertextStoreFailure.IO)
    }

    private companion object {
        val BINARY = "application/octet-stream".toMediaType()
        val XML = "application/xml; charset=utf-8".toMediaType()
        val EMPTY = byteArrayOf().toRequestBody(null)
        fun fail(failure: CiphertextStoreFailure): Nothing = WebDavHttpTransport.fail(failure)
    }
}
