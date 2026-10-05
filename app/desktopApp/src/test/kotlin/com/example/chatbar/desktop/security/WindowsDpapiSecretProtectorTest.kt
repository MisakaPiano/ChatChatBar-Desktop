package com.example.chatbar.desktop.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowsDpapiSecretProtectorTest {
    @Test
    fun `real Windows DPAPI protects current-user secret without plaintext persistence`() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return

        val root = Files.createTempDirectory("windows-dpapi-secret-store-")
        try {
            val store = WindowsSecretStore(root, WindowsDpapiSecretProtector())
            val key = DesktopCredentialKey.NovelAiToken
            val fakeSecret = "fake-test-key"

            store.save(key, fakeSecret)

            assertEquals(fakeSecret, store.load(key))
            val stored = Files.readAllBytes(store.secretPath(key))
            assertFalse(stored.toString(Charsets.ISO_8859_1).contains(fakeSecret))
            val reopened = WindowsSecretStore(root, WindowsDpapiSecretProtector())
            assertEquals(fakeSecret, reopened.load(key))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
