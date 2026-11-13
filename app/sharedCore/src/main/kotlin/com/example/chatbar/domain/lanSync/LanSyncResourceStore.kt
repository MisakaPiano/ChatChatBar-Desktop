package com.example.chatbar.domain.lanSync

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Durable verified chunks survive a disconnected invocation; only a full verified object is exposed. */
class LanSyncResourceStore(
    private val root: Path,
    private val budget: LanSyncResourceBudget = LanSyncResourceBudget(),
    val chunkBytes: Int = 1024 * 1024,
    private val observe: (String, Long) -> Unit = { _, _ -> },
) {
    init {
        require(chunkBytes in 1..1024 * 1024)
        createCheckedRoot(root)
    }

    fun completed(hash: String, length: Long): Path? {
        validateIdentity(hash, length)
        val path = root.resolve(hash)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(hashFile(path, length) == hash) { "SYNC_CACHED_RESOURCE_DAMAGED" }
        return path
    }

    fun open(hash: String, length: Long): Partial {
        validateIdentity(hash, length)
        val key = root.toAbsolutePath().normalize().toString() + "/" + hash
        val entry = requireNotNull(locks.compute(key) { _, previous -> (previous ?: LockEntry()).also { it.users++ } })
        val unregister: () -> Unit = {
            locks.compute(key) { _, current ->
                check(current === entry)
                entry.users--
                if (entry.users == 0) null else entry
            }
            Unit
        }
        if (entry.lock.isHeldByCurrentThread) {
            unregister()
            error("SYNC_RESOURCE_ALREADY_OPEN")
        }
        entry.lock.lock()
        val release: () -> Unit = { entry.lock.unlock(); unregister() }
        try { return Partial(hash, length, release) }
        catch (failure: Throwable) { release(); throw failure }
    }

    fun retain(source: Path, hash: String, length: Long): Path = open(hash, length).use { partial ->
        if (partial.offset < length) {
            lanSyncSafe(source)
            FileChannel.open(source, StandardOpenOption.READ).use { channel ->
                require(channel.size() == length) { "SYNC_RESOURCE_LENGTH" }
                channel.position(partial.offset)
                while (partial.offset < length) {
                    val bytes = ByteArray(minOf(chunkBytes.toLong(), length - partial.offset).toInt())
                    val block = ByteBuffer.wrap(bytes)
                    while (block.hasRemaining()) require(channel.read(block) > 0) { "SYNC_SOURCE_TRUNCATED" }
                    partial.append(partial.offset, bytes, lanSyncHash(bytes))
                }
            }
        }
        partial.finish()
    }

    private fun validateIdentity(hash: String, length: Long) {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "SYNC_RESOURCE_HASH" }
        budget.requireResource(length)
    }

    inner class Partial internal constructor(val hash: String, val length: Long, private val release: () -> Unit) : Closeable {
        private val directory = root.resolve("partial-$hash")
        private val data = directory.resolve("data.part")
        private val receipts = directory.resolve("verified.chunks")
        private val manifest = directory.resolve("identity")
        var offset: Long = 0
            private set
        private var closed = false
        private var finalPath: Path? = null

        init {
            finalPath = completed(hash, length)
            if (finalPath != null) offset = length else {
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
                lanSyncSafe(directory, true)
                val expected = "$hash\n$length\n$chunkBytes\n".toByteArray(Charsets.US_ASCII)
                if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    require(lanSyncRead(manifest, 256).contentEquals(expected)) { "SYNC_RESUME_IDENTITY" }
                } else {
                    require(Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }) { "SYNC_RESUME_UNKNOWN_FILES" }
                    lanSyncAtomicWrite(manifest, expected)
                }
                Files.newDirectoryStream(directory).use { entries ->
                    require(entries.all { it.fileName.toString() in setOf("identity", "data.part", "verified.chunks") }) { "SYNC_RESUME_UNKNOWN_FILES" }
                }
                if (!Files.exists(data, LinkOption.NOFOLLOW_LINKS)) Files.createFile(data)
                if (!Files.exists(receipts, LinkOption.NOFOLLOW_LINKS)) Files.createFile(receipts)
                lanSyncSafe(data); lanSyncSafe(receipts)
                recoverVerifiedPrefix()
                budget.requireSpace(root, length - offset)
            }
        }

        private fun recoverVerifiedPrefix() {
            var receiptBytes = 0L
            Files.newInputStream(receipts, LinkOption.NOFOLLOW_LINKS).buffered().use { input ->
                FileChannel.open(data, StandardOpenOption.READ).use { channel ->
                    while (true) {
                        val line = StringBuilder()
                        var terminated = false
                        while (true) {
                            val byte = input.read()
                            if (byte < 0) break
                            if (byte == 10) { terminated = true; break }
                            require(byte in 32..126 && line.length < 160) { "SYNC_RESUME_RECEIPT" }
                            line.append(byte.toChar())
                        }
                        if (!terminated) break // An interrupted trailing receipt has no durable authority.
                        val fields = line.toString().split(' ')
                        require(fields.size == 3 && fields[0].toLongOrNull() == offset) { "SYNC_RESUME_RECEIPT" }
                        val count = fields[1].toIntOrNull()
                        require(count != null && count == minOf(chunkBytes.toLong(), length - offset).toInt() && count > 0 &&
                            fields[2].matches(Regex("[a-f0-9]{64}"))) { "SYNC_RESUME_RECEIPT" }
                        val bytes = ByteArray(count); val block = ByteBuffer.wrap(bytes)
                        while (block.hasRemaining()) require(channel.read(block) > 0) { "SYNC_RESUME_TRUNCATED" }
                        require(lanSyncHash(bytes) == fields[2]) { "SYNC_RESUME_DAMAGED" }
                        offset = lanSyncAddLength(offset, count.toLong())
                        receiptBytes = lanSyncAddLength(receiptBytes, line.length.toLong() + 1)
                    }
                }
            }
            FileChannel.open(data, StandardOpenOption.WRITE).use { it.truncate(offset); it.force(true) }
            FileChannel.open(receipts, StandardOpenOption.WRITE).use { it.truncate(receiptBytes); it.force(true) }
        }

        fun append(expectedOffset: Long, bytes: ByteArray, chunkHash: String) {
            check(!closed && finalPath == null)
            require(expectedOffset == offset && bytes.size.toLong() == minOf(chunkBytes.toLong(), length - offset) && bytes.isNotEmpty()) { "SYNC_CHUNK_OFFSET" }
            require(chunkHash.matches(Regex("[a-f0-9]{64}")) && lanSyncHash(bytes) == chunkHash) { "SYNC_CHUNK_DAMAGED" }
            budget.requireSpace(root, bytes.size.toLong())
            FileChannel.open(data, StandardOpenOption.WRITE).use { channel ->
                channel.position(offset)
                val block = ByteBuffer.wrap(bytes); while (block.hasRemaining()) channel.write(block)
                channel.force(true)
            }
            observe("CHUNK_DATA_DURABLE", offset)
            val receipt = "$offset ${bytes.size} $chunkHash\n".toByteArray(Charsets.US_ASCII)
            FileChannel.open(receipts, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                val block = ByteBuffer.wrap(receipt); while (block.hasRemaining()) channel.write(block)
                channel.force(true)
            }
            offset = lanSyncAddLength(offset, bytes.size.toLong())
            observe("CHUNK_VERIFIED", offset)
        }

        fun finish(): Path {
            check(!closed && offset == length)
            finalPath?.let { return it }
            require(hashFile(data, length) == hash) { "SYNC_RESOURCE_DAMAGED" }
            val target = root.resolve(hash)
            Files.move(data, target, StandardCopyOption.ATOMIC_MOVE)
            finalPath = target
            Files.delete(receipts); Files.delete(manifest); Files.delete(directory)
            return target
        }

        override fun close() {
            if (!closed) { closed = true; release() }
        }
    }

    companion object {
        private class LockEntry(val lock: ReentrantLock = ReentrantLock(), var users: Int = 0)
        private val locks = ConcurrentHashMap<String, LockEntry>()
        private fun createCheckedRoot(path: Path) {
            require(path.isAbsolute && path.normalize() == path && path.parent != null) { "SYNC_UNSAFE_PATH" }
            val missing = mutableListOf<Path>()
            var existing = path
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                missing.add(existing)
                existing = requireNotNull(existing.parent) { "SYNC_UNSAFE_PATH" }
            }
            lanSyncSafe(existing, true)
            for (directory in missing.asReversed()) {
                lanSyncSafe(requireNotNull(directory.parent), true)
                try { Files.createDirectory(directory) }
                catch (_: java.nio.file.FileAlreadyExistsException) { /* Validate the racing occupant below. */ }
                lanSyncSafe(directory, true)
            }
            lanSyncSafe(path, true)
        }
        fun hashFile(path: Path, length: Long): String {
            lanSyncSafe(path)
            require(length > 0 && Files.size(path) == length) { "SYNC_RESOURCE_LENGTH" }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                var count = 0L; val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    count = lanSyncAddLength(count, n.toLong()); require(count <= length) { "SYNC_RESOURCE_LENGTH" }
                    digest.update(buffer, 0, n)
                }
                require(count == length) { "SYNC_RESOURCE_LENGTH" }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
