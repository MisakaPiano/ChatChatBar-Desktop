package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.CharacterCardPngPackageCodec
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopPhase7ImagesTest {
    @Test fun `text model linked vision uses shared envelope persists description and keeps raw image out of text request`() = runBlocking { fixture { c, root ->
        val session = session(c)
        MockWebServer().use { server ->
            val vision = ModelConfig(id = "vision", displayName = "Vision", modelName = "vision", apiKey = "fake-vision-key",
                baseUrl = server.url("/v1").toString().trimEnd('/'), isMultimodal = true, createdAt = 1)
            val text = vision.copy(id = "text", modelName = "text", isMultimodal = false, visionModelId = "vision")
            c.modelRepository.saveModel(vision); c.modelRepository.saveModel(text)
            c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = text.id, allowCleartextModelApi = true))
            for (reply in listOf("inline scene description", "reply")) server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            c.createRealChatRuntime().sendText(session, "look", attachments = listOf(DesktopPendingImage(bytes = png()), DesktopPendingImage(bytes = png(Color.BLUE))))
            val visionRequest = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            assertEquals("vision", visionRequest.getValue("model").jsonPrimitive.content)
            val envelope = visionRequest.getValue("messages").jsonArray
            assertEquals(listOf("system", "assistant", "user", "assistant", "user", "assistant", "assistant", "user"),
                envelope.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertEquals(1, Regex("data:image/jpeg;base64,").findAll(envelope.toString()).count())
            val textRequest = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            assertFalse(textRequest.contains("data:image/")); assertTrue(textRequest.contains("inline scene description"))
            val user = c.chatRepository.getMessages(session).single { it.role == MessageRole.USER }
            assertEquals(com.example.chatbar.domain.prompt.AuxiliaryPromptAuthority.appendUserImageDescriptions("look", listOf("inline scene description")), user.content)
            assertEquals(2, user.images.size)
            user.images.forEach { assertTrue(Files.exists(root.resolve(it))) }
            val reopened = com.example.chatbar.data.repository.ChatRepository(JsonFileStorage(root))
            assertEquals(user.content, reopened.getMessage(user.id, session)!!.content)
        }
    } }

    @Test fun `missing linked vision visibly follows baseline no-image request and retains attachment`() = runBlocking { fixture { c, _ ->
        val session = session(c)
        MockWebServer().use { server ->
            val text = ModelConfig(id = "text", modelName = "text", displayName = "Text", apiKey = "fake-key",
                baseUrl = server.url("/v1").toString().trimEnd('/'), isMultimodal = false, createdAt = 1)
            c.modelRepository.saveModel(text)
            c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = text.id, allowCleartextModelApi = true))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val notices = mutableListOf<String>()
            c.createRealChatRuntime().sendText(session, "look", observer = { it.status?.let(notices::add) },
                attachments = listOf(DesktopPendingImage(bytes = png())))
            assertTrue(notices.any { it.contains("无图") })
            assertFalse(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8().contains("data:image/"))
            assertEquals(1, c.chatRepository.getMessages(session).single { it.role == MessageRole.USER }.images.size)
        }
    } }

    @Test fun `crop geometry is invariant under DPI and preserves source`() {
        val source = BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB)
        source.createGraphics().let { g ->
            g.color = Color.RED; g.fillRect(0, 0, 200, 200)
            g.color = Color.BLUE; g.fillRect(200, 0, 200, 200); g.dispose()
        }
        val before = DesktopImageEditing.png(source)
        val transform = DesktopImageTransform(0.9f, 0.5f, 2f)
        assertEquals(desktopImageCrop(400, 200, 256, 256, transform), desktopImageCrop(400, 200, 1024, 1024, transform))
        assertEquals(Color.BLUE.rgb, DesktopImageEditing.crop(source, transform, 100, 100).getRGB(50, 50))
        assertContentEquals(before, DesktopImageEditing.png(source))
        assertEquals(DesktopImageTransform(), DesktopImageTransform(Float.NaN, Float.NaN, Float.NaN).normalized())
    }

    @Test fun `batch import failure removes only newly created files`() = runBlocking { fixture { c, root ->
        val session = session(c)
        val unrelated = Files.createDirectories(root.resolve("images")).resolve("unrelated.png")
        Files.write(unrelated, png())
        assertFailsWith<IllegalArgumentException> {
            c.chatImages.persistUser(session, "image", listOf(DesktopPendingImage(bytes = png()), DesktopPendingImage(bytes = byteArrayOf(1))))
        }
        assertTrue(c.chatRepository.getMessages(session).none { it.role == MessageRole.USER })
        assertEquals(listOf("unrelated.png"), Files.list(root.resolve("images")).use { it.map { p -> p.fileName.toString() }.toList() })
    } }

    @Test fun `failure after durable message write retains linked image and restart reads it`() = runBlocking { fixture { c, root ->
        val session = session(c)
        val images = DesktopChatImages(c.characterResourceStore, c.chatRepository,
            persistMessage = { c.chatRepository.addMessage(it); error("after durable write") })
        assertFailsWith<IllegalStateException> { images.persistUser(session, "image", listOf(DesktopPendingImage(bytes = png()))) }
        val reopened = com.example.chatbar.data.repository.ChatRepository(JsonFileStorage(root))
        val message = reopened.getMessages(session).single { it.role == MessageRole.USER }
        assertEquals(1, message.images.size)
        assertTrue(Files.exists(root.resolve(message.images.single())))
        assertFalse(Path.of(message.images.single()).isAbsolute)
    } }

    @Test fun `background replacement cleans obsolete copy but retains every shared reference`() = runBlocking { fixture { c, root ->
        val session = session(c)
        assertNull(c.chatImages.replaceBackground(session, png()))
        val first = c.chatRepository.getSession(session)!!.chatBackground!!
        Files.writeString(root.resolve("entities/retained-draft.json"), """{"image":"$first"}""")
        assertNull(c.chatImages.replaceBackground(session, png(Color.BLUE)))
        val second = c.chatRepository.getSession(session)!!.chatBackground!!
        assertNotEquals(first, second)
        assertTrue(Files.exists(root.resolve(first)))
        assertNull(c.chatImages.replaceBackground(session, null))
        assertFalse(Files.exists(root.resolve(second)))
        assertTrue(Files.exists(root.resolve(first)))
        assertNull(c.chatRepository.getSession(session)!!.chatBackground)
    } }

    @Test fun `corrupt authority blocks cleanup after successful background publication`() = runBlocking { fixture { c, root ->
        val session = session(c)
        c.chatImages.replaceBackground(session, png())
        val first = c.chatRepository.getSession(session)!!.chatBackground!!
        Files.writeString(root.resolve("entities/unreadable-owner.json"), "{bad")
        assertNotNull(c.chatImages.replaceBackground(session, png(Color.BLUE)))
        assertNotEquals(first, c.chatRepository.getSession(session)!!.chatBackground)
        assertTrue(Files.exists(root.resolve(first)))
    } }

    @Test fun `multimodal send persists one user with JPEG payload and no duplicate current image`() = runBlocking { fixture { c, root ->
        val session = session(c)
        MockWebServer().use { server ->
            val model = ModelConfig(id = "model", displayName = "Inline", baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKey = "inline-test-secret", modelName = "inline", isMultimodal = true, createdAt = 1)
            c.modelRepository.saveModel(model)
            c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = model.id, allowCleartextModelApi = true))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            c.createRealChatRuntime().sendText(session, "", attachments = listOf(DesktopPendingImage(bytes = png()), DesktopPendingImage(bytes = png(Color.BLUE))))
            val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            assertEquals(1, Regex("data:image/jpeg;base64,").findAll(body).count())
            val users = c.chatRepository.getMessages(session).filter { it.role == MessageRole.USER }
            assertEquals(1, users.size)
            assertEquals(2, users.single().images.size)
            users.single().images.forEach { assertTrue(Files.exists(root.resolve(it))) }
        }
    } }

    @Test fun `export replacement changes visible cover without changing package or entity`() = runBlocking { fixture { c, root ->
        val background = c.characterResourceStore.materializeImage(
            com.example.chatbar.domain.card.PackagedImage("original.png", java.util.Base64.getEncoder().encodeToString(png())), 1, "original")
        val card = CharacterCard.create("Cover").copy(chatBackground = background)
        c.characterRepository.save(card)
        val destination = root.resolve("export.png")
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType): Path? = null
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String) = destination
        }
        val controller = c.createTypedTransferController(picker)
        controller.beginCoverExport(card.id)
        val draft = assertNotNull(controller.state.value.coverExport)
        controller.finishCoverExport(draft, CharacterCardPngExportOptions(sizePx = 1024), png(Color.BLUE))
        assertNull(controller.state.value.error)
        val decoded = assertNotNull(c.characterTransfers.decodePng(Files.readAllBytes(destination)))
        assertEquals(draft.packageData, decoded)
        assertEquals(card, c.characterRepository.getById(card.id))
        val pixels = DesktopImageEditing.decode(Files.readAllBytes(destination))
        assertEquals(Color.BLUE.rgb, pixels.getRGB(100, 100))
    } }

    @Test fun `attachment preserves embedded metadata and display sampling bounds decode`() = runBlocking { fixture { c, _ ->
        val bytes = com.example.chatbar.domain.card.PngTextChunks.insertTextChunk(png(), "Comment", "inline metadata")
        val saved = c.chatImages.persistUser(session(c), "metadata", listOf(DesktopPendingImage(bytes = bytes)))
        assertContentEquals(bytes, c.characterResourceStore.readBytes(saved.images.single()))
        val sampled = DesktopImageEditing.decode(bytes, longestSide = 10)
        assertTrue(sampled.width <= 10 && sampled.height <= 10)
    } }

    @Test fun `JPEG EXIF rotation is applied before crop without mutating encoded source`() {
        val source = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        val jpeg = java.io.ByteArrayOutputStream().use { javax.imageio.ImageIO.write(source, "jpeg", it); it.toByteArray() }
        // Little-endian TIFF with one orientation=6 SHORT entry.
        val exif = byteArrayOf(69,120,105,102,0,0,73,73,42,0,8,0,0,0,1,0,
            18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0)
        val bytes = jpeg.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xe1.toByte(),0,(exif.size + 2).toByte()) + exif + jpeg.drop(2).toByteArray()
        val decoded = DesktopImageEditing.decode(bytes)
        assertEquals(20, decoded.width)
        assertEquals(40, decoded.height)
    }

    @Test fun `owned image name collision never deletes the preexisting resource`() = runBlocking { fixture { c, root ->
        val packaged = com.example.chatbar.domain.card.PackagedImage("image.png", java.util.Base64.getEncoder().encodeToString(png()))
        val original = c.characterResourceStore.materializeImage(packaged, 1, "collision")
        assertFailsWith<java.nio.file.FileAlreadyExistsException> {
            c.characterResourceStore.materializeImage(packaged, 1, "collision")
        }
        assertContentEquals(png(), Files.readAllBytes(root.resolve(original)))
    } }

    private suspend fun session(c: DesktopAppContainer): String {
        val card = CharacterCard.create("Inline images")
        c.characterRepository.save(card)
        return c.characterSessionService.createSessionForCharacter(card.id)
    }

    private suspend fun fixture(block: suspend (DesktopAppContainer, Path) -> Unit) {
        val root = Files.createTempDirectory("desktop-p7-images-")
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        try { block(c, root) } finally { c.close(); root.toFile().deleteRecursively() }
    }

    private fun png(color: Color = Color.RED): ByteArray {
        val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { g -> g.color = color; g.fillRect(0, 0, 40, 20); g.dispose() }
        return DesktopImageEditing.png(image)
    }
}
