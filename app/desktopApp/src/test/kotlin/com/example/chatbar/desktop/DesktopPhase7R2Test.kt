package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.repository.CharacterRepository
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopPhase7R2Test {
    @Test fun `background library restart and preference never mutate cross-platform card`() = runBlocking {
        fixture { storage, resources, gate, library, root ->
            val card = CharacterCard.create("Card").copy(chatBackground = "official-background.png")
            val cards = CharacterRepository(storage); cards.save(card)
            library.import(card.id, png()); library.import(card.id, png())
            val entries = library.load(card.id)
            assertEquals(2, entries.images.size); assertNotEquals(entries.images[0], entries.images[1])
            library.prefer(card.id, entries.images[1])
            val reopened = DesktopCharacterBackgrounds(JsonFileStorage(root, gate), resources, gate) {}
            assertEquals(entries.images[1], reopened.preferred(card.id))
            assertEquals(card, cards.getById(card.id))
            library.prefer(card.id, null)
            assertEquals("official-background.png", desktopEffectiveBackground(null, library.preferred(card.id), card.chatBackground).first)
            assertEquals("session.png", desktopEffectiveBackground("session.png", entries.images[1], card.chatBackground).first)
        }
    }

    @Test fun `exact deletion retains shared reference and removes only unreferenced candidate`() = runBlocking {
        fixture { storage, resources, _, library, _ ->
            library.import("a", png()); library.import("a", png())
            val paths = library.load("a").images
            storage.saveSingleton("another_owner", buildJsonObject { put("image", paths[0]) }, JsonObject.serializer())
            library.prefer("a", paths[1])
            library.remove("a", paths[1])
            assertNull(library.load("a").preferred)
            assertFails { resources.readBytes(paths[1]) }
            library.remove("a", paths[0])
            assertTrue(resources.readBytes(paths[0]).isNotEmpty())
        }
    }

    @Test fun `structurally corrupt authority blocks imports and all destructive cleanup`() = runBlocking {
        fixture { storage, resources, gate, library, root ->
            library.import("a", png())
            val path = library.load("a").images.single()
            storage.saveEntity(DesktopCharacterBackgrounds.KEY, "a", buildJsonObject { put("images", 9); put("preferred", JsonNull) }, JsonObject.serializer())
            val count = Files.list(root.resolve("images")).use { it.count() }
            assertFails { library.import("a", png()) }
            assertFails { library.remove("a", path) }
            assertFails { DesktopOwnedImageCleanup(root, resources, gate).deleteUnreferenced(listOf(path)) }
            assertEquals(count, Files.list(root.resolve("images")).use { it.count() })
            assertTrue(resources.readBytes(path).isNotEmpty())
        }
    }

    @Test fun `missing preferred file does not replace official fallback or discard library`() = runBlocking {
        fixture { _, _, _, library, root ->
            library.import("a", png())
            val path = library.load("a").images.single(); library.prefer("a", path)
            Files.delete(root.resolve(path))
            val preferred = runCatching { library.preferred("a") }.getOrNull()
            assertEquals("official", desktopEffectiveBackground(null, preferred, "official").first)
            assertEquals(path, library.load("a").preferred)
        }
    }

    @Test fun `paint stroke fills gaps on export copy and preserves source outside brush`() {
        val source = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        source.createGraphics().let { it.color = java.awt.Color.BLUE; it.fillRect(0, 0, 100, 100); it.dispose() }
        val before = source.pixels()
        val copy = DesktopImageTools.paint(source, listOf(.1f to .5f, .9f to .5f), .05f, 0xff000000.toInt())
        assertEquals(0xff000000.toInt(), copy.getRGB(50, 49))
        assertEquals(source.getRGB(50, 10), copy.getRGB(50, 10))
        assertContentEquals(before, source.pixels())
        val mosaic = DesktopImageTools.paint(source, listOf(.5f to .5f), .05f, null)
        assertContentEquals(before, source.pixels()); assertEquals(source.getRGB(0, 0), mosaic.getRGB(0, 0))
    }

    private fun png() = DesktopImageEditing.png(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))
    private suspend fun fixture(block: suspend (JsonFileStorage, DesktopCharacterResourceStore, DesktopDataOperationCoordinator, DesktopCharacterBackgrounds, java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("p7-r2-")
        val gate = DesktopDataOperationCoordinator()
        val storage = JsonFileStorage(root, gate)
        val resources = DesktopCharacterResourceStore(root)
        val cleanup = DesktopOwnedImageCleanup(root, resources, gate)
        try { block(storage, resources, gate, DesktopCharacterBackgrounds(storage, resources, gate, cleanup::deleteUnreferenced), root) }
        finally { root.toFile().deleteRecursively() }
    }
}
