package com.example.chatbar.desktop.security

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsSecretStoreTest {
    private lateinit var root: Path
    private lateinit var store: WindowsSecretStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("windows-secret-store-")
        store = WindowsSecretStore(root, DeterministicTestProtector())
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `save load overwrite delete and missing remain distinct`() {
        val key = DesktopCredentialKey.SiliconFlowApiKey
        assertNull(store.load(key))

        store.save(key, "fake-test-key")
        assertEquals("fake-test-key", store.load(key))

        store.save(key, "fake-updated-key")
        assertEquals("fake-updated-key", store.load(key))

        store.delete(key)
        assertNull(store.load(key))
        store.delete(key)
    }

    @Test
    fun `logical keys including colon IDs do not collide or appear in physical filenames`() {
        val global = DesktopCredentialKey.SiliconFlowApiKey
        val vision = DesktopCredentialKey.ModelApiKey("preset:vision")
        val other = DesktopCredentialKey.ModelApiKey("preset/vision")

        store.save(global, "fake-global-key")
        store.save(vision, "fake-vision-key")
        store.save(other, "fake-other-key")

        assertEquals("fake-global-key", store.load(global))
        assertEquals("fake-vision-key", store.load(vision))
        assertEquals("fake-other-key", store.load(other))
        val paths = listOf(store.secretPath(global), store.secretPath(vision), store.secretPath(other))
        assertEquals(3, paths.distinct().size)
        paths.forEach { path ->
            val name = path.fileName.toString()
            assertTrue(name.matches(Regex("[0-9a-f]{64}\\.ccbsecret")))
            assertFalse(name.contains("preset", ignoreCase = true))
            assertFalse(name.contains(':'))
            assertEquals(root, path.parent)
        }
    }

    @Test
    fun `persisted envelope does not contain plaintext`() {
        val key = DesktopCredentialKey.ModelApiKey("preset:vision")
        val fakeSecret = "fake-test-key"
        store.save(key, fakeSecret)

        val stored = Files.readAllBytes(store.secretPath(key))

        assertFalse(stored.containsSubsequence(fakeSecret.toByteArray(Charsets.UTF_8)))
        assertFalse(stored.contentEquals(fakeSecret.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `truncated envelope is failure and encrypted bytes remain unchanged`() {
        val key = DesktopCredentialKey.SiliconFlowApiKey
        Files.write(store.secretPath(key), byteArrayOf(0x43, 0x43, 0x42))
        val before = Files.readAllBytes(store.secretPath(key))

        val error = assertFailsWith<DesktopSecretStoreException> { store.load(key) }

        assertEquals(DesktopSecretStoreFailureKind.MALFORMED_ENVELOPE, error.kind)
        assertContentEquals(before, Files.readAllBytes(store.secretPath(key)))
    }

    @Test
    fun `decrypt failure is explicit and preserves original encrypted bytes`() {
        val key = DesktopCredentialKey.SiliconFlowApiKey
        store.save(key, "fake-test-key")
        val target = store.secretPath(key)
        val before = Files.readAllBytes(target)
        val failingStore = WindowsSecretStore(root, FailingUnprotectProtector())

        val error = assertFailsWith<DesktopSecretStoreException> { failingStore.load(key) }

        assertEquals(DesktopSecretStoreFailureKind.PROTECTION_FAILURE, error.kind)
        assertContentEquals(before, Files.readAllBytes(target))
    }

    @Test
    fun `failed replacement leaves existing value intact`() {
        val key = DesktopCredentialKey.SiliconFlowApiKey
        store.save(key, "fake-existing-key")
        val failingStore = WindowsSecretStore(
            secretRoot = root,
            protector = DeterministicTestProtector(),
            blobWriter = SecretBlobWriter { _, _ -> throw IOException("injected write failure") },
        )

        val error = assertFailsWith<DesktopSecretStoreException> {
            failingStore.save(key, "fake-replacement-key")
        }

        assertEquals(DesktopSecretStoreFailureKind.STORAGE_FAILURE, error.kind)
        assertEquals("fake-existing-key", store.load(key))
        assertTrue(Files.list(root).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun `unsafe root is explicit storage failure rather than missing`() {
        val fileRoot = root.resolve("not-a-directory")
        Files.writeString(fileRoot, "not a secret")
        val unsafeStore = WindowsSecretStore(fileRoot, DeterministicTestProtector())

        val loadError = assertFailsWith<DesktopSecretStoreException> {
            unsafeStore.load(DesktopCredentialKey.SiliconFlowApiKey)
        }
        val saveError = assertFailsWith<DesktopSecretStoreException> {
            unsafeStore.save(DesktopCredentialKey.SiliconFlowApiKey, "fake-test-key")
        }

        assertEquals(DesktopSecretStoreFailureKind.STORAGE_FAILURE, loadError.kind)
        assertEquals(DesktopSecretStoreFailureKind.STORAGE_FAILURE, saveError.kind)
    }

    private class DeterministicTestProtector : SecretProtector {
        override fun protect(plaintext: ByteArray): ByteArray =
            plaintext.reversedArray().map { byte -> (byte.toInt() xor MASK).toByte() }.toByteArray()

        override fun unprotect(ciphertext: ByteArray): ByteArray =
            ciphertext.map { byte -> (byte.toInt() xor MASK).toByte() }.toByteArray().reversedArray()

        private companion object {
            const val MASK = 0x5a
        }
    }

    private class FailingUnprotectProtector : SecretProtector {
        override fun protect(plaintext: ByteArray): ByteArray = plaintext.copyOf()

        override fun unprotect(ciphertext: ByteArray): ByteArray = throw DesktopSecretStoreException(
            DesktopSecretStoreFailureKind.PROTECTION_FAILURE,
            "Injected protection failure",
        )
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty()) return true
        return indices.any { start ->
            start + candidate.size <= size &&
                candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
    }
}
