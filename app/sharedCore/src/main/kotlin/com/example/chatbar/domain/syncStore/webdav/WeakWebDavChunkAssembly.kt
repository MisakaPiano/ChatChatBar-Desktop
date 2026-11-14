package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
import java.io.*
import java.nio.channels.Channels
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileLockInterruptionException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

data class WeakWebDavChunkPreparation(val request: CiphertextPutRequest, val chunkBytes: Long, val chunkCount: Long)

/** Provider assembly observation, deliberately distinct from a logical ciphertext/target receipt. */
class WeakWebDavAssemblyObservation internal constructor(val request: CiphertextPutRequest,
    val assembly: WeakWebDavCandidate, val chunkCount: Long, val observedAtMillis: Long)

/** Bounded untrusted header claim; it neither checks remote chunks nor authenticates the logical object. */
class WeakWebDavAssemblyHeader internal constructor(val originalRequest: CiphertextPutRequest,
    val chunkCount: Long, val chunkBytes: Long)

/**
 * Splits already frozen ciphertext without changing its logical key, full descriptor or AEAD bytes.
 * Provider chunks/assemblies use separate attempt namespaces. Plans and chunk bytes remain local;
 * every resume verifies complete remote chunks and publishes only missing/corrupt attempts anew.
 * Assembly records and Long counters stream with a bounded buffer; chunkBytes is a physical transfer
 * granularity, never a maximum object size. No admission, strict store or target receipt is supplied.
 */
