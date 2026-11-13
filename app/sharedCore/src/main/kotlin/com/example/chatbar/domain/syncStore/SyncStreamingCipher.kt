package com.example.chatbar.domain.syncStore

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import java.io.*
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.DigestInputStream
import java.security.DigestOutputStream

/** All associated-data fields are opaque. Plaintext names, hashes and paths never enter them. */
data class SyncEncryptionContext(val group: SyncStoreId, val epoch: SyncStoreId, val key: CiphertextObjectKey) {
    internal fun associatedData() = "CCB-SYNC-AEAD/1\n${group.value}\n${epoch.value}\n${key.kind.name}\n${key.id.value}"
        .toByteArray(Charsets.US_ASCII)
}

class SyncPreparedCiphertext internal constructor(private val file: PrivateSyncFile,
    val context: SyncEncryptionContext, val descriptor: CiphertextDescriptor,
    internal val plaintextIdentity: SyncPlaintextIdentity) : Closeable {
    val path: Path get() = file.path
    override fun close() = file.close()
}

internal data class SyncPlaintextIdentity(val length: Long, val sha256: String)

/** Only constructed after reading the complete AEAD stream, including its authenticated end. */
class SyncAuthenticatedPlaintext internal constructor(private val file: PrivateSyncFile, val length: Long) : Closeable {
    val path: Path get() = file.path
    override fun close() = file.close()
}

