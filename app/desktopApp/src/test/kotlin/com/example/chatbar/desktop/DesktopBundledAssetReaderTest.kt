package com.example.chatbar.desktop

import com.example.chatbar.domain.card.PackagedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBundledAssetReaderTest {
    @Test
    fun `reader resolves only safe classpath logical paths`() {
        val reader = DesktopBundledAssetReader()

        assertTrue(String(reader("fixtures/tiny.txt")).trim() == "desktop-asset-fixture")
        listOf("", "../tiny.txt", "fixtures/../tiny.txt", "/tmp/tiny.txt", "C:\\tmp\\tiny.txt").forEach { value ->
            assertFailsWith<IllegalArgumentException> { reader(value) }
        }
        assertFailsWith<IllegalStateException> { reader("fixtures/missing.txt") }
    }

    @Test
    fun `asset image is materialized as owned relative file and asset delete is no-op`() {
        val root = Files.createTempDirectory("desktop-asset-store-")
        try {
            val store = DesktopCharacterResourceStore(root, DesktopBundledAssetReader())
            val reference = store.materializeImage(
                PackagedImage("asset.txt", "asset:fixtures/tiny.txt"),
                timestamp = 1L,
                resourceId = "fixture",
            )
            assertFalse(java.nio.file.Path.of(reference).isAbsolute)
            assertTrue(String(store.readBytes(reference)).trim() == "desktop-asset-fixture")
            store.deleteOwned("asset:fixtures/tiny.txt")
            assertTrue(String(store.readBytes(reference)).trim() == "desktop-asset-fixture")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
