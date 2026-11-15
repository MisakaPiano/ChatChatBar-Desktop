package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
import java.io.*
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Local verified bytes were damaged. This is never a rejected provider candidate or a cache hit. */
class WeakWebDavIncomingCacheException internal constructor() : IllegalStateException("SYNC_WEAK_DAV_INCOMING_CACHE_INTEGRITY")

/** Local reconstruction only: no provider-availability observation or target-delivery receipt. */
data class WeakWebDavIncomingAssemblyResult(val metadata: CiphertextObjectMetadata,
    val manifest: WeakWebDavCandidate, val cachedChunks: Long, val fetchedChunks: Long,
    val cachedManifest: Boolean)

/**
 * Durable incoming ciphertext, separate from writer plans and target observations. The caller's
 * opaque scope binds group/genesis/endpoint/account; actual transport endpoint binding is checked
 * independently. Local route IDs never identify portable provider locators. Each manifest slot is
 * immutable and retains its exact physical locator and bytes; competing claims never replace it.
 */
class WeakWebDavIncomingChunkCache(root: Path, private val scopeHash: String,
    private val budget: WebDavIoBudget = WebDavIoBudget()) {
    private val root = root.toAbsolutePath().normalize()
    internal class Manifest(val candidate: WeakWebDavCandidate, val path: Path, val directory: Path)
    private val lock: ReentrantLock
    init {
        require(scopeHash.matches(Regex("[a-f0-9]{64}"))) { "SYNC_WEAK_DAV_INCOMING_SCOPE" }
        if (!Files.exists(this.root, NOFOLLOW_LINKS)) { PrivateSyncFile.checked(this.root.parent, true); Files.createDirectory(this.root) }
        PrivateSyncFile.checked(this.root, true)
        lock = locks.computeIfAbsent(this.root.toRealPath(NOFOLLOW_LINKS)) { ReentrantLock() }
        locked { bind(this.root.resolve("scope.bin"), "CCB-WEAK-INCOMING-SCOPE/1\n$scopeHash".toByteArray(Charsets.US_ASCII)) }
    }

    internal fun <T> withEndpoint(endpoint: String, work: () -> T): T = locked {
        require(endpoint.matches(Regex("[a-f0-9]{64}"))) { "SYNC_WEAK_DAV_INCOMING_ENDPOINT" }
        bind(root.resolve("endpoint.bin"), endpoint.toByteArray(Charsets.US_ASCII)); work()
    }
    internal fun manifests(expected: CiphertextObjectMetadata, route: SyncStoreId,
        active: () -> Unit): List<Manifest> {
        val home = logical(expected)
        if (!Files.exists(home, NOFOLLOW_LINKS)) return emptyList()
        PrivateSyncFile.checked(home, true)
        return Files.newDirectoryStream(home).use { paths -> paths.toList().sortedBy { it.fileName.toString() }.mapNotNull { directory ->
            active()
            if (!directory.fileName.toString().matches(Regex("[a-f0-9]{64}"))) corrupt()
            PrivateSyncFile.checked(directory, true)
            val reference = directory.resolve("manifest.ref")
            if (!Files.exists(reference, NOFOLLOW_LINKS)) return@mapNotNull null
            val candidate = readReference(reference, expected, route)
            if (directory.fileName.toString() != locator(candidate)) corrupt()
            val path = directory.resolve("manifest.bin")
            verify(path, candidate.descriptor, active)
            Manifest(candidate, path, directory)
        } }
    }
    internal fun retainManifest(expected: CiphertextObjectMetadata, candidate: WeakWebDavCandidate,
        source: Path, active: () -> Unit): Manifest {
        val directory = directory(directory(logical(expected)).resolve(locator(candidate)))
        val path = directory.resolve("manifest.bin")
        retain(source, path, candidate.descriptor, active)
        val reference = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { output ->
            output.write(REFERENCE_MAGIC); output.write(WebDavStoreRecord.header(expected))
            output.write(WebDavStoreRecord.header(CiphertextObjectMetadata(candidate.key, candidate.descriptor)))
            output.write(candidate.operationId.value.toByteArray(Charsets.US_ASCII))
            output.write(candidate.attempt.value.toByteArray(Charsets.US_ASCII))
        } }.toByteArray()
        bind(directory.resolve("manifest.ref"), reference + MessageDigest.getInstance("SHA-256").digest(reference))
        return Manifest(candidate, path, directory)
    }
    internal fun part(manifest: Manifest, index: Long, candidate: WeakWebDavCandidate,
        active: () -> Unit): Path? {
        val path = partPath(manifest, index, candidate)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        verify(path, candidate.descriptor, active); return path
    }
    internal fun retainPart(manifest: Manifest, index: Long, candidate: WeakWebDavCandidate,
        source: Path, active: () -> Unit): Path = partPath(manifest, index, candidate).also {
        retain(source, it, candidate.descriptor, active)
    }
    internal fun copy(path: Path, expected: CiphertextDescriptor, destination: OutputStream, active: () -> Unit) {
        PrivateSyncFile.checked(path)
        try { Files.newInputStream(path, NOFOLLOW_LINKS).use { WebDavStoreRecord.transfer(checked(it, active), destination, expected, budget.bufferBytes) } }
        catch (failure: CiphertextStoreException) { corrupt() }
    }
    internal fun space(length: Long) {
        val needed = try { Math.addExact(length, budget.minimumFreeBytes) } catch (_: ArithmeticException) { fail(CiphertextStoreFailure.QUOTA) }
        if (root.toFile().usableSpace < needed) fail(CiphertextStoreFailure.QUOTA)
    }
    private fun logical(expected: CiphertextObjectMetadata) = root.resolve(hash("LOGICAL\n$scopeHash\n${identity(expected)}"))
    private fun locator(candidate: WeakWebDavCandidate) = hash("MANIFEST\n${identity(CiphertextObjectMetadata(candidate.key, candidate.descriptor))}\n${candidate.operationId.value}\n${candidate.attempt.value}")
    private fun partPath(manifest: Manifest, index: Long, candidate: WeakWebDavCandidate): Path {
        require(index >= 0)
        // The enclosing manifest hash includes the original route claim and all exact part frames.
        return manifest.directory.resolve(hash("PART\n$index\n${identity(CiphertextObjectMetadata(candidate.key, candidate.descriptor))}\n${candidate.operationId.value}\n${candidate.attempt.value}") + ".part")
    }
    private fun identity(metadata: CiphertextObjectMetadata) = "${metadata.key.kind.name}\n${metadata.key.id.value}\n${metadata.descriptor.length}\n${metadata.descriptor.sha256.hex}"
    private fun readReference(path: Path, expected: CiphertextObjectMetadata, route: SyncStoreId): WeakWebDavCandidate {
        PrivateSyncFile.checked(path)
        val length = REFERENCE_MAGIC.size + 2 * WebDavStoreRecord.HEADER_BYTES.toInt() + 64 + 32
        if (Files.size(path) != length.toLong()) corrupt()
        val bytes = Files.readAllBytes(path)
        val body = bytes.copyOfRange(0, bytes.size - 32)
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(bytes.size - 32, bytes.size))) corrupt()
        return try { DataInputStream(body.inputStream()).use { input ->
            if (!ByteArray(REFERENCE_MAGIC.size).also(input::readFully).contentEquals(REFERENCE_MAGIC)) corrupt()
            if (WebDavStoreRecord.readHeader(input, expected.key) != expected) corrupt()
            val manifest = WebDavStoreRecord.readHeader(input, expected.key)
            fun id() = SyncStoreId.parse(String(ByteArray(32).also(input::readFully), Charsets.US_ASCII))
            val result = WeakWebDavCandidate(route, manifest.key, manifest.descriptor, id(), id())
            if (input.read() != -1) corrupt()
            result
        } } catch (_: IOException) { corrupt() } catch (_: IllegalArgumentException) { corrupt() }
    }
    private fun retain(source: Path, target: Path, expected: CiphertextDescriptor, active: () -> Unit) {
        if (Files.exists(target, NOFOLLOW_LINKS)) { verify(target, expected, active); return }
        space(expected.length)
        PrivateSyncFile.create(root).use { temporary ->
            FileChannel.open(temporary.path, WRITE, NOFOLLOW_LINKS).use { output ->
                Files.newInputStream(source, NOFOLLOW_LINKS).use { input ->
                    WebDavStoreRecord.transfer(checked(input, active), Channels.newOutputStream(output), expected, budget.bufferBytes)
                }
                output.force(true)
            }
            active(); Files.move(temporary.path, target, ATOMIC_MOVE)
        }
    }
    private fun verify(path: Path, expected: CiphertextDescriptor, active: () -> Unit) {
        try {
            PrivateSyncFile.checked(path)
            if (Files.size(path) != expected.length) corrupt()
            Files.newInputStream(path, NOFOLLOW_LINKS).use { WebDavStoreRecord.transfer(checked(it, active), WebDavStoreRecord.discard, expected, budget.bufferBytes) }
        } catch (failure: InterruptedIOException) { throw failure }
        catch (_: IOException) { corrupt() }
    }
    private fun checked(input: InputStream, active: () -> Unit) = object : FilterInputStream(input) {
        override fun read(): Int { active(); val value = `in`.read(); active(); return value }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int { active(); val count = `in`.read(bytes, offset, length); active(); return count }
    }
    private fun bind(path: Path, bytes: ByteArray) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            PrivateSyncFile.checked(path)
            if (Files.size(path) != bytes.size.toLong() || !Files.readAllBytes(path).contentEquals(bytes)) fail(CiphertextStoreFailure.OPERATION_CONFLICT)
        } else PrivateSyncFile.create(root).use { temporary ->
            FileChannel.open(temporary.path, WRITE, NOFOLLOW_LINKS).use { output ->
                val buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            Files.move(temporary.path, path, ATOMIC_MOVE)
        }
    }
    private fun directory(path: Path): Path {
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        PrivateSyncFile.checked(path, true); return path
    }
    private fun <T> locked(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_WEAK_DAV_INCOMING_REENTRY" }
        try { lock.lockInterruptibly() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("SYNC_WEAK_DAV_INCOMING_INTERRUPTED") }
        try {
            PrivateSyncFile.checked(root, true)
            FileChannel.open(root.resolve("incoming.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { file ->
                file.lock().use { return work() }
            }
        } finally { lock.unlock() }
    }
    override fun toString() = "WeakWebDavIncomingChunkCache(REDACTED)"
    private companion object {
        val locks = ConcurrentHashMap<Path, ReentrantLock>()
        val REFERENCE_MAGIC = "CCB-WEAK-INCOMING-MANIFEST/1\n".toByteArray(Charsets.US_ASCII)
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun corrupt(): Nothing = throw WeakWebDavIncomingCacheException()
        fun fail(reason: CiphertextStoreFailure): Nothing = WebDavHttpTransport.fail(reason)
    }
}
