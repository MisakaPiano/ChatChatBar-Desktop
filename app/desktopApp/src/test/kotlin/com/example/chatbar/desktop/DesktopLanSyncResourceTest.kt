package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
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
    @Test fun malformedKnownWebpAndHeifAreHeldOnlyInTheOwningConversation(): Unit = runBlocking {
        val root = Files.createTempDirectory("desktop-identified-damaged-chat-image-").toRealPath()
        try {
            val data = Files.createDirectory(root.resolve("data"))
            val images = Files.createDirectory(data.resolve("images"))
            val png = ByteArrayOutputStream().apply {
                com.example.chatbar.domain.image.PrivacyPngEncoder.write(this, 16, 12) { _, pixels -> pixels.fill(0xff2359b4.toInt()) }
            }.toByteArray()
            val original = org.jetbrains.skia.Image.makeFromEncoded(png).use { image ->
                requireNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP, 100)).use { it.bytes }
            }
            val image = images.resolve("owned.webp")
            Files.write(image, original)
            val storage = JsonFileStorage(data)
            for (id in listOf("has_image", "text_only")) {
                storage.saveEntity("chat_sessions", id,
                    ChatSession(id, "card", id, createdAt = 1, updatedAt = 1), ChatSession.serializer())
            }
            val owningMessage = ChatMessage("photo", "has_image", MessageRole.USER, "保留原图",
                images = listOf("images/owned.webp"), createdAt = 2, updatedAt = 2)
            storage.saveEntity("chat_messages", "has_image_photo", owningMessage, ChatMessage.serializer())
            storage.saveEntity("chat_messages", "text_only_dialogue",
                ChatMessage("dialogue", "text_only", MessageRole.USER, "独立文字", createdAt = 2, updatedAt = 2), ChatMessage.serializer())
            val workspace = LanSyncWorkspace(data, Files.createDirectory(root.resolve("state")),
                DesktopCharacterResourceStore(data)::resolveOwnedReference,
                resourceReference = { _, path -> desktopLanSyncResourceReference(data, path) }, validateResource = ::validateDesktopLanSyncResource)
            val originalHashes = workspace.captureGroup("desktop").use { it.snapshot.hashes() }
            val malformed = listOf(original.copyOf(16),
                byteArrayOf(0, 0, 0, 20, 102, 116, 121, 112, 104, 101, 105, 99, 0, 0, 0, 0, 109, 105, 102, 49))
            for (broken in malformed) {
                Files.write(image, broken)
                assertFailsWith<LanSyncInvalidImage> { workspace.capture("desktop").close() }
                workspace.captureGroup("desktop").use { capture ->
                    assertEquals(setOf("conversation/has_image"), capture.blockedResourceUnits)
                    assertEquals(originalHashes["conversation/text_only"], capture.snapshot.hashes()["conversation/text_only"])
                }
                assertContentEquals(broken, Files.readAllBytes(image))
                assertEquals(owningMessage, storage.loadEntity("chat_messages", "has_image_photo", ChatMessage.serializer()))
            }
            Files.write(image, original)
            workspace.captureGroup("desktop").use {
                assertTrue(it.blockedResourceUnits.isEmpty())
                assertEquals(originalHashes, it.snapshot.hashes())
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun ordinary48MpJpegPreservesTheOriginalAndDoesNotBlockAnotherConversation(): Unit = runBlocking {
        val root = Files.createTempDirectory("lan-48mp-").toRealPath()
        try {
            val source = Files.createDirectory(root.resolve("phone"))
            val target = Files.createDirectory(root.resolve("desktop"))
            val image = Files.createDirectory(source.resolve("images")).resolve("camera.jpg")
            val raster = BufferedImage(8000, 6000, BufferedImage.TYPE_BYTE_GRAY)
            try { check(ImageIO.write(raster, "jpeg", image.toFile())) } finally { raster.flush() }
            val bytes = Files.size(image)
            val expectedHash = LanSyncResourceStore.hashFile(image, bytes)
            val sourceStorage = JsonFileStorage(source)
            sourceStorage.saveEntity("chat_sessions", "photo",
                ChatSession("photo", "card", "合成相片会话", chatBackground = "images/camera.jpg", createdAt = 1, updatedAt = 1), ChatSession.serializer())
            sourceStorage.saveEntity("chat_sessions", "text",
                ChatSession("text", "card", "合成独立会话", createdAt = 2, updatedAt = 2), ChatSession.serializer())
            sourceStorage.saveEntity("chat_messages", "text_m", ChatMessage("m", "text", MessageRole.USER, "独立文字",
                createdAt = 3, updatedAt = 3), ChatMessage.serializer())
            fun workspace(data: Path, state: String) = LanSyncWorkspace(data, Files.createDirectories(root.resolve(state)),
                DesktopCharacterResourceStore(data)::resolveOwnedReference,
                resourceReference = { _, path -> desktopLanSyncResourceReference(data, path) }, validateResource = ::validateDesktopLanSyncResource)
            workspace(source, "source").capture("phone").use { captured ->
                workspace(target, "target").apply(LanSyncPlan(UUID.randomUUID().toString(), captured.snapshot, emptyMap()),
                    emptyMap(), "pc", captured.blobs)
                val storage = JsonFileStorage(target)
                assertEquals("独立文字", storage.loadEntity("chat_messages", "text_m", ChatMessage.serializer())?.content)
                val resource = DesktopCharacterResourceStore(target)
                val reference = requireNotNull(storage.loadEntity("chat_sessions", "photo", ChatSession.serializer())?.chatBackground)
                val installed = resource.resolveOwnedReference(reference)
                assertEquals(bytes, Files.size(installed))
                assertEquals(expectedHash, LanSyncResourceStore.hashFile(installed, bytes))
                val preview = ImageIO.read(resource.readDisplayImageBytes(reference).inputStream())
                assertTrue(preview.width in 1..2048 && preview.height in 1..2048)
                workspace(target, "target").capture("pc").use { assertEquals(captured.snapshot.hashes(), it.snapshot.hashes()) }
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun heifWithoutDesktopCodecKeepsExactBytesAndLeavesTextUsable(): Unit = runBlocking {
        val fixture = System.getenv("CCB_R02_HEIF_FIXTURE")
        org.junit.Assume.assumeTrue("requires a generated positive HEIF fixture", fixture != null)
        val root = Files.createTempDirectory("lan-heif-").toRealPath()
        try {
            val target = Files.createDirectory(root.resolve("desktop"))
            val source = Files.createDirectory(root.resolve("phone"))
            val original = Files.createDirectory(source.resolve("images")).resolve("legacy.jpg")
            Files.copy(Path.of(requireNotNull(fixture)), original)
            val dimensions = requireNotNull(LanSyncHeifStill.inspect(original))
            assertEquals(64, dimensions.width); assertTrue(dimensions.height in 48..64) // Coded surface may include HEIF clean-aperture padding.
            val sha = LanSyncResourceStore.hashFile(original, Files.size(original))
            val storage = JsonFileStorage(source)
            storage.saveEntity("chat_sessions", "session", ChatSession("session", "card", "合成 HEIF", chatBackground = "images/legacy.jpg",
                createdAt = 1, updatedAt = 1), ChatSession.serializer())
            val message = ChatMessage("m", "session", MessageRole.USER, "同会话文字", createdAt = 2, updatedAt = 2)
            storage.saveEntity("chat_messages", "session_m", message, ChatMessage.serializer())
            fun workspace(data: Path, state: String) = LanSyncWorkspace(data, Files.createDirectories(root.resolve(state)),
                DesktopCharacterResourceStore(data)::resolveOwnedReference,
                resourceReference = { _, path -> desktopLanSyncResourceReference(data, path) }, validateResource = ::validateDesktopLanSyncResource)
            workspace(source, "source").capture("phone").use { captured ->
                workspace(target, "target").apply(LanSyncPlan(UUID.randomUUID().toString(), captured.snapshot, emptyMap()),
                    emptyMap(), "pc", captured.blobs)
                val destination = JsonFileStorage(target)
                assertEquals(message.content, destination.loadEntity("chat_messages", "session_m", ChatMessage.serializer())?.content)
                val ref = requireNotNull(destination.loadEntity("chat_sessions", "session", ChatSession.serializer())?.chatBackground)
                val resources = DesktopCharacterResourceStore(target)
                val retained = resources.resolveOwnedReference(ref)
                assertEquals(sha, LanSyncResourceStore.hashFile(retained, Files.size(retained)))
                assertEquals("SYNC_IMAGE_HEIF_PREVIEW_UNAVAILABLE", assertFails { resources.readDisplayImageBytes(ref) }.message)
                workspace(target, "target").capture("pc").use { assertEquals(captured.snapshot.hashes(), it.snapshot.hashes()) }
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun phoneBmpSessionBackgroundRetainsOriginalBytesAndPreviewsAfterColdApply(): Unit = runBlocking {
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
                ChatSession("session", "card", "合成会话", chatBackground = "images/original.jpg", createdAt = 1, updatedAt = 1),
                ChatSession.serializer())
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
