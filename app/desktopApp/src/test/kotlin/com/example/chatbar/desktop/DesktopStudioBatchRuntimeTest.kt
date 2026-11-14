package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.msgpack.core.MessagePack
import kotlin.test.*

class DesktopStudioBatchRuntimeTest {
    private val usage = NovelAiAccountUsage(1000000L, 3, true, 100.0, false)
    private fun plan(origin: ImageBatchOrigin) = ImageBatchRunPlan(4, 10,
        ImageBatchPacing(ImageBatchInterval.NONE), 100000, origin)

    @Test fun `Studio confirmation drives three production HTTP batches and saved history reopens without restarting`() = runBlocking {
        fixture { f ->
            val c = f.app
            val runtime = DesktopNovelAiGenerationRuntime(c.desktopSecretStore,
                DesktopNovelAiGenerationStore(c.jsonFileStorage, c.characterResourceStore, c.dataOperationCoordinator)::persist, f.client)
            val controller = DesktopNovelAiStudioController(c.jsonFileStorage, c.characterResourceStore, UnconfiguredDesktopFilePicker,
                c.dataOperationCoordinator, {}, runtime,
                DesktopNovelAiGuidance(f.root, c.characterResourceStore, c.desktopSecretStore, c.dataOperationCoordinator),
                c.taskRuntime, c.novelAiInfrastructure, c.settingsRepository, c.effectiveModelResolver, c.characterRepository,
                account = { usage }, credentialConfigured = { true }, credentialIdentity = { "synthetic-account" },
                freezeCredential = { DesktopCredentialCall(c.desktopSecretStore, DesktopCredentialKey.NovelAiToken, "synthetic-account", f.token) })
            controller.load()
            controller.edit { it.copy(basePrompt = "synthetic scene", followDefaultNovelAiImageModel = false,
                continuousModeEnabled = true, continuousTargetCount = 10,
                v45Settings = NovelAiGenerationSettings(count = 4, customWidth = 512, customHeight = 512)) }
            controller.generate()
            assertNotNull(controller.state.value.pendingBatchDraft)
            assertTrue(f.counts.isEmpty(), "Opening confirmation must not request images")
            assertTrue(controller.confirmBatch(plan(ImageBatchOrigin.STUDIO)))
            val task = assertNotNull(controller.state.value.generationTaskId)
            assertEquals(DesktopTaskStatus.COMPLETED, terminal(c.taskRuntime, task).status)
            assertEquals(listOf(4, 4, 2), f.counts.toList())
            assertEquals(10, controller.state.value.batchProgress?.saved)
            assertFalse(controller.manualBatchRunning)
            f.reopen()
            val repository = NovelAiStudioStateRepository(JsonFileStorage(f.root))
            repository.initializeHistory()
            val history = repository.history.first()
            assertEquals(3, history.size)
            assertEquals(10, history.sumOf { it.images.size })
            assertTrue(history.all { it.images.size == it.recipe.settings.count })
            assertTrue(history.flatMap { it.images }.all { f.app.characterResourceStore.readBytes(it.path).isNotEmpty() })
            assertEquals(listOf(4, 4, 2), f.counts.toList())
        }
    }

