package com.example.chatbar.domain.syncStore

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.channels.FileLockInterruptionException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * A small filesystem adapter for an application-owned private root. Every cooperating instance uses
 * a JVM lock and an OS file lock; a caller stream/callback must not re-enter the same store. Files
 * are forced then published with a required atomic move. No overwrite or non-atomic fallback exists.
 *
 * Symlinks, other/reparse entries reported by Java and unsafe ancestors are refused. This is not a
 * sandbox against a local attacker able to rename ancestors or mutate files concurrently: Java's
 * portable path API cannot eliminate those races on every platform. Protect the root with OS ACLs.
 * File force is not a portable guarantee that directory entries survive sudden power loss.
 */
class FileCiphertextStore(root: Path, private val bufferBytes: Int = 64 * 1024) : CiphertextStore {
    private val root = checkedRoot(root)
    private val blobs = this.root.resolve("objects")
    private val commits = this.root.resolve("commits")
    private val operations = this.root.resolve("operations")
    private val staging = this.root.resolve("staging")
    private val lockPath = this.root.resolve("store.lock")
    private val localLock: ReentrantLock
    private val storeId: SyncStoreId

    override val capabilities = CiphertextStoreCapabilities(
        commitListing = CommitListingSemantics.KEYSET_RESCAN_REQUIRED,
        fileDataForcedBeforePublication = true,
    )

    init {
        require(bufferBytes in 1..MAX_BUFFER_BYTES) { "Invalid stream buffer budget" }
        ensureDirectoryTree(this.root)
        listOf(blobs, commits, operations, staging).forEach(::ensureDirectoryTree)
        localLock = locks.computeIfAbsent(this.root.toRealPath(NOFOLLOW_LINKS)) { ReentrantLock() }
        storeId = guarded {
            discardAbandonedStaging()
            val identity = this.root.resolve("store.id")
            if (!existsRegular(identity)) {
                publishSmall(identity, SyncStoreId.random().value.toByteArray(Charsets.US_ASCII))
            }
            if (Files.size(identity) != 32L) fail(CiphertextStoreFailure.INTEGRITY)
            try {
                SyncStoreId.parse(Files.newInputStream(identity, NOFOLLOW_LINKS).use {
                    String(readFixed(it, 32), Charsets.US_ASCII)
                })
            } catch (_: IllegalArgumentException) {
                fail(CiphertextStoreFailure.INTEGRITY)
            }
        }
    }