class WeakWebDavChunkAssembly(transport: WebDavHttpTransport, private val privateRoot: Path,
    route: SyncStoreId, private val chunkBytes: Long = 8L * 1024 * 1024,
    private val budget: WebDavIoBudget = WebDavIoBudget()) {
    private val chunks: WeakWebDavAttemptStore
    private val assemblies: WeakWebDavAttemptStore
    val routeId: SyncStoreId get() = chunks.routeId
    private val requests: FileCiphertextStore
    private val lock: ReentrantLock
    init {
        PrivateSyncFile.checked(privateRoot, true)
        require(chunkBytes > 0 && chunkBytes <= Long.MAX_VALUE - WebDavStoreRecord.HEADER_BYTES) { "SYNC_WEAK_DAV_CHUNK_SIZE" }
        chunks = WeakWebDavAttemptStore(transport, privateRoot, route, budget, WeakWebDavNamespace.CHUNKS)
        assemblies = WeakWebDavAttemptStore(transport, privateRoot, route, budget, WeakWebDavNamespace.ASSEMBLIES)
        requests = FileCiphertextStore(privateRoot.resolve("chunk-request-bindings"))
        lock = locks.computeIfAbsent(privateRoot) { ReentrantLock() }
    }

    fun prepare(request: CiphertextPutRequest, source: InputStream,
        checkActive: () -> Unit = {}): WeakWebDavChunkPreparation = guarded {
        prepareLocked(request, source, checkActive)
    }
    fun append(request: CiphertextPutRequest, source: InputStream,
        checkActive: () -> Unit = {}): WeakWebDavAssemblyObservation = guarded {
        prepareLocked(request, source, checkActive)
        resumeLocked(request.operationId, checkActive)
    }
    /** Reopen needs only the retained plan and chunks, never the original source stream. */
    fun resume(operationId: SyncStoreId, checkActive: () -> Unit = {}): WeakWebDavAssemblyObservation = guarded {
        resumeLocked(operationId, checkActive)
    }
    fun listAssemblies(key: CiphertextObjectKey, cursor: WeakWebDavCursor? = null, pageSize: Int = 100,
        checkActive: () -> Unit = {}): WeakWebDavCandidatePage = assemblies.listCandidates(key, cursor, pageSize, checkActive)
    fun listCommitAssemblies(cursor: WeakWebDavCursor? = null, pageSize: Int = 100,
        checkActive: () -> Unit = {}): WeakWebDavCandidatePage = assemblies.listCommitCandidates(cursor, pageSize, checkActive)

    /** Verifies only the provider manifest's bytes and bounded header, without fetching any chunk. */
    fun inspectAssembly(candidate: WeakWebDavCandidate, checkActive: () -> Unit = {}): WeakWebDavAssemblyHeader? = guarded {
        checkActive()
        PrivateSyncFile.create(privateRoot).use { manifest ->
            val observed = FileChannel.open(manifest.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val result = assemblies.readCandidate(candidate, Channels.newOutputStream(channel), checkActive)
                channel.force(true); result
            } ?: return@guarded null
            if (observed.candidate != candidate) fail(CiphertextStoreFailure.INTEGRITY)
            val plan = Files.newInputStream(manifest.path, NOFOLLOW_LINKS).use { decodePlan(DataInputStream(it)) }
            val length = try { Math.addExact(PLAN_BYTES.toLong(), Math.multiplyExact(plan.chunkCount, ENTRY_BYTES)) }
                catch (_: ArithmeticException) { fail(CiphertextStoreFailure.INTEGRITY) }
            if (plan.request.key != candidate.key || length != candidate.descriptor.length) fail(CiphertextStoreFailure.INTEGRITY)
            checkActive()
            WeakWebDavAssemblyHeader(plan.request, plan.chunkCount, plan.chunkBytes)
        }
    }

    /** The complete original descriptor must come from an authenticated manifest outside this layer. */
    fun readAssembly(candidate: WeakWebDavCandidate, expected: CiphertextObjectMetadata, destination: OutputStream,
        checkActive: () -> Unit = {}): WeakWebDavAssemblyObservation? = guarded {
        assembled(candidate, expected, checkActive)?.use {
            transfer(it.file.path, destination, expected.descriptor, checkActive)
            checkActive()
            WeakWebDavAssemblyObservation(it.plan.request, candidate, it.plan.chunkCount, System.currentTimeMillis())
        }
    }
    fun readExact(key: CiphertextObjectKey, expected: CiphertextDescriptor, destination: OutputStream,
        checkActive: () -> Unit = {}): WeakWebDavAssemblyObservation? = guarded {
        var cursor: WeakWebDavCursor? = null
        var rejected: CiphertextStoreException? = null
        do {
            val page = listAssemblies(key, cursor, checkActive = checkActive)
            for (candidate in page.entries) {
                val prepared = try { assembled(candidate, CiphertextObjectMetadata(key, expected), checkActive) }
                catch (failure: CiphertextStoreException) {
                    if (failure.failure !in setOf(CiphertextStoreFailure.INTEGRITY, CiphertextStoreFailure.TRANSIENT)) throw failure
                    rejected = failure; null
                } ?: continue
                prepared.use {
                    // Caller sink failure cannot retry into already emitted bytes.
                    transfer(it.file.path, destination, expected, checkActive)
                    checkActive()
                    return@guarded WeakWebDavAssemblyObservation(it.plan.request, candidate, it.plan.chunkCount, System.currentTimeMillis())
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
        rejected?.let { throw it }
        null
    }

    private fun prepareLocked(request: CiphertextPutRequest, source: InputStream,
        checkActive: () -> Unit): WeakWebDavChunkPreparation {
        checkActive(); requests.reserveOperation(request)
        val directory = directory(request.operationId)
        val binding = directory.resolve("binding.bin")
        val plan = if (Files.exists(binding, NOFOLLOW_LINKS)) readPlan(binding) else
            WeakWebDavChunkPreparation(request, chunkBytes, count(request.descriptor.length, chunkBytes)).also {
                small(binding, encodePlan(it))
            }
        if (plan.request != request) fail(CiphertextStoreFailure.OPERATION_CONFLICT)
        val ready = directory.resolve("ready.bin")
        if (Files.exists(ready, NOFOLLOW_LINKS)) {
            if (!readSmall(ready, PLAN_BYTES).contentEquals(encodePlan(plan))) fail(CiphertextStoreFailure.INTEGRITY)
            return plan
        }
        val parts = subdirectory(directory.resolve("parts"))
        val whole = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(budget.bufferBytes)
        var remaining = request.descriptor.length
        var index = 0L
        while (remaining > 0) {
            checkActive(); val length = minOf(remaining, plan.chunkBytes); space(length)
            PrivateSyncFile.create(parts).use { temporary ->
                val hash = MessageDigest.getInstance("SHA-256")
                FileChannel.open(temporary.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val output = Channels.newOutputStream(channel)
                    var left = length
                    while (left > 0) {
                        checkActive(); WebDavStoreRecord.interrupted()
                        val wanted = minOf(left, buffer.size.toLong()).toInt()
                        var n = source.read(buffer, 0, wanted)
                        if (n == 0) { val byte = source.read(); if (byte < 0) fail(CiphertextStoreFailure.INTEGRITY); buffer[0] = byte.toByte(); n = 1 }
                        checkActive()
                        if (n < 0 || n > wanted) fail(CiphertextStoreFailure.INTEGRITY)
                        output.write(buffer, 0, n); hash.update(buffer, 0, n); whole.update(buffer, 0, n); left -= n
                    }
                    channel.force(true); checkActive()
                }
                val descriptor = CiphertextDescriptor(length, digest(hash))
                val metadata = parts.resolve("$index.meta")
                val chunk = if (Files.exists(metadata, NOFOLLOW_LINKS)) readChunk(metadata) else
                    CiphertextPutRequest(CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()), SyncStoreId.random(), descriptor)
                if (chunk.descriptor != descriptor) fail(CiphertextStoreFailure.INTEGRITY)
                publish(temporary, parts.resolve("$index.part"), descriptor, checkActive)
                small(metadata, encodeChunk(chunk)); index++; remaining -= length
            }
        }
        checkActive(); if (source.read() != -1 || digest(whole) != request.descriptor.sha256) fail(CiphertextStoreFailure.INTEGRITY)
        checkActive(); small(ready, encodePlan(plan))
        return plan
    }

    private fun resumeLocked(operationId: SyncStoreId, checkActive: () -> Unit): WeakWebDavAssemblyObservation {
        checkActive()
        val directory = directory(operationId)
        val plan = readPlan(directory.resolve("binding.bin"))
        if (plan.request.operationId != operationId) fail(CiphertextStoreFailure.OPERATION_CONFLICT)
        requests.reserveOperation(plan.request)
        if (!readSmall(directory.resolve("ready.bin"), PLAN_BYTES).contentEquals(encodePlan(plan))) fail(CiphertextStoreFailure.INTEGRITY)
        val parts = subdirectory(directory.resolve("parts"))
        PrivateSyncFile.create(directory).use { manifest ->
            val whole = DigestSink(plan.request.descriptor.length, checkActive)
            FileChannel.open(manifest.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val output = DataOutputStream(Channels.newOutputStream(channel))
                output.write(encodePlan(plan))
                var index = 0L
                while (index < plan.chunkCount) {
                    checkActive()
                    val part = parts.resolve("$index.part"); val request = readChunk(parts.resolve("$index.meta"))
                    val expectedLength = minOf(plan.chunkBytes, plan.request.descriptor.length - index * plan.chunkBytes)
                    if (request.descriptor.length != expectedLength) fail(CiphertextStoreFailure.INTEGRITY)
                    verify(part, request.descriptor, checkActive)
                    Files.newInputStream(part, NOFOLLOW_LINKS).use { source ->
                        chunks.appendAttemptReadback(request, source, checkActive).use { readback ->
                            readback.copyTo(whole)
                            writeEntry(output, index, readback.observation.candidate)
                        }
                    }
                    checkActive(); index++
                }
                if (whole.length != plan.request.descriptor.length || whole.finish() != plan.request.descriptor.sha256) fail(CiphertextStoreFailure.INTEGRITY)
                channel.force(true); checkActive()
            }
            val descriptor = identity(manifest.path, checkActive)
            val retained = subdirectory(directory.resolve("assemblies")).resolve("${descriptor.sha256.hex}-${descriptor.length}.bin")
            publish(manifest, retained, descriptor, checkActive)
            val operation = WebDavStoreRecord.opaqueKey("WEAK-ASSEMBLY", "${operationId.value}\n${descriptor.length}\n${descriptor.sha256.hex}")
            val request = CiphertextPutRequest(plan.request.key, operation, descriptor)
            val observed = Files.newInputStream(retained, NOFOLLOW_LINKS).use { assemblies.appendAttempt(request, it, checkActive) }
            checkActive()
            return WeakWebDavAssemblyObservation(plan.request, observed.candidate, plan.chunkCount, System.currentTimeMillis())
        }
    }

    private class Assembled(val file: PrivateSyncFile, val plan: WeakWebDavChunkPreparation) : Closeable {
        override fun close() = file.close()
    }
    private fun assembled(candidate: WeakWebDavCandidate, expected: CiphertextObjectMetadata,
        checkActive: () -> Unit): Assembled? {
        checkActive(); if (candidate.key != expected.key) fail(CiphertextStoreFailure.INTEGRITY)
        val ciphertext = PrivateSyncFile.create(privateRoot)
        try {
            PrivateSyncFile.create(privateRoot).use { manifest ->
                val observed = FileChannel.open(manifest.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val result = assemblies.readCandidate(candidate, Channels.newOutputStream(channel), checkActive)
                    channel.force(true); result
                } ?: run { ciphertext.close(); return null }
                checkActive(); if (observed.candidate != candidate) fail(CiphertextStoreFailure.INTEGRITY)
                val plan = Files.newInputStream(manifest.path, NOFOLLOW_LINKS).use { input ->
                    val data = DataInputStream(input); val header = decodePlan(data)
                    if (header.request.key != expected.key || header.request.descriptor != expected.descriptor) fail(CiphertextStoreFailure.INTEGRITY)
                    space(expected.descriptor.length)
                    FileChannel.open(ciphertext.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                        val whole = DigestSink(expected.descriptor.length, checkActive, Channels.newOutputStream(channel))
                        var index = 0L; var remaining = expected.descriptor.length
                        while (index < header.chunkCount) {
                            checkActive(); val part = readEntry(data, index)
                            val length = minOf(remaining, header.chunkBytes)
                            if (part.key.kind != CiphertextObjectKind.BLOB || part.descriptor.length != length || part.route != chunks.routeId)
                                fail(CiphertextStoreFailure.INTEGRITY)
                            if (chunks.readCandidate(part, whole, checkActive) == null) fail(CiphertextStoreFailure.TRANSIENT)
                            remaining -= length; index++
                        }
                        if (data.read() != -1 || remaining != 0L || whole.length != expected.descriptor.length ||
                            whole.finish() != expected.descriptor.sha256) fail(CiphertextStoreFailure.INTEGRITY)
                        channel.force(true); checkActive()
                    }
                    header
                }
                return Assembled(ciphertext, plan)
            }
        } catch (failure: Throwable) { ciphertext.close(); throw failure }
    }

    private fun encodePlan(plan: WeakWebDavChunkPreparation): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
        it.write(MAGIC); writeRequest(it, plan.request); it.writeLong(plan.chunkBytes); it.writeLong(plan.chunkCount)
    } }.toByteArray()
    private fun decodePlan(input: DataInputStream): WeakWebDavChunkPreparation {
        if (!fixed(input, MAGIC.size).contentEquals(MAGIC)) fail(CiphertextStoreFailure.INTEGRITY)
        val request = readRequest(input); val size = long(input); val number = long(input)
        if (size <= 0 || size > Long.MAX_VALUE - WebDavStoreRecord.HEADER_BYTES || number != count(request.descriptor.length, size))
            fail(CiphertextStoreFailure.INTEGRITY)
        return WeakWebDavChunkPreparation(request, size, number)
    }
    private fun readPlan(path: Path) = DataInputStream(readSmall(path, PLAN_BYTES).inputStream()).use { decodePlan(it) }
    private fun encodeChunk(request: CiphertextPutRequest) = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { writeRequest(it, request) } }.toByteArray()
    private fun readChunk(path: Path) = DataInputStream(readSmall(path, 110).inputStream()).use { readRequest(it) }.also {
        if (it.key.kind != CiphertextObjectKind.BLOB) fail(CiphertextStoreFailure.INTEGRITY)
    }
    private fun writeRequest(out: DataOutputStream, request: CiphertextPutRequest) {
        out.write(WebDavStoreRecord.header(CiphertextObjectMetadata(request.key, request.descriptor)))
        out.write(request.operationId.value.toByteArray(Charsets.US_ASCII))
    }
    private fun readRequest(input: DataInputStream): CiphertextPutRequest {
        val header = fixed(input, 78)
        val kind = when (header[5].toInt()) { 1 -> CiphertextObjectKind.BLOB; 2 -> CiphertextObjectKind.COMMIT; else -> fail(CiphertextStoreFailure.INTEGRITY) }
        val key = CiphertextObjectKey(kind, id(String(header, 6, 32, Charsets.US_ASCII)))
        val metadata = WebDavStoreRecord.readHeader(header.inputStream(), key)
        return CiphertextPutRequest(key, id(text(input, 32)), metadata.descriptor)
    }
    private fun writeEntry(out: DataOutputStream, index: Long, candidate: WeakWebDavCandidate) {
        out.writeLong(index); out.write(candidate.route.value.toByteArray(Charsets.US_ASCII))
        writeRequest(out, CiphertextPutRequest(candidate.key, candidate.operationId, candidate.descriptor))
        out.write(candidate.attempt.value.toByteArray(Charsets.US_ASCII))
    }
    private fun readEntry(input: DataInputStream, index: Long): WeakWebDavCandidate {
        if (long(input) != index) fail(CiphertextStoreFailure.INTEGRITY)
        val route = id(text(input, 32)); val request = readRequest(input)
        return WeakWebDavCandidate(route, request.key, request.descriptor, request.operationId, id(text(input, 32)))
    }
    private fun small(path: Path, bytes: ByteArray) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            if (!readSmall(path, bytes.size).contentEquals(bytes)) fail(CiphertextStoreFailure.INTEGRITY)
        } else PrivateSyncFile.create(path.parent).use { file ->
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use {
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            Files.move(file.path, path, ATOMIC_MOVE)
        }
    }
    private fun readSmall(path: Path, size: Int): ByteArray {
        PrivateSyncFile.checked(path)
        if (Files.size(path) != size.toLong()) fail(CiphertextStoreFailure.INTEGRITY)
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { fixed(DataInputStream(it), size) }
    }
    private fun publish(file: PrivateSyncFile, path: Path, descriptor: CiphertextDescriptor, checkActive: () -> Unit) {
        if (Files.exists(path, NOFOLLOW_LINKS)) verify(path, descriptor, checkActive)
        else { checkActive(); Files.move(file.path, path, ATOMIC_MOVE) }
    }
    private fun verify(path: Path, expected: CiphertextDescriptor, checkActive: () -> Unit) {
        if (identity(path, checkActive) != expected) fail(CiphertextStoreFailure.INTEGRITY)
    }
    private fun identity(path: Path, checkActive: () -> Unit): CiphertextDescriptor {
        PrivateSyncFile.checked(path)
        val sink = DigestSink(Files.size(path), checkActive)
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(budget.bufferBytes)
            while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; if (n > 0) sink.write(buffer, 0, n) }
        }
        return CiphertextDescriptor(sink.length, sink.finish())
    }
    private fun transfer(path: Path, destination: OutputStream, expected: CiphertextDescriptor, checkActive: () -> Unit) {
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val guarded = object : FilterInputStream(input) {
                override fun read(): Int { checkActive(); val n = `in`.read(); checkActive(); return n }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { checkActive(); val n = `in`.read(bytes, offset, length); checkActive(); return n }
            }
            val output = object : OutputStream() {
                override fun write(value: Int) { checkActive(); destination.write(value); checkActive() }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { checkActive(); destination.write(bytes, offset, length); checkActive() }
            }
            WebDavStoreRecord.transfer(guarded, output, expected, budget.bufferBytes)
        }
    }
    private class DigestSink(private val maximum: Long, private val checkActive: () -> Unit,
        private val destination: OutputStream? = null) : OutputStream() {
        private val hash = MessageDigest.getInstance("SHA-256")
        var length = 0L; private set
        override fun write(value: Int) { val one = byteArrayOf(value.toByte()); write(one, 0, 1) }
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            checkActive(); if (count < 0 || count.toLong() > maximum - length) fail(CiphertextStoreFailure.INTEGRITY)
            destination?.write(bytes, offset, count); hash.update(bytes, offset, count); length += count; checkActive()
        }
        fun finish() = digest(hash)
    }
    private fun directory(operation: SyncStoreId) = subdirectory(privateRoot.resolve("chunk-operations")).resolve(operation.value).let(::subdirectory)
    private fun subdirectory(path: Path): Path {
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        PrivateSyncFile.checked(path, true); return path
    }
    private fun space(length: Long) {
        val required = try { Math.addExact(length, budget.minimumFreeBytes) } catch (_: ArithmeticException) { fail(CiphertextStoreFailure.QUOTA) }
        if (privateRoot.toFile().usableSpace < required) fail(CiphertextStoreFailure.QUOTA)
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_WEAK_DAV_ASSEMBLY_REENTRY" }
        try { lock.lockInterruptibly() }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("SYNC_WEAK_DAV_ASSEMBLY_INTERRUPTED") }
        try {
            PrivateSyncFile.checked(privateRoot, true)
            val path = privateRoot.resolve("chunk-assembly.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                PrivateSyncFile.checked(path)
                channel.lock().use { return work() }
            }
        } catch (failure: CiphertextStoreException) { throw failure }
        catch (failure: InterruptedIOException) { throw failure }
        catch (_: ClosedByInterruptException) { Thread.currentThread().interrupt(); throw InterruptedIOException("SYNC_WEAK_DAV_ASSEMBLY_INTERRUPTED") }
        catch (_: FileLockInterruptionException) { Thread.currentThread().interrupt(); throw InterruptedIOException("SYNC_WEAK_DAV_ASSEMBLY_INTERRUPTED") }
        catch (_: AccessDeniedException) { fail(CiphertextStoreFailure.PERMISSION) }
        catch (_: IOException) { fail(CiphertextStoreFailure.IO) }
        finally { lock.unlock() }
    }
    private companion object {
        val locks = ConcurrentHashMap<Path, ReentrantLock>()
        val MAGIC = "CCB-WEAK-DAV-ASSEMBLY/1\n".toByteArray(Charsets.US_ASCII)
        val PLAN_BYTES = MAGIC.size + 126
        const val ENTRY_BYTES = 182L
        fun count(length: Long, size: Long) = if (length == 0L) 0L else (length - 1) / size + 1
        fun digest(hash: MessageDigest) = CiphertextSha256.parse(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        fun fail(failure: CiphertextStoreFailure): Nothing = WebDavHttpTransport.fail(failure)
        fun fixed(input: DataInputStream, size: Int): ByteArray = try { ByteArray(size).also(input::readFully) }
            catch (_: EOFException) { fail(CiphertextStoreFailure.INTEGRITY) }
        fun text(input: DataInputStream, size: Int) = String(fixed(input, size), Charsets.US_ASCII)
        fun id(value: String) = try { SyncStoreId.parse(value) } catch (_: IllegalArgumentException) { fail(CiphertextStoreFailure.INTEGRITY) }
        fun long(input: DataInputStream) = try { input.readLong() } catch (_: EOFException) { fail(CiphertextStoreFailure.INTEGRITY) }
    }
}