    @Test fun `chat batch commits append in request order with original source binding across reopen`() = runBlocking {
        fixture { f ->
            val c = f.app
            c.characterRepository.save(CharacterCard.create("synthetic").copy(id = "card"))
            c.chatRepository.createSession(ChatSession(id = "session", characterCardId = "card", title = "synthetic", createdAt = 1, updatedAt = 1))
            val original = c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "synthetic source"))
            val prompt = NovelAiPromptPlan("synthetic scene", emptyList())
            val size = NovelAiImageSize(512, 512)
            suspend fun eligible() = c.chatRepository.readMessageDurable(original.id, original.sessionId) ==
                com.example.chatbar.data.local.JsonFileStorage.EntityReadResult.Valid(original)
            val runtime = DesktopNovelAiGenerationRuntime(c.desktopSecretStore, persist = { bytes, recipe ->
                persistDesktopChatImages(c.chatRepository, c.characterResourceStore, c.dataOperationCoordinator,
                    original, prompt, size, bytes, recipe, ::eligible, expectedCharacterCardId = "card")
            }, client = f.client)
            val settings = NovelAiGenerationSettings(count = 4, customWidth = 512, customHeight = 512)
            val draft = NovelAiStudioDraft(basePrompt = "synthetic scene").withActiveSettings(settings)
            runDesktopManualImageBatch(plan(ImageBatchOrigin.CHAT_MANUAL), ImageBatchControl(), f.token,
                account = { usage }, estimate = { count, account -> NovelAiImageCostEstimator.estimate(settings.copy(count = count), account) },
                validateOwner = { check(eligible()) }, onProgress = {},
                runner = ImageBatchRunner(admission = c.taskRuntime.imageBatchAdmission)) { count, _ ->
                runtime.generate(draft.withActiveSettings(settings.copy(count = count)), promptPlan = prompt, safeBatchRequest = true).images.size
            }
            f.reopen()
            val messages = f.app.chatRepository.getMessages("session")
            assertEquals(original, messages.first())
            assertEquals(listOf(4, 4, 2), messages.drop(1).map { it.images.size })
            assertTrue(messages.drop(1).all { it.generatedFromMessageId == original.id })
            assertEquals(listOf(4, 4, 2), f.counts.toList())
            assertEquals((1..10).toList(), messages.drop(1).flatMap { it.images }.map {
                DesktopImageEditing.decode(f.app.characterResourceStore.readBytes(it)).getRGB(0, 0) and 0xff
            })
        }
    }

    @Test fun `save rechecks original anchor and session under repository writer after stale eligibility`() = runBlocking {
        for (change in listOf("source-edit", "source-delete", "session-delete", "card-rebind")) fixture { f ->
            val c = f.app
            c.characterRepository.save(CharacterCard.create("synthetic").copy(id = "card"))
            c.chatRepository.createSession(ChatSession(id = "session", characterCardId = "card", title = "synthetic", createdAt = 1, updatedAt = 1))
            val original = c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "original"))
            val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
            val prompt = NovelAiPromptPlan("synthetic scene", emptyList())
            val size = NovelAiImageSize(512, 512)
            val recipe = NovelAiGenerationRecipe()
            persistDesktopChatImages(c.chatRepository, c.characterResourceStore, c.dataOperationCoordinator,
                original, prompt, size, listOf(png), recipe, { true }, expectedCharacterCardId = "card")
            fun media() = Files.walk(f.root).use { stream -> stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".png") }.toList().toSet() }
            val before = media()
            assertTrue(before.isNotEmpty())
            val result = runCatching {
                persistDesktopChatImages(c.chatRepository, c.characterResourceStore, c.dataOperationCoordinator,
                    original, prompt, size, listOf(png), recipe, {
                        when (change) {
                            "source-edit" -> c.chatRepository.updateMessage(original.copy(content = "edited"))
                            "source-delete" -> c.chatRepository.deleteMessage(original.id, original.sessionId)
                            "session-delete" -> c.chatRepository.deleteSessionRecord(original.sessionId)
                            else -> c.chatRepository.getSession(original.sessionId)!!.let {
                                c.chatRepository.saveSessionSettingsDraft(it, it.copy(characterCardId = "changed-card"))
                            }
                        }
                        true // The outside eligibility result is now deliberately stale.
                    }, expectedCharacterCardId = "card")
            }
            assertTrue(result.isFailure, change)
            assertEquals(before, media(), "Only newly staged failed-batch media may be removed: $change")
            assertEquals(1, c.chatRepository.getMessages("session").count { it.generatedFromMessageId == original.id }, change)
            if (change == "session-delete") assertNull(c.chatRepository.getSession("session"))
            assertTrue(f.counts.isEmpty(), "Persistence refusal must not start an image request")
        }
    }

    private suspend fun terminal(tasks: DesktopTaskRuntime, id: String) = withTimeout(30000) {
        tasks.tasks.first { entries -> entries.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } }.single { it.taskId == id }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { f.app.desktopSecretStore.save(DesktopCredentialKey.NovelAiToken, f.token); block(f) }
        finally { f.app.close(); f.client.dispatcher.executorService.shutdown(); f.client.connectionPool.evictAll(); f.root.toFile().deleteRecursively() }
    }

    private class Fixture {
        val root: Path = Files.createTempDirectory("desktop-real-batch-")
        val token = "synthetic-" + UUID.randomUUID()
        private fun container() = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        var app = container()
        val counts = java.util.Collections.synchronizedList(mutableListOf<Int>())
        private var imageIndex = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); requireNotNull(chain.request().body).writeTo(buffer)
            val count = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("parameters").jsonObject.getValue("n_samples").jsonPrimitive.int
            counts += count
            val output = ByteArrayOutputStream()
            repeat(count) {
                val image = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
                val color = 0xff000000.toInt() or (++imageIndex)
                for (y in 0 until 512) for (x in 0 until 512) image.setRGB(x, y, color)
                val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
                val packer = MessagePack.newDefaultBufferPacker()
                packer.packMapHeader(2); packer.packString("event_type"); packer.packString("final")
                packer.packString("image"); packer.packBinaryHeader(png.size); packer.writePayload(png); packer.close()
                val payload = packer.toByteArray()
                output.write(ByteBuffer.allocate(4).putInt(payload.size).array()); output.write(payload)
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(output.toByteArray().toResponseBody()).build()
        }.build()
        fun reopen() { app.close(); app = container() }
    }
}
