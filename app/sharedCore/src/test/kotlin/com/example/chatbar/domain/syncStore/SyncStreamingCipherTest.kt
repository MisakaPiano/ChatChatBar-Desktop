package com.example.chatbar.domain.syncStore

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SyncStreamingCipherTest {
    private fun context(cipher: SyncStreamingCipher, kind: CiphertextObjectKind = CiphertextObjectKind.BLOB) =
        SyncEncryptionContext(cipher.group, cipher.epoch, CiphertextObjectKey(kind, SyncStoreId.random()))
    private fun privateRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("sync-aead-synthetic-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }
    // Synthetic reversible protection tests only the key-envelope boundary, never platform custody.
    private fun protect(bytes: ByteArray) = bytes.reversedArray()
    private fun count(root: Path) = Files.newDirectoryStream(root).use { it.count() }

    @Test fun actualMultiSegmentCiphertextUsesTheFileBackendAndReopensWithProtectedKeyset() = privateRoot { root ->
        val cipher = SyncStreamingCipher.create(); val binding = context(cipher)
        val plain = ByteArray(3 * 1024 * 1024 + 29) { ((it * 17) % 251).toByte() }
        var closed = false
        val input = object : ByteArrayInputStream(plain) { override fun close() { closed = true; super.close() } }
        val protected = cipher.protectedKeyset(::protect); val frozen = protected.copyOf()
        val reopened = SyncStreamingCipher.open(cipher.group, cipher.epoch, protected, ::protect)
        assertEquals(cipher.keyCommitment(), reopened.keyCommitment())
        assertNotEquals(cipher.keyCommitment(), SyncStreamingCipher.create(cipher.group, cipher.epoch).keyCommitment())
        assertContentEquals(frozen, protected)
        val store = FileCiphertextStore(root.resolve("backend"))
        cipher.encrypt(input, binding, root).use { encrypted ->
            assertFalse(closed); assertTrue(encrypted.descriptor.length > plain.size)
            val request = CiphertextPutRequest(binding.key, SyncStoreId.random(), encrypted.descriptor)
            Files.newInputStream(encrypted.path).use { store.put(request, it) }
            val downloaded = Files.createTempFile(root, "download-", ".cipher")
            Files.newOutputStream(downloaded).use { store.readTo(binding.key, it, encrypted.descriptor) }
            Files.newInputStream(downloaded).use { source -> reopened.decrypt(source, binding, root).use { authenticated ->
                assertEquals(plain.size.toLong(), authenticated.length)
                assertContentEquals(plain, Files.readAllBytes(authenticated.path))
            } }
            Files.delete(downloaded)
        }
        assertFalse(closed)
    }

    @Test fun encryptionIsRandomizedAndDifferentGroupsEpochsObjectsAndKindsCannotBeSubstituted() = privateRoot { root ->
        val cipher = SyncStreamingCipher.create(); val binding = context(cipher)
        val bytes = "只在本地模拟的正文".toByteArray(Charsets.UTF_8)
        cipher.encrypt(bytes.inputStream(), binding, root).use { one ->
            cipher.encrypt(bytes.inputStream(), binding, root).use { two ->
                assertNotEquals(one.descriptor.sha256, two.descriptor.sha256)
            }
            val ciphertext = Files.readAllBytes(one.path)
            for (changed in listOf(binding.copy(group = SyncStoreId.random()), binding.copy(epoch = SyncStoreId.random()),
                binding.copy(key = CiphertextObjectKey(binding.key.kind, SyncStoreId.random())),
                binding.copy(key = binding.key.copy(kind = CiphertextObjectKind.COMMIT)))) {
                assertFails { cipher.decrypt(ciphertext.inputStream(), changed, root).close() }
                assertEquals(1, count(root))
            }
            val wrongKey = SyncStreamingCipher.create(cipher.group, cipher.epoch)
            assertFails { wrongKey.decrypt(ciphertext.inputStream(), binding, root).close() }
            assertEquals(1, count(root))
        }
        assertEquals(0, count(root))
    }

    @Test fun tamperTruncationAndTailFailureNeverReturnOrRetainAnAuthenticatedPlaintextFile() = privateRoot { root ->
        val cipher = SyncStreamingCipher.create(); val binding = context(cipher)
        val plain = ByteArray(2 * 1024 * 1024 + 211) { (it % 233).toByte() }
        cipher.encrypt(plain.inputStream(), binding, root).use { encrypted ->
            val bytes = Files.readAllBytes(encrypted.path)
            val damaged = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            for (invalid in listOf(damaged, bytes.copyOf(bytes.size - 1), bytes.copyOf(bytes.size / 2), bytes + 0)) {
                assertFails { cipher.decrypt(invalid.inputStream(), binding, root).close() }
                assertEquals(1, count(root))
            }
            var closed = false
            val failing = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (pos > bytes.size - 1000) throw IOException("synthetic tail read failure")
                    return super.read(buffer, offset, minOf(length, 4096))
                }
                override fun close() { closed = true; super.close() }
            }
            assertFails { cipher.decrypt(failing, binding, root).close() }
            assertFalse(closed); assertEquals(1, count(root))
        }
        assertEquals(0, count(root))
    }

    @Test fun emptyStreamsRemainAuthenticatedAndSourceFailureRemovesUnpublishedCiphertext() = privateRoot { root ->
        val cipher = SyncStreamingCipher.create(); val binding = context(cipher, CiphertextObjectKind.COMMIT)
        cipher.encrypt(byteArrayOf().inputStream(), binding, root).use { encrypted ->
            Files.newInputStream(encrypted.path).use { input -> cipher.decrypt(input, binding, root).use {
                assertEquals(0L, it.length); assertEquals(0L, Files.size(it.path))
            } }
        }
        val source = object : InputStream() { override fun read(): Int = throw IOException("synthetic source failure") }
        assertFailsWith<IOException> { cipher.encrypt(source, binding, root) }
        assertEquals(0, count(root))
        val protected = cipher.protectedKeyset(::protect)
        assertFails { SyncStreamingCipher.open(SyncStoreId.random(), cipher.epoch, protected, ::protect) }
        assertFails { SyncStreamingCipher.open(cipher.group, SyncStoreId.random(), protected, ::protect) }
        assertFails { SyncStreamingCipher.open(cipher.group, cipher.epoch, protected.copyOf(protected.size - 1), ::protect) }
    }
}
