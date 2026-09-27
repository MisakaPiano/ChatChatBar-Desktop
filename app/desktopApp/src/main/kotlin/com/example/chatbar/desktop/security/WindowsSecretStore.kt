package com.example.chatbar.desktop.security

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class WindowsSecretStore internal constructor(
    private val secretRoot: Path,
    private val protector: SecretProtector,
    private val blobWriter: SecretBlobWriter = AtomicSecretBlobWriter,
) : DesktopSecretStore {
    override fun load(key: DesktopCredentialKey): String? {
        if (!validateRootForRead()) return null
        val target = secretPath(key)
        if (!validateTargetForRead(target)) return null
        val envelope = try {
            val size = Files.size(target)
            if (size > MAX_ENVELOPE_BYTES) {
                throw DesktopSecretStoreException(
                    DesktopSecretStoreFailureKind.MALFORMED_ENVELOPE,
                    "Credential envelope exceeds the supported size",
                )
            }
            Files.readAllBytes(target)
        } catch (error: DesktopSecretStoreException) {
            throw error
        } catch (error: IOException) {
            throw storageFailure("Credential envelope could not be read", error)
        }
        val ciphertext = try {
            SecretEnvelope.decode(envelope)
        } finally {
            envelope.fill(0)
        }
        val plaintext = try {
            protector.unprotect(ciphertext)
        } finally {
            ciphertext.fill(0)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext))
                .toString()
        } catch (error: Exception) {
            throw DesktopSecretStoreException(
                DesktopSecretStoreFailureKind.PROTECTION_FAILURE,
                "Decrypted credential payload is not valid UTF-8",
                error,
            )
        } finally {
            plaintext.fill(0)
        }
    }

    override fun save(key: DesktopCredentialKey, secret: String) {
        prepareRootForWrite()
        val target = secretPath(key)
        validateTargetForRead(target)
        val plaintext = secret.toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            protector.protect(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val envelope = try {
            SecretEnvelope.encode(ciphertext)
        } finally {
            ciphertext.fill(0)
        }
        try {
            blobWriter.write(target, envelope)
        } catch (error: IOException) {
            throw storageFailure("Credential envelope could not be written", error)
        } finally {
            envelope.fill(0)
        }
    }

    override fun delete(key: DesktopCredentialKey) {
        if (!validateRootForRead()) return
        val target = secretPath(key)
        if (!validateTargetForRead(target)) return
        try {
            Files.delete(target)
        } catch (error: IOException) {
            throw storageFailure("Credential envelope could not be deleted", error)
        }
    }

    internal fun secretPath(key: DesktopCredentialKey): Path =
        secretRoot.resolve(SecretFileNamePolicy.fileName(key))

    private fun validateRootForRead(): Boolean {
        val attributes = readAttributesOrMissing(secretRoot) ?: return false
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            throw storageFailure("Credential root is not a safe directory")
        }
        return true
    }

    private fun prepareRootForWrite() {
        try {
            val attributes = readAttributesOrMissing(secretRoot)
            if (attributes != null) {
                if (attributes.isSymbolicLink || !attributes.isDirectory) {
                    throw storageFailure("Credential root is not a safe directory")
                }
            } else {
                Files.createDirectories(secretRoot)
                val created = readAttributesOrMissing(secretRoot)
                    ?: throw storageFailure("Credential root was not created")
                if (created.isSymbolicLink || !created.isDirectory) {
                    throw storageFailure("Credential root is not a safe directory")
                }
            }
        } catch (error: DesktopSecretStoreException) {
            throw error
        } catch (error: IOException) {
            throw storageFailure("Credential root could not be prepared", error)
        }
    }

    private fun validateTargetForRead(target: Path): Boolean {
        val attributes = readAttributesOrMissing(target) ?: return false
        if (attributes.isSymbolicLink || !attributes.isRegularFile) {
            throw storageFailure("Credential target is not a safe regular file")
        }
        return true
    }

    private fun readAttributesOrMissing(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    } catch (error: IOException) {
        throw storageFailure("Credential storage path could not be inspected", error)
    } catch (error: SecurityException) {
        throw storageFailure("Credential storage path access was denied", error)
    }

    private fun storageFailure(message: String, cause: Throwable? = null) =
        DesktopSecretStoreException(DesktopSecretStoreFailureKind.STORAGE_FAILURE, message, cause)

    companion object {
        fun create(selectedAppDataRoot: Path): WindowsSecretStore = WindowsSecretStore(
            secretRoot = WindowsSecretRootResolver.resolve(selectedAppDataRoot),
            protector = WindowsDpapiSecretProtector(),
        )
    }
}

internal fun interface SecretBlobWriter {
    @Throws(IOException::class)
    fun write(target: Path, envelope: ByteArray)
}

internal object AtomicSecretBlobWriter : SecretBlobWriter {
    override fun write(target: Path, envelope: ByteArray) {
        val parent = target.parent ?: throw IOException("Credential target has no parent directory")
        val temporary = Files.createTempFile(parent, ".ccb-secret-", ".tmp")
        try {
            Files.write(
                temporary,
                envelope,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

internal object SecretFileNamePolicy {
    fun fileName(key: DesktopCredentialKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.canonicalValue().toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        } + FILE_SUFFIX
    }

    private const val FILE_SUFFIX = ".ccbsecret"
}

private object SecretEnvelope {
    fun encode(ciphertext: ByteArray): ByteArray {
        require(ciphertext.isNotEmpty()) { "Protected credential payload must not be empty" }
        return ByteBuffer.allocate(HEADER_SIZE + ciphertext.size)
            .put(MAGIC)
            .put(VERSION)
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
    }

    fun decode(envelope: ByteArray): ByteArray {
        if (envelope.size < HEADER_SIZE) malformed("Credential envelope is truncated")
        val buffer = ByteBuffer.wrap(envelope)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) malformed("Credential envelope has an invalid header")
        val version = buffer.get()
        if (version != VERSION) {
            throw DesktopSecretStoreException(
                DesktopSecretStoreFailureKind.UNSUPPORTED_ENVELOPE_VERSION,
                "Credential envelope version is unsupported",
            )
        }
        val ciphertextSize = buffer.int
        if (ciphertextSize <= 0 || ciphertextSize != buffer.remaining()) {
            malformed("Credential envelope length is invalid")
        }
        return ByteArray(ciphertextSize).also(buffer::get)
    }

    private fun malformed(message: String): Nothing = throw DesktopSecretStoreException(
        DesktopSecretStoreFailureKind.MALFORMED_ENVELOPE,
        message,
    )

    private val MAGIC = byteArrayOf(0x43, 0x43, 0x42, 0x53, 0x45, 0x43)
    private const val VERSION: Byte = 1
    private val HEADER_SIZE = MAGIC.size + Byte.SIZE_BYTES + Int.SIZE_BYTES
}

private const val MAX_ENVELOPE_BYTES = 1024 * 1024L
