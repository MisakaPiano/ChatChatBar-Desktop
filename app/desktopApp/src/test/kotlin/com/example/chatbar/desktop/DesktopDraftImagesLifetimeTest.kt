package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopDraftImagesLifetimeTest {
    private fun container(parent: Path) = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() })
    private suspend fun prepare(c: DesktopAppContainer, server: MockWebServer? = null): String {
        val card = CharacterCard.create("Synthetic image draft")
        c.characterRepository.save(card)
        if (server != null) {
            val vision = ModelConfig("vision", "Synthetic vision", server.url("/v1").toString(), "fake-test-key", "vision", createdAt = 1, isMultimodal = true)
            c.modelRepository.saveModel(vision)
            c.modelRepository.saveModel(vision.copy(id = "text", modelName = "text", isMultimodal = false, visionModelId = "vision"))
            c.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "text", allowCleartextModelApi = true, ragInjectionMode = "OFF"))
        }
        return c.characterSessionService.createSessionForCharacter(card.id)
    }
    private fun png(color: Int): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        return try { image.setRGB(0, 0, color); ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() } }
        finally { image.flush() }
    }
    private suspend fun terminal(c: DesktopAppContainer, id: String) = withTimeout(15_000) {
        c.taskRuntime.tasks.first { rows -> rows.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } }
    }
    private fun reply(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

    @Test fun `image only draft order removal GC and cold reopen retain original encoded bytes`() = runBlocking {
        val parent = Files.createTempDirectory("synthetic-draft-images-")
        var c = container(parent)
        try {
            val id = prepare(c); val controller = c.primaryChatController; controller.refresh()
            val a = png(0xff336699.toInt()); val b = png(0xff993366.toInt())
            controller.receiveImages(DesktopImageIngress(raster = a)); controller.receiveImages(DesktopImageIngress(raster = b))
            val first = controller.state.value.pendingImages.first().id
            controller.reorderPendingImage(first, 1); controller.persistComposer()
            var draft = assertNotNull(c.chatRepository.getSessionDraftSnapshot(id))
            assertEquals("", draft.content); assertEquals(2, draft.images.size)
            assertContentEquals(b, c.chatImages.read(draft.images[0])); assertContentEquals(a, c.chatImages.read(draft.images[1]))
            DesktopOwnedImageCleanup(c.appDataRoot, c.characterResourceStore, c.dataOperationCoordinator).deleteUnreferenced(draft.images)
            draft.images.forEach { assertTrue(Files.exists(c.appDataRoot.resolve(it))) }
            c.close(); c = container(parent); c.primaryChatController.refresh()
            assertEquals(id, c.primaryChatController.state.value.selectedSession?.id)
            assertEquals("", c.primaryChatController.state.value.composerDraft)
            assertContentEquals(b, c.primaryChatController.state.value.pendingImages[0].bytes)
            c.primaryChatController.removePendingImage(c.primaryChatController.state.value.pendingImages[0].id)
            c.primaryChatController.persistComposer()
            draft = assertNotNull(c.chatRepository.getSessionDraftSnapshot(id))
            assertEquals(1, draft.images.size); assertContentEquals(a, c.chatImages.read(draft.images.single()))
        } finally { c.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `Stop during image understanding keeps image draft through actual container restart`() = runBlocking {
        val parent = Files.createTempDirectory("synthetic-draft-stop-")
        var c = container(parent)
        try { MockWebServer().use { server ->
            val id = prepare(c, server); val controller = c.primaryChatController; controller.refresh()
            val image = png(0xff669933.toInt())
            controller.receiveImages(DesktopImageIngress(raster = image)); controller.editComposer("not yet USER")
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = assertNotNull(controller.send())
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(0, c.chatRepository.getMessages(id).count { it.role == MessageRole.USER })
            assertTrue(controller.stop(task)); terminal(c, task); controller.persistComposer()
            c.close(); c = container(parent); c.primaryChatController.refresh()
            assertEquals("not yet USER", c.primaryChatController.state.value.composerDraft)
            assertContentEquals(image, c.primaryChatController.state.value.pendingImages.single().bytes)
        } } finally { c.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `durable old USER cannot consume newer text and attachments`() = runBlocking {
        val parent = Files.createTempDirectory("synthetic-draft-newer-")
        val c = container(parent)
        try { MockWebServer().use { server ->
            val id = prepare(c, server); val controller = c.primaryChatController; controller.refresh()
            val original = png(0xff336699.toInt()); val newer = png(0xff996633.toInt())
            controller.receiveImages(DesktopImageIngress(raster = original)); controller.editComposer("old send")
            server.enqueue(reply("description").setBodyDelay(1, TimeUnit.SECONDS)); server.enqueue(reply("answer"))
            val task = assertNotNull(controller.send()); assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            controller.removePendingImage(controller.state.value.pendingImages.single().id)
            controller.receiveImages(DesktopImageIngress(raster = newer)); controller.editComposer("new unsent")
            terminal(c, task); controller.persistComposer()
            assertEquals(1, c.chatRepository.getMessages(id).count { it.role == MessageRole.USER })
            val draft = assertNotNull(c.chatRepository.getSessionDraftSnapshot(id))
            assertEquals("new unsent", draft.content); assertContentEquals(newer, c.chatImages.read(draft.images.single()))
            assertEquals("new unsent", controller.state.value.composerDraft)
            assertContentEquals(newer, controller.state.value.pendingImages.single().bytes)
        } } finally { c.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `failed image draft write retries the same admitted attachment snapshot`() = runBlocking {
        val parent = Files.createTempDirectory("synthetic-draft-io-"); val c = container(parent)
        var fail = true
        val controller = DesktopPrimaryChatController(
            characters = c.characterRepository, chats = c.chatRepository, settings = c.settingsRepository,
            models = c.effectiveModelResolver, formats = c.formatCardRepository, worldBooks = c.worldBookRepository,
            sessionService = c.characterSessionService, characterResources = c.characterResourceStore,
            taskRuntime = c.taskRuntime, imageStore = c.chatImages,
            draftWriter = { _, _ -> if (fail) error("synthetic private error") })
        try {
            val id = prepare(c); controller.refresh()
            val image = png(0xff123456.toInt())
            controller.receiveImages(DesktopImageIngress(raster = image))
            assertNotNull(controller.state.value.error)
            assertContentEquals(image, controller.state.value.pendingImages.single().bytes)
            assertNull(c.chatRepository.getSessionDraftSnapshot(id))
            fail = false
            controller.persistComposer()
            val draft = assertNotNull(c.chatRepository.getSessionDraftSnapshot(id))
            assertContentEquals(image, c.chatImages.read(draft.images.single()))
            assertEquals(1, controller.state.value.pendingImages.size)
        } finally { fail = false; controller.persistComposer(); controller.closeDraftPersistence(); c.close(); parent.toFile().deleteRecursively() }
    }
}
