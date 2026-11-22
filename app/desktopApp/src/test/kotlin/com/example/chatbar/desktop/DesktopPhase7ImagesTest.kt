package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.CharacterCardPngPackageCodec
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.CharacterCardExportCompatibility
import com.example.chatbar.domain.card.PngTextChunks
import com.example.chatbar.domain.card.forExportCompatibility
import com.example.chatbar.domain.lanSync.LanSyncHeifStill
import com.example.chatbar.domain.lanSync.LanSyncResourceStore
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun highBaselineJpegUsesBoundedWireBytesForContinueRegenerateAndDirectHistory(): Unit = runBlocking { fixture { c, root ->
        val session = session(c)
        val original = Files.createDirectories(root.resolve("images")).resolve("camera.jpg")
        val camera = BufferedImage(8000, 6000, BufferedImage.TYPE_BYTE_GRAY)
        try { check(ImageIO.write(camera, "jpeg", original.toFile())) } finally { camera.flush() }
        Files.write(root.resolve("images/other.png"), png(Color.BLUE))
        val length = Files.size(original)
        val hash = LanSyncResourceStore.hashFile(original, length)
        val refs = listOf("images/camera.jpg", "images/other.png")
        val source = c.chatRepository.addMessage(ChatMessage.create(session, MessageRole.USER, "48MP source").copy(images = refs))

        MockWebServer().use { server ->
            val vision = wireModel(server, images = true)
            c.modelRepository.saveModel(vision)
            c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = vision.id,
                defaultContextWindowSize = 8, allowCleartextModelApi = true))
            val runtime = c.createRealChatRuntime()

            server.enqueue(wireReply("continued"))
            val continued = runtime.sendText(session, "")
            assertNull(continued.failureMessage)
            assertWireJpeg(server.wireMessages(), "48MP source", 1600, 1200)
            assertEquals(listOf(source.id), c.chatRepository.getMessages(session).filter { it.role == MessageRole.USER }.map { it.id })

            server.enqueue(wireReply("regenerated"))
            val regenerated = runtime.regenerate(session, continued.assistant.id)
            assertNull(regenerated.failureMessage)
            assertWireJpeg(server.wireMessages(), "48MP source", 1600, 1200)
            assertEquals(continued.assistant.id, regenerated.assistant.id)
            assertEquals(listOf("continued", "regenerated"), regenerated.assistant.alternatives)

            server.enqueue(wireReply("follow-up"))
            assertNull(runtime.sendText(session, "Next text turn").failureMessage)
            val requestWithHistory = server.wireMessages()
            assertWireJpeg(requestWithHistory, "48MP source", 1600, 1200)
            assertTrue(requestWithHistory.any { it.jsonObject["content"] == JsonPrimitive("Next text turn") })
            assertEquals(3, server.requestCount)
        }
        val exported = root.resolve("compatible.png")
        c.characterResourceStore.exportImagePng(refs.first(), exported)
        val png = assertNotNull(ImageIO.read(exported.toFile()))
        try { assertEquals(2000 to 1500, png.width to png.height) } finally { png.flush() }
        assertFailsWith<IllegalArgumentException> { c.characterResourceStore.exportImagePng(refs.first(), original) }
        assertEquals(length, Files.size(original))
        assertEquals(hash, LanSyncResourceStore.hashFile(original, length))
        assertEquals(refs, c.chatRepository.getMessage(source.id, session)?.images)
    } }

    @Test fun unavailableHeifRequestPreservesOriginalAndAllowsExplicitTextAndCompatibleImage(): Unit = runBlocking { fixture { c, root ->
        val heifFixture = System.getenv("CCB_R02_HEIF_FIXTURE")
        org.junit.Assume.assumeTrue("requires a genuine synthetic HEIF still", heifFixture != null)
        val session = session(c)
        val reference = "images/legacy.jpg"
        val original = Files.createDirectories(root.resolve("images")).resolve("legacy.jpg")
        Files.copy(Path.of(requireNotNull(heifFixture)), original)
        assertNotNull(LanSyncHeifStill.inspect(original))
        val length = Files.size(original)
        val hash = LanSyncResourceStore.hashFile(original, length)
        val source = c.chatRepository.addMessage(ChatMessage.create(session, MessageRole.USER, "HEIF source").copy(images = listOf(reference)))

        MockWebServer().use { server ->
            val vision = wireModel(server, images = true)
            val textOnly = wireModel(server, images = false)
            c.modelRepository.saveModel(vision)
            c.modelRepository.saveModel(textOnly)
            c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = vision.id,
                defaultContextWindowSize = 8, allowCleartextModelApi = true))
            assertFailsWith<DesktopHeifPreviewUnavailable> { c.characterResourceStore.readDisplayImageBytes(reference) }
            val failed = c.taskRuntime.awaitTerminal(c.taskRuntime.launchChat(session, ""))
            assertEquals(DesktopTaskStatus.FAILED, failed.status)
            assertEquals(source.id, failed.persistedUserMessageId)
            assertTrue(failed.message.contains("HEIF 原图已保留"))
            assertTrue(failed.message.contains("纯文字模型"))
            assertEquals(0, server.requestCount)
            assertEquals(listOf(reference), c.chatRepository.getMessage(source.id, session)?.images)

            val beforeTextChoice = requireNotNull(c.chatRepository.getSession(session))
            c.chatRepository.saveSessionSettingsDraft(beforeTextChoice, beforeTextChoice.copy(modelId = textOnly.id))
            val runtime = c.createRealChatRuntime()
            server.enqueue(wireReply("text still works"))
            val continued = runtime.sendText(session, "")
            assertNull(continued.failureMessage)
            val textRequest = server.wireMessages()
            assertTrue(textRequest.any { it.jsonObject["content"] == JsonPrimitive("HEIF source") })
            assertFalse(textRequest.toString().contains("data:image/"))
            assertEquals(listOf(source.id), c.chatRepository.getMessages(session).filter { it.role == MessageRole.USER }.map { it.id })
            assertEquals(listOf(reference), c.chatRepository.getMessage(source.id, session)?.images)

            val beforeVisionChoice = requireNotNull(c.chatRepository.getSession(session))
            c.chatRepository.saveSessionSettingsDraft(beforeVisionChoice, beforeVisionChoice.copy(modelId = vision.id))
            val currentSource = requireNotNull(c.chatRepository.getMessage(source.id, session))
            c.chatImages.editMessage(currentSource, currentSource.content, currentSource.images,
                listOf(DesktopPendingImage(bytes = png())), preferAddedImages = true)
            val retained = requireNotNull(c.chatRepository.getMessage(source.id, session)).images
            assertEquals(2, retained.size)
            assertEquals(reference, retained.last())
            server.enqueue(wireReply("compatible image"))
            assertNull(runtime.regenerate(session, continued.assistant.id).failureMessage)
            assertWireJpeg(server.wireMessages(), "HEIF source", 40, 20)
            assertEquals(2, server.requestCount)
        }
        assertEquals(length, Files.size(original))
        assertEquals(hash, LanSyncResourceStore.hashFile(original, length))
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
        val output = Files.readAllBytes(destination)
        val raw = String(java.util.Base64.getDecoder().decode(assertNotNull(
            PngTextChunks.extractTextChunk(output, PngTextChunks.CHATBAR_CHARACTER_KEYWORD))), Charsets.UTF_8)
        val payload = c.transferJson.decodeFromString(CharacterCardPackage.serializer(), raw)
        assertEquals(draft.packageData.forExportCompatibility(CharacterCardExportCompatibility.OFFICIAL_V9), payload)
        val decoded = assertNotNull(c.characterTransfers.decodePng(output))
        val importedCoverId = assertNotNull(decoded.card.coverImageResourceId)
        assertEquals(payload, decoded.copy(schemaVersion = 9, card = decoded.card.copy(coverImageResourceId = null),
            images = decoded.images - importedCoverId))
        assertContentEquals(png(Color.BLUE), java.util.Base64.getDecoder().decode(decoded.images.getValue(importedCoverId).data))
        assertEquals(card, c.characterRepository.getById(card.id))
        val pixels = DesktopImageEditing.decode(Files.readAllBytes(destination))
        assertEquals(40, pixels.width); assertEquals(20, pixels.height)
        assertEquals(Color.BLUE.rgb, pixels.getRGB(pixels.width / 2, pixels.height / 2))
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

    private fun wireModel(server: MockWebServer, images: Boolean): ModelConfig {
        val id = if (images) "vision" else "text"
        return ModelConfig(id = id, displayName = id, baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "synthetic-test-key", modelName = id, isMultimodal = images, createdAt = 1)
    }

    private fun wireReply(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

    private fun MockWebServer.wireMessages(): JsonArray {
        val request = assertNotNull(takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/chat/completions", request.path)
        return Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("messages").jsonArray
    }

    private fun assertWireJpeg(messages: JsonArray, text: String, width: Int, height: Int) {
        val multimodal = messages.map { it.jsonObject }.single { it["content"] is JsonArray }
        assertEquals(JsonPrimitive("user"), multimodal["role"])
        val parts = multimodal.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive(text), parts.single { it["type"] == JsonPrimitive("text") }["text"])
        val url = parts.single { it["type"] == JsonPrimitive("image_url") }
            .getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
        val prefix = "data:image/jpeg;base64,"
        assertTrue(url.startsWith(prefix))
        val encoded = Base64.getDecoder().decode(url.removePrefix(prefix))
        assertEquals(listOf(0xff.toByte(), 0xd8.toByte()), encoded.take(2))
        val raster = assertNotNull(ImageIO.read(encoded.inputStream()))
        try { assertEquals(width to height, raster.width to raster.height) } finally { raster.flush() }
    }

    private suspend fun DesktopTaskRuntime.awaitTerminal(id: String): DesktopTaskEntry = withTimeout(10_000) {
        tasks.first { tasks -> tasks.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } }.single { it.taskId == id }
    }

    private suspend fun fixture(block: suspend (DesktopAppContainer, Path) -> Unit) {
        val root = Files.createTempDirectory("desktop-p7-images-").toRealPath()
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
