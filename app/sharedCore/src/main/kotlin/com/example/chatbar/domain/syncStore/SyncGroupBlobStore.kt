package com.example.chatbar.domain.syncStore

import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE

/** Detached ciphertext locations can rotate while the signed logical plaintext identity stays fixed. */
class SyncGroupBlobStore(private val group: SyncStoreId, private val privateDirectory: Path) {
    init { PrivateSyncFile.checked(privateDirectory, true) }

    fun enqueue(outbox: SyncEncryptedOutbox, operation: SyncStoreId, key: CiphertextObjectKey,
        cipher: SyncStreamingCipher, source: Path, expected: SyncPlaintextIdentity): SyncEnvelopeBlobBinding {
        require(key.kind == CiphertextObjectKind.BLOB && cipher.group == group) { "SYNC_GROUP_BLOB_CONTEXT" }
        val context = SyncEncryptionContext(group, cipher.epoch, key)
        val ready = outbox.prepare(operation, context, source, expectedPlaintext = expected)
        return SyncEnvelopeBlobBinding(expected, key, cipher.epoch, ready.request.descriptor)
    }

    fun read(backend: CiphertextStore, cipher: SyncStreamingCipher, binding: SyncEnvelopeBlobBinding,
        expected: SyncPlaintextIdentity): SyncAuthenticatedPlaintext {
        require(cipher.group == group && cipher.epoch == binding.epoch) { "SYNC_GROUP_BLOB_CONTEXT" }
        require(binding.plaintext == expected) { "SYNC_GROUP_BLOB_PLAINTEXT_IDENTITY" }
        PrivateSyncFile.create(privateDirectory).use { ciphertext ->
            val metadata = FileChannel.open(ciphertext.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val result = requireNotNull(backend.readTo(binding.key, Channels.newOutputStream(channel), binding.descriptor)) { "SYNC_GROUP_BLOB_MISSING" }
                channel.force(true); result
            }
            require(metadata == CiphertextObjectMetadata(binding.key, binding.descriptor)) { "SYNC_GROUP_BLOB_CONTEXT" }
            Files.newInputStream(ciphertext.path, NOFOLLOW_LINKS).use { input ->
                val plaintext = cipher.decrypt(input, SyncEncryptionContext(group, binding.epoch, binding.key), privateDirectory)
                try {
                    require(plaintext.identity == expected) { "SYNC_GROUP_BLOB_PLAINTEXT_IDENTITY" }
                    return plaintext
                } catch (failure: Throwable) { plaintext.close(); throw failure }
            }
        }
    }
}