    override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult = guarded {
        // Account for the file header without narrowing to Int or overflowing Long.
        if (request.descriptor.length > Long.MAX_VALUE - OBJECT_HEADER_BYTES) {
            fail(CiphertextStoreFailure.UNSUPPORTED)
        }
        bindOperation(request)
        withTemporaryFile { temporary ->
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                val output = DataOutputStream(Channels.newOutputStream(channel))
                output.writeInt(OBJECT_MAGIC)
                output.writeByte(FORMAT_VERSION)
                output.writeByte(request.key.kind.storageCode)
                output.writeLong(request.descriptor.length)
                output.write(decodeDigest(request.descriptor.sha256))
                val digest = transferExact(source, output, request.descriptor.length)
                if (digest != request.descriptor.sha256) fail(CiphertextStoreFailure.INTEGRITY)
                output.flush()
                channel.force(true)
            }
            val target = objectPath(request.key)
            val existing = readStored(request.key, expected = null, destination = null)
            if (existing != null) {
                if (existing.descriptor != request.descriptor) fail(CiphertextStoreFailure.OBJECT_CONFLICT)
                CiphertextPutResult(existing, CiphertextPutDisposition.ALREADY_PRESENT)
            } else {
                publish(temporary, target)
                CiphertextPutResult(
                    CiphertextObjectMetadata(request.key, request.descriptor),
                    CiphertextPutDisposition.CREATED,
                )
            }
        }
    }

    override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? = guarded {
        readStored(key, expected = null, destination = null)
    }

    override fun readTo(
        key: CiphertextObjectKey,
        destination: OutputStream,
        expected: CiphertextDescriptor?,
    ): CiphertextObjectMetadata? = guarded { readStored(key, expected, destination) }

    override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = guarded {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Invalid commit page budget" }
        val after = decodeCursor(cursor)
        // Retain at most pageSize + 1 IDs, even if the store has millions of objects.
        val selected = PriorityQueue<String>(pageSize + 1, reverseOrder())
        Files.newDirectoryStream(commits).use { entries ->
            for (entry in entries) {
                interrupted()
                val name = entry.fileName.toString()
                if (!OBJECT_NAME.matches(name)) fail(CiphertextStoreFailure.UNSAFE_PATH)
                if (!existsRegular(entry)) fail(CiphertextStoreFailure.INTEGRITY)
                val id = name.removeSuffix(".obj")
                if (after != null && id <= after) continue
                selected.add(id)
                if (selected.size > pageSize + 1) selected.remove()
            }
        }
        val ordered = selected.sorted()
        val hasMore = ordered.size > pageSize
        val result = ordered.take(pageSize).map { id ->
            readStored(CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.parse(id)), null, null)
                ?: fail(CiphertextStoreFailure.INTEGRITY)
        }
        CommitPage(result, if (hasMore) CommitCursor("v1:${storeId.value}:${ordered[pageSize - 1]}") else null)
    }

    private fun decodeCursor(cursor: CommitCursor?): String? {
        if (cursor == null) return null
        if (cursor.token.length != 68) fail(CiphertextStoreFailure.INVALID_CURSOR)
        val parts = cursor.token.split(':')
        if (parts.size != 3 || parts[0] != "v1" || parts[1] != storeId.value ||
            !ID_PATTERN.matches(parts[2])) fail(CiphertextStoreFailure.INVALID_CURSOR)
        return parts[2]
    }

    /** An immutable intent survives stream failure and binds the operation across process restarts. */
    private fun bindOperation(request: CiphertextPutRequest) {
        val receipt = operations.resolve("${request.operationId.value}.op")
        val bytes = ByteBuffer.allocate(OPERATION_BYTES).apply {
            putInt(OPERATION_MAGIC)
            put(FORMAT_VERSION.toByte())
            put(request.key.kind.storageCode.toByte())
            put(request.key.id.value.toByteArray(Charsets.US_ASCII))
            putLong(request.descriptor.length)
            put(decodeDigest(request.descriptor.sha256))
        }.array()
        if (existsRegular(receipt)) {
            if (Files.size(receipt) != bytes.size.toLong()) fail(CiphertextStoreFailure.INTEGRITY)
            val stored = Files.newInputStream(receipt, NOFOLLOW_LINKS).use { readFixed(it, bytes.size) }
            if (!stored.contentEquals(bytes)) fail(CiphertextStoreFailure.OPERATION_CONFLICT)
        } else {
            publishSmall(receipt, bytes)
        }
    }

    private fun readStored(
        key: CiphertextObjectKey,
        expected: CiphertextDescriptor?,
        destination: OutputStream?,
    ): CiphertextObjectMetadata? {
        val path = objectPath(key)
        if (!existsRegular(path)) return null
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val input = DataInputStream(Channels.newInputStream(channel))
            if (channel.size() < OBJECT_HEADER_BYTES) fail(CiphertextStoreFailure.INTEGRITY)
            if (input.readInt() != OBJECT_MAGIC || input.readUnsignedByte() != FORMAT_VERSION ||
                input.readUnsignedByte() != key.kind.storageCode) fail(CiphertextStoreFailure.INTEGRITY)
            val length = input.readLong()
            if (length < 0 || length > Long.MAX_VALUE - OBJECT_HEADER_BYTES ||
                channel.size() != OBJECT_HEADER_BYTES + length) fail(CiphertextStoreFailure.INTEGRITY)
            val digest = ByteArray(32).also(input::readFully).let(::encodeDigest)
            val descriptor = CiphertextDescriptor(length, digest)
            if (expected != null && expected != descriptor) fail(CiphertextStoreFailure.INTEGRITY)
            if (transferExact(input, discardOutput, length) != digest) {
                fail(CiphertextStoreFailure.INTEGRITY)
            }
            if (destination != null) {
                channel.position(OBJECT_HEADER_BYTES)
                if (transferExact(input, destination, length) != digest) fail(CiphertextStoreFailure.INTEGRITY)
            }
            return CiphertextObjectMetadata(key, descriptor)
        }
    }

    private fun transferExact(source: InputStream, destination: OutputStream, length: Long): CiphertextSha256 {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferBytes)
        var remaining = length
        while (remaining > 0) {
            interrupted()
            val requested = minOf(remaining, buffer.size.toLong()).toInt()
            var count = source.read(buffer, 0, requested)
            if (count == 0) {
                val single = source.read()
                if (single < 0) fail(CiphertextStoreFailure.INTEGRITY)
                buffer[0] = single.toByte()
                count = 1
            }
            if (count < 0 || count > requested) fail(CiphertextStoreFailure.INTEGRITY)
            destination.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            remaining -= count.toLong()
        }
        interrupted()
        if (source.read() != -1) fail(CiphertextStoreFailure.INTEGRITY)
        return encodeDigest(digest.digest())
    }

    private fun objectPath(key: CiphertextObjectKey): Path =
        (if (key.kind == CiphertextObjectKind.COMMIT) commits else blobs).resolve("${key.id.value}.obj")

    private fun publishSmall(target: Path, bytes: ByteArray) = withTemporaryFile { temporary ->
        FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        publish(temporary, target)
    }

    private fun publish(temporary: Path, target: Path) {
        if (existsRegular(target)) fail(CiphertextStoreFailure.OBJECT_CONFLICT)
        try {
            Files.move(temporary, target, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            fail(CiphertextStoreFailure.UNSUPPORTED)
        }
    }

    private fun <T> withTemporaryFile(block: (Path) -> T): T {
        val path = staging.resolve("${SyncStoreId.random().value}.part")
        FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).close()
        var failure: Throwable? = null
        try {
            return block(path)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                Files.deleteIfExists(path)
            } catch (cleanup: Exception) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun discardAbandonedStaging() {
        Files.newDirectoryStream(staging).use { entries ->
            for (entry in entries) {
                if (!PART_NAME.matches(entry.fileName.toString()) || !existsRegular(entry)) {
                    fail(CiphertextStoreFailure.UNSAFE_PATH)
                }
                Files.delete(entry)
            }
        }
    }

    private fun <T> guarded(block: () -> T): T {
        if (localLock.isHeldByCurrentThread) fail(CiphertextStoreFailure.UNSUPPORTED)
        try {
            localLock.lockInterruptibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Ciphertext store interrupted")
        }
        try {
            interrupted()
            listOf(root, blobs, commits, operations, staging).forEach(::validateDirectoryTree)
            if (Files.exists(lockPath, NOFOLLOW_LINKS)) existsRegular(lockPath)
            return FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                channel.lock().use { block() }
            }
        } catch (_: ClosedByInterruptException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Ciphertext store interrupted")
        } catch (_: FileLockInterruptionException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Ciphertext store interrupted")
        } finally {
            localLock.unlock()
        }
    }

    companion object {
        private const val OBJECT_MAGIC = 0x43434243
        private const val OPERATION_MAGIC = 0x4343424f
        private const val FORMAT_VERSION = 1
        private const val OBJECT_HEADER_BYTES = 46L
        private const val OPERATION_BYTES = 78
        private const val MAX_BUFFER_BYTES = 1024 * 1024
        private const val MAX_PAGE_SIZE = 1000
        private val ID_PATTERN = Regex("[0-9a-f]{32}")
        private val OBJECT_NAME = Regex("[0-9a-f]{32}\\.obj")
        private val PART_NAME = Regex("[0-9a-f]{32}\\.part")
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
        private val discardOutput = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        private fun readFixed(input: InputStream, length: Int): ByteArray =
            ByteArray(length).also { DataInputStream(input).readFully(it) }

        private fun fail(failure: CiphertextStoreFailure): Nothing = throw CiphertextStoreException(failure)

        private fun checkedRoot(path: Path): Path {
            if (!path.isAbsolute || path.normalize() != path) fail(CiphertextStoreFailure.UNSAFE_PATH)
            return path
        }

        private fun interrupted() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Ciphertext store interrupted")
        }

        private fun existsRegular(path: Path): Boolean {
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return false
            }
            if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.isOther) {
                fail(CiphertextStoreFailure.UNSAFE_PATH)
            }
            return true
        }

        private fun validateDirectoryTree(path: Path) {
            var current = path.root
            for (part in path) {
                current = current.resolve(part)
                val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther) {
                    fail(CiphertextStoreFailure.UNSAFE_PATH)
                }
            }
        }

        private fun ensureDirectoryTree(path: Path) {
            var current = path.root
            for (part in path) {
                current = current.resolve(part)
                try {
                    Files.createDirectory(current)
                } catch (_: FileAlreadyExistsException) {
                    // Validate the existing occupant without following links.
                }
                val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther) {
                    fail(CiphertextStoreFailure.UNSAFE_PATH)
                }
            }
        }

        private fun decodeDigest(value: CiphertextSha256): ByteArray =
            ByteArray(32) { index -> value.hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

        private fun encodeDigest(value: ByteArray): CiphertextSha256 = CiphertextSha256.parse(
            value.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        )
    }
}
