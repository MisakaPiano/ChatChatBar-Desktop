package com.example.chatbar.desktop

import com.example.chatbar.domain.card.PackagedDocument
import com.example.chatbar.domain.card.PackagedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCharacterResourceStoreTest {
    @Test
    fun `materialized resources use root-relative refs and read back`() = withRoot { root ->
        val store = DesktopCharacterResourceStore(root)

        val imageRef = store.materializeImage(
            PackagedImage("avatar.PNG", "aGVs\nbG8="),
            timestamp = 10L,
            resourceId = "image-id",
        )
        val documentRef = store.materializeDocument(
            PackagedDocument("bad:name?.md", "md", "文档内容"),
            timestamp = 10L,
            resourceId = "document-id",
        )

        assertEquals("images/card_10_image-id.png", imageRef)
        assertEquals("documents/card_10_document-id_bad_name_.md", documentRef)
        assertFalse(Path.of(imageRef).isAbsolute)
        assertFalse(Path.of(documentRef).isAbsolute)
        assertContentEquals("hello".toByteArray(), store.readBytes(imageRef))
        assertEquals("文档内容", store.readText(documentRef))
        assertEquals("card_10_image-id.png", store.fileName(imageRef))
    }

    @Test
    fun `same refs relocate from root A to root B without entity rewrite`() = withRoot { parent ->
        val rootA = Files.createDirectory(parent.resolve("root-a"))
        val rootB = Files.createDirectory(parent.resolve("root-b"))
        val storeA = DesktopCharacterResourceStore(rootA)
        val imageRef = storeA.materializeImage(PackagedImage("a.webp", "QQ=="), 1L, "a")
        val documentRef = storeA.materializeDocument(PackagedDocument("a.txt", "txt", "A"), 1L, "d")

        copyTree(rootA, rootB)
        Files.write(rootB.resolve(imageRef), byteArrayOf(66))
        Files.writeString(rootB.resolve(documentRef), "B")
        val storeB = DesktopCharacterResourceStore(rootB)

        assertContentEquals(byteArrayOf(66), storeB.readBytes(imageRef))
        assertEquals("B", storeB.readText(documentRef))
        assertContentEquals(byteArrayOf(65), storeA.readBytes(imageRef))
        assertEquals("A", storeA.readText(documentRef))
        assertTrue(storeB.resolveOwnedReference(imageRef).startsWith(rootB))
        assertFalse(storeB.resolveOwnedReference(imageRef).startsWith(rootA))
    }

    @Test
    fun `absolute traversal and outside deletion are rejected`() = withRoot { root ->
        val store = DesktopCharacterResourceStore(root)
        val outside = Files.writeString(root.resolveSibling("${root.fileName}-outside.txt"), "keep")
        try {
            assertFailsWith<IllegalArgumentException> { store.readBytes(outside.toString()) }
            assertFailsWith<IllegalArgumentException> { store.readBytes("images/../outside.txt") }
            assertFailsWith<IllegalArgumentException> { store.readBytes("../images/outside.txt") }
            assertFailsWith<IllegalArgumentException> { store.deleteOwned(outside.toString()) }
            assertTrue(Files.exists(outside))
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `asset refs use logical resolver and are not treated as owned paths`() = withRoot { root ->
        val requested = mutableListOf<String>()
        val store = DesktopCharacterResourceStore(root) { logicalPath ->
            requested += logicalPath
            "asset:$logicalPath".toByteArray()
        }

        assertContentEquals("asset:cards/avatar.png".toByteArray(), store.readBytes("asset:cards/avatar.png"))
        val materialized = store.materializeImage(
            PackagedImage("avatar.png", "asset:bundled/avatar.png"),
            timestamp = 2L,
            resourceId = "asset",
        )

        assertEquals(listOf("cards/avatar.png", "bundled/avatar.png"), requested)
        assertEquals("images/card_2_asset.png", materialized)
        assertContentEquals("asset:bundled/avatar.png".toByteArray(), store.readBytes(materialized))
        store.deleteOwned("asset:bundled/avatar.png")
        assertTrue(Files.exists(root.resolve(materialized)))
    }

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("desktop-character-resources-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.sorted().forEach { path ->
                val relative = source.relativize(path)
                val target = destination.resolve(relative)
                if (Files.isDirectory(path)) {
                    if (!Files.exists(target)) Files.createDirectory(target)
                } else {
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }
}
