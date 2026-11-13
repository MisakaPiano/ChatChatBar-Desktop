package com.example.chatbar.domain.syncStore

import java.io.*
import java.nio.ByteBuffer
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

data class SyncOutboxEntry(val context: SyncEncryptionContext, val request: CiphertextPutRequest, val uploaded: Boolean,
    val plaintext: SyncPlaintextIdentity)

/**
 * Private local queue, not a remote store or portable backup. A forced immutable source binding
 * precedes encryption; exact ciphertext and its request precede any backend call. Lost responses
 * retry the same bytes/operation. Uploaded receipts describe a past transfer, not remote freshness.
 * Commit dependency/author/ancestry admission belongs to the version ledger, outside this owner.
 */
class SyncEncryptedOutbox(private val root: Path, private val cipher: SyncStreamingCipher,
    private val observe: (String) -> Unit = {}) {
    private val lock: ReentrantLock
    init {
        PrivateSyncFile.checked(root, true)
        lock = locks.computeIfAbsent(root) { ReentrantLock() }
    }
    private data class Binding(val operation: SyncStoreId, val context: SyncEncryptionContext, val plain: SyncPlaintextIdentity)
    private data class Ready(val binding: Binding, val descriptor: CiphertextDescriptor) {
        fun entry(uploaded: Boolean) = SyncOutboxEntry(binding.context,
            CiphertextPutRequest(binding.context.key, binding.operation, descriptor), uploaded, binding.plain)
    }

    fun prepare(operation: SyncStoreId, context: SyncEncryptionContext, source: Path,
        expectedPlaintext: SyncPlaintextIdentity? = null): SyncOutboxEntry = guarded {
        require(context.group == cipher.group && context.epoch == cipher.epoch) { "SYNC_OUTBOX_CONTEXT" }
        PrivateSyncFile.checked(source)
        require(!source.startsWith(root)) { "SYNC_OUTBOX_SOURCE" }
        val plain = identity(source)
        require(expectedPlaintext == null || expectedPlaintext == plain) { "SYNC_OUTBOX_SOURCE_CHANGED" }
        val requested = Binding(operation, context, plain)
        val directory = directory(operation, create = true)
        // Only this operation's invocation-owned staging names are reclaimable under both locks.
        Files.newDirectoryStream(directory).use { files -> files.forEach { file ->
            if (file.fileName.toString().matches(Regex("\\.sync-aead-[0-9]+\\.preparing"))) {
                PrivateSyncFile.checked(file); Files.delete(file)
            }
        } }
        val bindingFile = directory.resolve("binding.bin")
        if (Files.exists(bindingFile, NOFOLLOW_LINKS)) {
            require(decodeBinding(readSmall(bindingFile)).also(::validateBinding) == requested) { "SYNC_OUTBOX_OPERATION_CHANGED" }
        } else {
            require(Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }) { "SYNC_OUTBOX_UNKNOWN_PREPARATION" }
            publishSmall(bindingFile, encodeBinding(requested))
        }
        observe("BINDING_READY")
        readReady(directory, requested)?.let { return@guarded it.entry(uploaded(directory, it)) }
        val ciphertext = directory.resolve("ciphertext.bin")
        if (!Files.exists(ciphertext, NOFOLLOW_LINKS)) {
            Files.newInputStream(source, NOFOLLOW_LINKS).use { input -> cipher.encrypt(input, context, directory).use { prepared ->
                require(prepared.plaintextIdentity == plain) { "SYNC_OUTBOX_SOURCE_CHANGED" }
                Files.move(prepared.path, ciphertext, ATOMIC_MOVE)
            } }
        } else {
            PrivateSyncFile.checked(ciphertext)
            Files.newInputStream(ciphertext, NOFOLLOW_LINKS).use {
                require(cipher.verify(it, context) == plain) { "SYNC_OUTBOX_SOURCE_CHANGED" }
            }
        }
        observe("CIPHERTEXT_READY")
        val identity = identity(ciphertext)
        val ready = Ready(requested, CiphertextDescriptor(identity.length, CiphertextSha256.parse(identity.sha256)))
        publishSmall(directory.resolve("ready.bin"), encodeReady(ready))
        observe("REQUEST_READY")
        ready.entry(false)
    }

    /** Resume needs only the retained ciphertext, never the original source file. */
    fun entry(operation: SyncStoreId): SyncOutboxEntry? = guarded {
        val directory = directory(operation, false)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return@guarded null
        val binding = readBinding(directory, operation)
        readReady(directory, binding)?.let { it.entry(uploaded(directory, it)) }
    }

    fun upload(operation: SyncStoreId, backend: CiphertextStore): CiphertextPutResult = guarded {
        val directory = directory(operation, false)
        val binding = readBinding(directory, operation)
        val ready = requireNotNull(readReady(directory, binding)) { "SYNC_OUTBOX_NOT_READY" }
        uploaded(directory, ready) // Refuse damaged local receipts before invoking an external owner.
        val request = ready.entry(false).request
        observe("UPLOAD_STARTED")
        val result = Files.newInputStream(directory.resolve("ciphertext.bin"), NOFOLLOW_LINKS).use { backend.put(request, it) }
        require(result.metadata == CiphertextObjectMetadata(request.key, request.descriptor)) { "SYNC_OUTBOX_RECEIPT" }
        observe("BACKEND_RETURNED")
        val receipt = directory.resolve("uploaded.bin")
        if (!Files.exists(receipt, NOFOLLOW_LINKS)) publishSmall(receipt, encodeReady(ready))
        observe("UPLOAD_RECORDED")
        result
    }

    private fun readBinding(directory: Path, operation: SyncStoreId) = decodeBinding(readSmall(directory.resolve("binding.bin"))).also {
        validateBinding(it); require(it.operation == operation) { "SYNC_OUTBOX_BINDING" }
    }
    private fun validateBinding(binding: Binding) {
        require(binding.context.group == cipher.group && binding.context.epoch == cipher.epoch &&
            binding.plain.length >= 0 && binding.plain.sha256.matches(Regex("[a-f0-9]{64}"))) { "SYNC_OUTBOX_BINDING" }
    }
    private fun readReady(directory: Path, binding: Binding): Ready? {
        val file = directory.resolve("ready.bin")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        val ready = decodeReady(readSmall(file))
        require(ready.binding == binding) { "SYNC_OUTBOX_BINDING" }
        val identity = identity(directory.resolve("ciphertext.bin"))
        require(ready.descriptor == CiphertextDescriptor(identity.length, CiphertextSha256.parse(identity.sha256))) { "SYNC_OUTBOX_CIPHERTEXT_CHANGED" }
        Files.newInputStream(directory.resolve("ciphertext.bin"), NOFOLLOW_LINKS).use {
            require(cipher.verify(it, binding.context) == binding.plain) { "SYNC_OUTBOX_CIPHERTEXT_CHANGED" }
        }
        return ready
    }
    private fun uploaded(directory: Path, ready: Ready): Boolean {
        val file = directory.resolve("uploaded.bin")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return false
        require(readSmall(file).contentEquals(encodeReady(ready))) { "SYNC_OUTBOX_RECEIPT" }
        return true
    }
    private fun directory(operation: SyncStoreId, create: Boolean): Path {
        val directory = root.resolve(operation.value)
        if (create && !Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
        if (Files.exists(directory, NOFOLLOW_LINKS)) PrivateSyncFile.checked(directory, true)
        return directory
    }
    private fun publishSmall(path: Path, bytes: ByteArray) {
        require(!Files.exists(path, NOFOLLOW_LINKS)) { "SYNC_OUTBOX_IMMUTABLE" }
        PrivateSyncFile.create(path.parent).use { file ->
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val data = ByteBuffer.wrap(bytes)
                while (data.hasRemaining()) channel.write(data)
                channel.force(true)
            }
            Files.move(file.path, path, ATOMIC_MOVE)
        }
    }
    private fun readSmall(path: Path): ByteArray {
        PrivateSyncFile.checked(path)
        require(Files.size(path) in 1..MAX_METADATA_BYTES) { "SYNC_OUTBOX_METADATA" }
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { source ->
            val bytes = ByteArray(Files.size(path).toInt()); DataInputStream(source).readFully(bytes)
            require(source.read() == -1) { "SYNC_OUTBOX_METADATA" }; bytes
        }
    }
    private fun identity(path: Path): SyncPlaintextIdentity {
        PrivateSyncFile.checked(path)
        val digest = MessageDigest.getInstance("SHA-256"); var length = 0L
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                interrupted(); val count = input.read(buffer); if (count < 0) break
                require(length <= Long.MAX_VALUE - count) { "SYNC_OUTBOX_LENGTH" }
                digest.update(buffer, 0, count); length += count
            }
        }
        return SyncPlaintextIdentity(length, digest.digest().joinToString("") { "%02x".format(it) })
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_OUTBOX_REENTRY" }
        lock.lockInterruptibly()
        try {
            PrivateSyncFile.checked(root, true)
            val lockFile = root.resolve("outbox.lock")
            FileChannel.open(lockFile, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                PrivateSyncFile.checked(lockFile)
                channel.lock().use { return work() }
            }
        } finally { lock.unlock() }
    }

    companion object {
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
        private const val MAX_METADATA_BYTES = 512L
        private val MAGIC = "CCB-OUTBOX/1\n".toByteArray(Charsets.US_ASCII)
        private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_OUTBOX_INTERRUPTED") }
        private fun encodeBinding(binding: Binding): ByteArray = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { writeBinding(it, binding) }
        }.toByteArray()
        private fun writeBinding(output: DataOutputStream, binding: Binding) {
            output.write(MAGIC)
            for (id in listOf(binding.operation, binding.context.group, binding.context.epoch, binding.context.key.id)) output.write(id.value.toByteArray(Charsets.US_ASCII))
            output.writeByte(binding.context.key.kind.storageCode); output.writeLong(binding.plain.length)
            output.write(binding.plain.sha256.toByteArray(Charsets.US_ASCII))
        }
        private fun readBinding(input: DataInputStream): Binding {
            require(ByteArray(MAGIC.size).also(input::readFully).contentEquals(MAGIC)) { "SYNC_OUTBOX_METADATA" }
            fun text(size: Int) = String(ByteArray(size).also(input::readFully), Charsets.US_ASCII)
            fun id() = SyncStoreId.parse(text(32))
            val operation = id(); val group = id(); val epoch = id(); val objectId = id(); val code = input.readUnsignedByte()
            val kind = requireNotNull(CiphertextObjectKind.entries.firstOrNull { it.storageCode == code }) { "SYNC_OUTBOX_METADATA" }
            return Binding(operation, SyncEncryptionContext(group, epoch, CiphertextObjectKey(kind, objectId)), SyncPlaintextIdentity(input.readLong(), text(64)))
        }
        private fun decodeBinding(bytes: ByteArray): Binding = DataInputStream(bytes.inputStream()).use {
            val binding = readBinding(it); require(it.read() == -1) { "SYNC_OUTBOX_METADATA" }; binding
        }
        private fun encodeReady(ready: Ready): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            writeBinding(it, ready.binding); it.writeLong(ready.descriptor.length); it.write(ready.descriptor.sha256.hex.toByteArray(Charsets.US_ASCII))
        } }.toByteArray()
        private fun decodeReady(bytes: ByteArray): Ready = DataInputStream(bytes.inputStream()).use {
            val binding = readBinding(it); val length = it.readLong()
            val hash = CiphertextSha256.parse(String(ByteArray(64).also(it::readFully), Charsets.US_ASCII))
            require(it.read() == -1) { "SYNC_OUTBOX_METADATA" }; Ready(binding, CiphertextDescriptor(length, hash))
        }
    }
}