internal class PrivateSyncFile(val path: Path, private val identity: Any?) : Closeable {
    override fun close() {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            checked(path)
            require(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == identity) { "SYNC_AEAD_PREPARATION_CHANGED" }
            Files.delete(path)
        }
    }
    companion object {
        fun checked(path: Path, directory: Boolean = false) {
            require(path.isAbsolute && path.normalize() == path && path.toRealPath() == path &&
                if (directory) Files.isDirectory(path, NOFOLLOW_LINKS) else Files.isRegularFile(path, NOFOLLOW_LINKS)) { "SYNC_AEAD_PRIVATE_PATH" }
            var ancestor: Path? = path
            while (ancestor != null) {
                require(!Files.isSymbolicLink(ancestor) &&
                    !Files.readAttributes(ancestor, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isOther) { "SYNC_AEAD_PRIVATE_PATH" }
                ancestor = ancestor.parent
            }
        }
        fun create(parent: Path): PrivateSyncFile {
            checked(parent, true)
            val path = Files.createTempFile(parent, ".sync-aead-", ".preparing")
            return PrivateSyncFile(path, Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
        }
    }
}

/**
 * Tink AES256_GCM_HKDF_1MB, pinned with the existing Desktop codec. Encryption is randomized;
 * the caller must durably retain the exact prepared ciphertext/request before its first upload.
 * This owner supplies neither device admission nor rollback/freshness proof: those belong to the
 * authenticated version/device ledger. A shared symmetric key alone cannot prove an author.
 */
class SyncStreamingCipher private constructor(val group: SyncStoreId, val epoch: SyncStoreId,
    private val handle: KeysetHandle) {
    private val primitive: StreamingAead = handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)

    fun encrypt(source: InputStream, context: SyncEncryptionContext, privateDirectory: Path): SyncPreparedCiphertext {
        requireContext(context)
        val file = PrivateSyncFile.create(privateDirectory)
        try {
            val plainDigest = MessageDigest.getInstance("SHA-256")
            val plainLength = FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val output = Channels.newOutputStream(channel)
                // Closing the AEAD stream writes its final authenticated segment; retain the channel for force.
                val borrowed = object : FilterOutputStream(output) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
                    override fun close() { flush() }
                }
                val length = primitive.newEncryptingStream(borrowed, context.associatedData()).use { copy(DigestInputStream(source, plainDigest), it) }
                channel.force(true)
                length
            }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file.path, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) { interrupted(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val hash = CiphertextSha256.parse(digest.digest().joinToString("") { "%02x".format(it) })
            return SyncPreparedCiphertext(file, context, CiphertextDescriptor(Files.size(file.path), hash),
                SyncPlaintextIdentity(plainLength, plainDigest.digest().joinToString("") { "%02x".format(it) }))
        } catch (failure: Throwable) { file.close(); throw failure }
    }

    fun decrypt(source: InputStream, context: SyncEncryptionContext, privateDirectory: Path): SyncAuthenticatedPlaintext {
        requireContext(context)
        val file = PrivateSyncFile.create(privateDirectory)
        try {
            val borrowed = object : FilterInputStream(source) { override fun close() {} }
            val length = primitive.newDecryptingStream(borrowed, context.associatedData()).use { clear ->
                FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val count = copy(clear, Channels.newOutputStream(channel)); channel.force(true); count
                }
            }
            return SyncAuthenticatedPlaintext(file, length)
        } catch (failure: Throwable) { file.close(); throw failure }
    }

    /** The platform protection owner must encrypt/authenticate this small key envelope locally. */
    fun protectedKeyset(protect: (ByteArray) -> ByteArray): ByteArray {
        val keyBytes = ByteArrayOutputStream().also { CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(it)) }.toByteArray()
        try {
            require(keyBytes.size in 1..MAX_KEY_BYTES) { "SYNC_AEAD_KEYSET" }
            val envelope = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
                it.write(KEY_MAGIC); it.write(group.value.toByteArray(Charsets.US_ASCII)); it.write(epoch.value.toByteArray(Charsets.US_ASCII))
                it.writeInt(keyBytes.size); it.write(keyBytes)
            } }.toByteArray()
            try { return protect(envelope).copyOf().also { require(it.size in 1..MAX_PROTECTED_BYTES) { "SYNC_AEAD_KEYSET" } } }
            finally { envelope.fill(0) }
        } finally { keyBytes.fill(0) }
    }

    private fun requireContext(context: SyncEncryptionContext) {
        require(context.group == group && context.epoch == epoch) { "SYNC_AEAD_CONTEXT" }
    }

    /** Recovery verification consumes plaintext only into a digest; no partial content escapes. */
    internal fun verify(source: InputStream, context: SyncEncryptionContext): SyncPlaintextIdentity {
        requireContext(context)
        val borrowed = object : FilterInputStream(source) { override fun close() {} }
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        }
        val length = primitive.newDecryptingStream(borrowed, context.associatedData()).use {
            copy(it, DigestOutputStream(sink, digest))
        }
        return SyncPlaintextIdentity(length, digest.digest().joinToString("") { "%02x".format(it) })
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_KEY_BYTES = 32 * 1024
        private const val MAX_PROTECTED_BYTES = 64 * 1024
        private val KEY_MAGIC = "CCB-SYNC-KEY/1\n".toByteArray(Charsets.US_ASCII)
        fun create(group: SyncStoreId = SyncStoreId.random(), epoch: SyncStoreId = SyncStoreId.random()): SyncStreamingCipher {
            StreamingAeadConfig.register()
            return SyncStreamingCipher(group, epoch, KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB))
        }
        fun open(group: SyncStoreId, epoch: SyncStoreId, protected: ByteArray, unprotect: (ByteArray) -> ByteArray): SyncStreamingCipher {
            require(protected.size in 1..MAX_PROTECTED_BYTES) { "SYNC_AEAD_KEYSET" }
            val borrowed = protected.copyOf()
            val envelope = try { unprotect(borrowed) } catch (failure: Throwable) { borrowed.fill(0); throw failure }
            try {
                require(envelope.size in 1..MAX_PROTECTED_BYTES) { "SYNC_AEAD_KEYSET" }
                DataInputStream(envelope.inputStream()).use { input ->
                    require(ByteArray(KEY_MAGIC.size).also(input::readFully).contentEquals(KEY_MAGIC)) { "SYNC_AEAD_KEYSET" }
                    fun id() = SyncStoreId.parse(String(ByteArray(32).also(input::readFully), Charsets.US_ASCII))
                    require(id() == group && id() == epoch) { "SYNC_AEAD_CONTEXT" }
                    val size = input.readInt(); require(size in 1..MAX_KEY_BYTES) { "SYNC_AEAD_KEYSET" }
                    val keyBytes = ByteArray(size).also(input::readFully)
                    try {
                        require(input.read() == -1) { "SYNC_AEAD_KEYSET" }
                        StreamingAeadConfig.register()
                        return SyncStreamingCipher(group, epoch, CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(keyBytes)))
                    } finally { keyBytes.fill(0) }
                }
            } finally { envelope.fill(0); borrowed.fill(0) }
        }
        private fun interrupted() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_AEAD_INTERRUPTED")
        }
        private fun copy(input: InputStream, output: OutputStream): Long {
            val buffer = ByteArray(BUFFER_BYTES); var total = 0L
            while (true) {
                interrupted(); val count = input.read(buffer); if (count < 0) break
                require(total <= Long.MAX_VALUE - count) { "SYNC_AEAD_LENGTH" }
                output.write(buffer, 0, count); total += count
            }
            return total
        }
    }
}
