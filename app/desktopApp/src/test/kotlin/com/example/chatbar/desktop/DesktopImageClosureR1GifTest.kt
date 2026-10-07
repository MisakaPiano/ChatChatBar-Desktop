package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.Color
import java.awt.datatransfer.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopImageClosureR1GifTest {
    private class Picker(val path: Path) : DesktopFilePicker {
        val types = mutableListOf<DesktopFileType>()
        override fun pickOpenFile(type: DesktopFileType): Path { types += type; return path }
        override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
    }
    private suspend fun fixture(block: suspend (DesktopAppContainer, Path, Picker, String) -> Unit) {
        val root = Files.createTempDirectory("ccb-r1-gif-")
        val picker = Picker(root.resolve("source.gif")); Files.write(picker.path, gif())
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolve("bootstrap.json")), filePicker = picker, secretStoreFactory = { InMemoryDesktopSecretStore() })
        try {
            val card = CharacterCard.create("Inline GIF")
            c.characterRepository.save(card)
            val session = c.characterSessionService.createSessionForCharacter(card.id)
            block(c, c.appDataRoot, picker, session)
        } finally { c.close(); root.toFile().deleteRecursively() }
    }
    private fun gif(frames: Int = 2): ByteArray = ByteArrayOutputStream().use { out ->
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try { MemoryCacheImageOutputStream(out).use { stream ->
            writer.output = stream; writer.prepareWriteSequence(null)
            repeat(frames) { i ->
                val image = BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB)
                image.createGraphics().let { g -> g.color = if (i == 0) Color.RED else Color.BLUE; g.fillRect(0, 0, 30, 20); g.dispose() }
                writer.writeToSequence(IIOImage(image, null, null), null)
            }
            writer.endWriteSequence()
        } } finally { writer.dispose() }
        out.toByteArray()
    }
    private fun assertAnimation(bytes: ByteArray) {
        ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            try { reader.input = input; assertEquals(2, reader.getNumImages(true)) }
            finally { reader.dispose() }
        }
    }

    @Test fun `GIF picker and drop send through pending and real chat persistence with original animated bytes`() = runBlocking { fixture { c, root, picker, session ->
        MockWebServer().use { server ->
            c.modelRepository.saveModel(ModelConfig("fixture", "Fixture", server.url("/v1").toString().trimEnd('/'),
                "local-fake-key", "fixture", isMultimodal = true, createdAt = 1))
            c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "fixture", allowCleartextModelApi = true))
            val controller = c.primaryChatController
            controller.refresh(); controller.selectSession(session)
            for (drop in listOf(false, true)) {
                if (drop) {
                    val transfer = object : Transferable {
                        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
                        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
                        override fun getTransferData(flavor: DataFlavor) = listOf(picker.path.toFile())
                    }
                    controller.receiveImages(assertNotNull(desktopImageTransfer(transfer, paste = false)))
                } else controller.pickImage()
                val original = Files.readAllBytes(picker.path)
                assertContentEquals(original, controller.state.value.pendingImages.single().bytes)
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"local reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                assertNotNull(controller.send())
                withTimeout(10_000) { while (c.taskRuntime.tasks.value.any { it.status == DesktopTaskStatus.RUNNING }) delay(10) }
                val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
                assertTrue(request.contains("data:image/jpeg;base64,")) // Request adaptation remains separate.
                val reopened = ChatRepository(JsonFileStorage(root))
                val messages = reopened.getMessages(session).filter { it.role == MessageRole.USER }
                assertEquals(if (drop) 2 else 1, messages.size)
                messages.forEach { message ->
                    val reference = message.images.single(); assertTrue(reference.endsWith(".gif"))
                    val saved = Files.readAllBytes(root.resolve(reference))
                    assertContentEquals(original, saved); assertAnimation(saved)
                }
                assertTrue(controller.state.value.pendingImages.isEmpty())
            }
            assertTrue(picker.types.single().extensions.contains("gif"))
        }
    } }

    @Test fun `edit picker adds GIF and exact candidate deletion preserves external original`() = runBlocking { fixture { c, root, picker, session ->
        val message = c.chatRepository.addMessage(ChatMessage.create(session, MessageRole.USER, "edit fixture"))
        val controller = c.primaryChatController
        controller.refresh(); controller.selectSession(session)
        val image = assertNotNull(controller.pickMessageEditImage())
        assertTrue(picker.types.single().extensions.contains("gif"))
        assertTrue(controller.editMessage(message.id, message.content, additions = listOf(image), expected = message))
        val saved = assertNotNull(ChatRepository(JsonFileStorage(root)).getMessage(message.id, session))
        val reference = saved.images.single(); assertTrue(reference.endsWith(".gif"))
        assertContentEquals(Files.readAllBytes(picker.path), Files.readAllBytes(root.resolve(reference)))
        assertTrue(controller.deleteMessageImage(saved, reference))
        assertFalse(Files.exists(root.resolve(reference))); assertTrue(Files.exists(picker.path))
        assertTrue(ChatRepository(JsonFileStorage(root)).getMessage(message.id, session)!!.images.isEmpty())
    } }

    @Test fun `both GIF signatures persist unchanged`() = runBlocking { fixture { c, root, _, session ->
        for (signature in listOf("GIF87a", "GIF89a")) {
            val bytes = gif(1).also { signature.toByteArray(Charsets.US_ASCII).copyInto(it) }
            val saved = c.chatImages.persistUser(session, signature, listOf(c.chatImages.prepareBytes(bytes)))
            assertTrue(saved.images.single().endsWith(".gif"))
            assertContentEquals(bytes, Files.readAllBytes(root.resolve(saved.images.single())))
        }
    } }

    @Test fun `PNG JPEG WebP and ordinary APNG attachment formats retain their encoded bytes`() = runBlocking { fixture { c, root, _, session ->
        val raster = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
        val png = DesktopImageEditing.png(raster)
        val jpeg = ByteArrayOutputStream().use { ImageIO.write(raster, "jpeg", it); it.toByteArray() }
        val webp = org.jetbrains.skia.Image.makeFromEncoded(png).use { image ->
            requireNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP, 100)).use { it.bytes }
        }
        val disguised = root.resolve("disguised.png"); Files.write(disguised, DesktopImageTools.disguise(png))
        val ordinary = com.example.chatbar.domain.image.ImageMetadataStripper.stripContainerMetadataToCopy(disguised.toFile(), root.resolve("ordinary").toFile())
        for ((bytes, extension) in listOf(png to "png", jpeg to "jpg", webp to "webp", ordinary.readBytes() to "png")) {
            val pending = c.chatImages.prepareBytes(bytes)
            assertFalse(pending.restoredDisguise)
            val message = c.chatImages.persistUser(session, extension, listOf(pending))
            assertTrue(message.images.single().endsWith(".$extension"))
            assertContentEquals(bytes, Files.readAllBytes(root.resolve(message.images.single())))
        }
    } }

    @Test fun `failed GIF persistence removes only new candidate while committed failure retains original bytes`() = runBlocking { fixture { c, root, picker, session ->
        val unrelated = root.resolve("images/unrelated.gif"); Files.createDirectories(unrelated.parent); Files.write(unrelated, gif())
        val pending = c.chatImages.prepare(picker.path)
        val failBefore = DesktopChatImages(c.characterResourceStore, c.chatRepository, persistMessage = { error("before write") })
        assertFailsWith<IllegalStateException> { failBefore.persistUser(session, "fail", listOf(pending)) }
        assertEquals(listOf("unrelated.gif"), Files.list(unrelated.parent).use { it.map { p -> p.fileName.toString() }.toList() })
        assertFailsWith<IllegalArgumentException> { c.chatImages.persistUser(session, "bad batch", listOf(pending, DesktopPendingImage(bytes = byteArrayOf(1)))) }
        assertEquals(listOf("unrelated.gif"), Files.list(unrelated.parent).use { it.map { p -> p.fileName.toString() }.toList() })
        val failAfter = DesktopChatImages(c.characterResourceStore, c.chatRepository, persistMessage = { c.chatRepository.addMessage(it); error("after write") })
        assertFailsWith<IllegalStateException> { failAfter.persistUser(session, "durable", listOf(pending)) }
        val saved = ChatRepository(JsonFileStorage(root)).getMessages(session).single { it.role == MessageRole.USER }
        assertContentEquals(pending.bytes, Files.readAllBytes(root.resolve(saved.images.single())))
        assertTrue(Files.exists(unrelated))
    } }
}
