package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.domain.lanSync.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.*
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopLanSyncResourceTest {
    @Test fun phoneBmpSessionBackgroundRetainsOriginalBytesAndPreviewsAfterColdApply() = runBlocking {
        val testRoot = Files.createTempDirectory("desktop-lan-bmp-").toRealPath()
        try {
            val source = Files.createDirectory(testRoot.resolve("phone"))
            val target = Files.createDirectory(testRoot.resolve("desktop"))
            val sourceState = Files.createDirectory(testRoot.resolve("source-state"))
            val targetState = Files.createDirectory(testRoot.resolve("target-state"))
            val images = Files.createDirectory(source.resolve("images"))
            val image = BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR)
            image.setRGB(0, 0, 0xff336699.toInt())
            val bytes = ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "bmp", output))
                output.toByteArray()
            }
            Files.write(images.resolve("original.jpg"), bytes)
            JsonFileStorage(source).saveEntity("character_cards", "card", CharacterCard("card", "合成角色", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            JsonFileStorage(source).saveEntity("chat_sessions", "session",
                ChatSession("session", "card", "合成会话", chatBackground = "images/original.jpg"), ChatSession.serializer())
            fun workspace(root: Path, state: Path) = LanSyncWorkspace(root, state, { root.resolve(it) },
                resourceReference = { _, path -> desktopLanSyncResourceReference(root, path) },
                validateResource = ::validateDesktopLanSyncResource)
            workspace(source, sourceState).capture("phone").use { capture ->
                val plan = LanSyncPlan(UUID.randomUUID().toString(), capture.snapshot, emptyMap())
                workspace(target, targetState).apply(plan, emptyMap(), "desktop", capture.blobs)
                val restored = requireNotNull(JsonFileStorage(target).loadEntity("chat_sessions", "session", ChatSession.serializer()))
                val path = target.resolve(requireNotNull(restored.chatBackground))
                val record = capture.snapshot.records.single { it.type == "chat_sessions" }
                assertContentEquals(bytes, Files.readAllBytes(path))
                val preview = DesktopLanSyncRaster.preview(path, record)
                assertEquals(0xff336699.toInt(), ImageIO.read(preview.inputStream()).getRGB(0, 0))
                workspace(target, targetState).capture("desktop").use { reopened ->
                    assertEquals(capture.snapshot.hashes(), reopened.snapshot.hashes())
                }
                val truncated = Files.write(testRoot.resolve("truncated.jpg"), bytes.copyOf(16))
                assertFails { DesktopLanSyncRaster.decode(truncated, record, 256) }
            }
        } finally { check(testRoot.toFile().deleteRecursively()) }
    }

    @Test fun `actual resource owner reopens synced avatar original cover and equal-byte distinct gallery`() = runBlocking {
        val root = Files.createTempDirectory("lan-desktop-target-").toRealPath()
        val state = Files.createTempDirectory("lan-desktop-state-").toRealPath()
        val phone = Files.createTempDirectory("lan-phone-source-").toRealPath()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val hash = lanSyncHash(bytes); val blob = phone.resolve("synthetic.png"); Files.write(blob, bytes)
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        val first = "ccb-lan-v1/images/$a/$hash/png"; val second = "ccb-lan-v1/images/$b/$hash/png"
        val card = CharacterCard("synthetic-card", "合成角色", avatar = first, coverImage = first,
            chatBackgroundImages = listOf(first, second), chatBackground = second, createdAt = 1, updatedAt = 1)
        val json = Json { encodeDefaults = true }
        val record = LanSyncRecord("character_cards", card.id, json.encodeToJsonElement(CharacterCard.serializer(), card).jsonObject)
        val snapshot = LanSyncSnapshot(deviceId = "phone", records = listOf(record), resources = mapOf(hash to bytes.size.toLong()))
        val resources = DesktopCharacterResourceStore(root)
        val workspace = LanSyncWorkspace(root, state, resources::resolveOwnedReference,
            resourceReference = { _, path -> desktopLanSyncResourceReference(root, path) },
            validateResource = { kind, path, _ -> if (kind == "images") DesktopImageEditing.decode(Files.readAllBytes(path), longestSide = 256).flush() })
        workspace.apply(LanSyncPlan(UUID.randomUUID().toString(), snapshot, emptyMap()), emptyMap(), "computer", mapOf(hash to blob))
        val restored = requireNotNull(JsonFileStorage(root).loadEntity("character_cards", card.id, CharacterCard.serializer()))
        assertContentEquals(bytes, resources.readBytes(requireNotNull(restored.avatar)))
        assertTrue(requireNotNull(restored.coverImage).startsWith("images/character-covers/"))
        assertContentEquals(bytes, resources.readBytes(requireNotNull(restored.coverImage)))
        assertEquals(2, restored.chatBackgroundImages.distinct().size)
        assertEquals(restored.chatBackgroundImages[1], restored.chatBackground)
        restored.chatBackgroundImages.forEach { assertContentEquals(bytes, resources.readBytes(it)) }
        workspace.capture("computer").use { assertEquals(snapshot.hashes(), it.snapshot.hashes()) }
    }
}
