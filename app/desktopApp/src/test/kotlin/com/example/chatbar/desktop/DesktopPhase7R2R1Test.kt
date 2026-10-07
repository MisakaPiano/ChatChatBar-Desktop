package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.chat.ProviderCompletionMetadata
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopPhase7R2R1Test {
    @Test fun `missing-source apply matrix uses formal warning and never permits FULL`() = runBlocking { fixture { c, _ ->
        val controller = c.novelAiStudioController
        controller.load()
        for (missing in listOf(false, true)) for (mode in NovelAiHistoryApplyMode.entries) {
            controller.edit { NovelAiStudioDraft(basePrompt = "current") }
            val entry = NovelAiGenerationHistoryEntry(recipe = NovelAiGenerationRecipe(basePrompt = "history",
                imageGuidance = if (missing) NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.IMAGE_TO_IMAGE) else NovelAiImageGuidanceDraft()))
            val image = NovelAiGenerationHistoryImage("images/fixture.png", 17)
            val before = controller.repository.loadDraft()
            assertEquals(missing && mode != NovelAiHistoryApplyMode.FULL, controller.historyReuseNeedsConfirmation(entry, mode))
            assertEquals(entry.recipe.requiresImageGuidanceReuseWarning(mode), controller.historyReuseNeedsConfirmation(entry, mode))
            assertEquals(!missing || mode != NovelAiHistoryApplyMode.FULL, desktopHistoryApplyAvailable(entry.recipe, mode))
            assertEquals(!missing, controller.applyHistory(entry, image, mode))
            if (missing) {
                assertEquals(before, controller.repository.loadDraft())
                assertEquals(mode != NovelAiHistoryApplyMode.FULL, controller.applyHistory(entry, image, mode, warningConfirmed = true))
                if (mode == NovelAiHistoryApplyMode.FULL) assertEquals(before, controller.repository.loadDraft())
            }
        }
        val history = source("DesktopStudioHistory.kt")
        assertTrue(history.contains("if (!available) \"缺少来源\"")); assertTrue(history.contains("enabled = available"))
        val output = source("DesktopNovelAiStudioPanel.kt")
        for (mode in listOf(NovelAiHistoryApplyMode.NEW_SEED, NovelAiHistoryApplyMode.SEED_ONLY)) {
            assertTrue(output.contains("reuse(entry, image, NovelAiHistoryApplyMode.$mode)"))
        }
    } }

    @Test fun `current and history Use-as use model-filtered targets`() {
        assertEquals(NovelAiImageUseTarget.entries.toList(), desktopImageUseTargets(NovelAiImageModel.V4_5_FULL))
        assertEquals(listOf(NovelAiImageUseTarget.IMAGE_TO_IMAGE, NovelAiImageUseTarget.INPAINT), desktopImageUseTargets(NovelAiImageModel.V5_FULL))
        assertTrue(source("DesktopNovelAiStudioPanel.kt").contains("desktopImageUseTargets(d.selectedModel)"))
        assertTrue(source("DesktopStudioHistory.kt").contains("desktopImageUseTargets(current.selectedModel)"))
    }

    @Test fun `guidance label follows effective shared summary including inactive V5 reference`() {
        val blank = NovelAiImageGuidanceDraft()
        assertEquals("图像引导", desktopGuidanceLabel(blank, NovelAiImageModel.V4_5_FULL))
        val precise = blank.copy(referenceMode = NovelAiReferenceMode.PRECISE)
        assertTrue(desktopGuidanceLabel(precise, NovelAiImageModel.V4_5_FULL).contains(precise.summary(NovelAiImageModel.V4_5_FULL)))
        assertEquals("图像引导", desktopGuidanceLabel(precise, NovelAiImageModel.V5_FULL))
        val inpaint = blank.copy(action = NovelAiGenerationAction.INPAINT)
        assertTrue(desktopGuidanceLabel(inpaint, NovelAiImageModel.V5_FULL).contains("聚焦重绘"))
    }

    @Test fun `anchored tasks support Stop retry and terminal dismiss without deleting images`() = runBlocking { fixture { c, root ->
        val tasks = c.taskRuntime
        var attempts = 0
        val started = CompletableDeferred<Unit>()
        val id = tasks.launchNovelAi("fixture", "session", "source", retryable = true) {
            attempts++; started.complete(Unit); awaitCancellation()
        }
        started.await()
        assertFalse(tasks.dismissImageTask(id)); assertNull(tasks.retryImageTask(id))
        assertTrue(tasks.requestUserStop(id)); assertTrue(terminal(tasks, id).canRetry)
        val next = assertNotNull(tasks.retryImageTask(id))
        withTimeout(5000) { while (attempts < 2) yield() }
        assertEquals("source", tasks.tasks.value.single { it.taskId == next }.targetMessageId)
        assertNull(tasks.retryImageTask(id)); assertTrue(tasks.requestUserStop(next)); terminal(tasks, next)
        assertTrue(tasks.dismissImageTask(next)); assertNull(tasks.retryImageTask(next))
        val original = c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "source"))
        val png = DesktopImageEditing.png(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB))
        val complete = tasks.launchNovelAi("local save", "session", original.id, retryable = true) {
            persistDesktopChatImages(c.chatRepository, c.characterResourceStore, c.dataOperationCoordinator, original,
                NovelAiPromptPlan("fixture", emptyList()), NovelAiImageSize(4, 4, "fixture"), listOf(png), NovelAiGenerationRecipe()) { true }
        }
        assertFalse(terminal(tasks, complete).canRetry)
        val image = c.chatRepository.getMessages("session").single { it.generatedFromMessageId == original.id }
        assertTrue(tasks.dismissImageTask(complete))
        assertEquals(image, c.chatRepository.getMessage(image.id, "session")); assertTrue(Files.exists(root.resolve(image.images.single())))
        val ui = source("DesktopImageViewer.kt")
        assertTrue(ui.contains("controller.stop(task.taskId)")); assertTrue(ui.contains("retryImageTask(task.taskId)"))
        assertTrue(ui.contains("dismissImageTask(task.taskId)"))
    } }

    @Test fun `manual retry retains actual hint and requirement after session preference changes`() = runBlocking { fixture { c, _ ->
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"fixture rejection\"}}")
            }
            configureModel(c, server.url("/v1").toString())
            val original = c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "fixture scene"))
            val id = c.primaryChatController.imageRegeneration!!.generateFromAssistant(original,
                DesktopChatImageRequirements("unique-hint-original", "unique-preference-original"))
            assertEquals(DesktopTaskStatus.FAILED, terminal(c.taskRuntime, id).status)
            val firstCount = server.requestCount
            assertTrue(firstCount > 0)
            val first = (0 until firstCount).map { server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8() }
            val session = c.chatRepository.getSession("session")!!
            c.chatRepository.saveSessionSettingsDraft(session, session.copy(imagePromptPreference = "unique-preference-changed"))
            val next = assertNotNull(c.taskRuntime.retryImageTask(id))
            assertEquals(DesktopTaskStatus.FAILED, terminal(c.taskRuntime, next).status)
            val second = (firstCount until server.requestCount).map { server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8() }
            for (bodies in listOf(first, second)) {
                assertTrue(bodies.any { it.contains("unique-hint-original") && it.contains("unique-preference-original") })
                assertTrue(bodies.none { it.contains("unique-preference-changed") })
            }
            assertEquals("unique-preference-changed", c.chatRepository.getSession("session")!!.imagePromptPreference)
        }
    } }

    @Test fun `regeneration retry retains actual prompt seed dimensions and anchor using fake HTTP`() = runBlocking { fixture { c, _ ->
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            bodies += okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("fixture")
                .body("{}".toResponseBody()).build()
        }.build()
        c.desktopSecretStore.save(DesktopCredentialKey.NovelAiToken, "synthetic-fixture-token")
        val service = DesktopChatImageRegeneration(c.chatRepository, c.characterRepository, c.settingsRepository,
            c.characterResourceStore, c.dataOperationCoordinator, c.desktopSecretStore, c.taskRuntime,
            c.novelAiInfrastructure, c.effectiveModelResolver, client)
        val original = c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "source"))
        val draft = NovelAiImageRegenerationDraft("fixture-base", emptyList(), "fixture-negative", "fixture", 512, 512, "fixture-style")
        val settings = NovelAiGenerationSettings(seedMode = NovelAiSeedMode.FIXED, seed = 37, customWidth = 512, customHeight = 512)
        val id = service.generate(original, draft, settings)
        assertEquals(DesktopTaskStatus.FAILED, terminal(c.taskRuntime, id).status)
        val next = assertNotNull(c.taskRuntime.retryImageTask(id)); val terminal = terminal(c.taskRuntime, next)
        assertEquals(original.id, terminal.targetMessageId); assertEquals(2, bodies.size); assertEquals(bodies[0], bodies[1])
        assertTrue(bodies[1].contains("fixture-base")); assertTrue(bodies[1].contains("fixture-style"))
        assertTrue(bodies[1].contains("\"seed\":37")); assertTrue(bodies[1].contains("\"width\":512"))
        c.chatRepository.updateMessage(original.copy(content = "edited source"))
        val invalid = assertNotNull(c.taskRuntime.retryImageTask(next)); terminal(c.taskRuntime, invalid)
        assertEquals(2, bodies.size, "Changed source must fail before HTTP")
    } }

    @Test fun `automatic retry rechecks opt-in source and original stopped control`() = runBlocking { fixture { c, _ ->
        configureModel(c, "http://127.0.0.1:1/v1")
        var attempts = 0; var stopped = false
        val automatic = DesktopAutomaticChatImages(c.chatRepository, c.characterRepository, c.settingsRepository,
            c.effectiveModelResolver, { attempts++; error("fake designer failure") }, c.characterResourceStore,
            c.dataOperationCoordinator, c.desktopSecretStore,
            launch = { session, message, work -> c.taskRuntime.launchNovelAi("automatic", session, message, retryable = true, work = work) })
        for (change in listOf("disabled", "edited", "stopped")) {
            stopped = false
            val session = c.chatRepository.getSession("session")!!
            c.chatRepository.saveSessionSettingsDraft(session, session.copy(automaticImageGenerationEnabled = true))
            val original = c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "source-$change"))
            automatic.completed(DesktopRealChatResult(ChatMessage.create("session", MessageRole.USER, "input"), true,
                original, ProviderCompletionMetadata("stop"))) { stopped }
            val id = c.taskRuntime.tasks.value.first { it.targetMessageId == original.id }.taskId
            terminal(c.taskRuntime, id); val before = attempts
            when (change) {
                "disabled" -> c.chatRepository.getSession("session")!!.let { c.chatRepository.saveSessionSettingsDraft(it, it.copy(automaticImageGenerationEnabled = false)) }
                "edited" -> c.chatRepository.updateMessage(original.copy(content = "edited"))
                else -> stopped = true
            }
            val retry = assertNotNull(c.taskRuntime.retryImageTask(id))
            assertEquals(DesktopTaskStatus.FAILED, terminal(c.taskRuntime, retry).status); assertEquals(before, attempts)
        }
        assertEquals(3, attempts)
    } }

    private suspend fun terminal(tasks: DesktopTaskRuntime, id: String) = withTimeout(15000) {
        tasks.tasks.first { entries -> entries.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } }.single { it.taskId == id }
    }
    private suspend fun configureModel(c: DesktopAppContainer, baseUrl: String) {
        c.modelRepository.saveModel(ModelConfig(id = "fixture-model", displayName = "Fixture", baseUrl = baseUrl.trimEnd('/'),
            apiKey = "fixture-api-key", modelName = "fixture", createdAt = 1))
        c.settingsRepository.saveAppSettings(AppSettings(defaultImageModelId = "fixture-model", allowCleartextModelApi = true))
    }
    private fun source(name: String) = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop", name))
    private suspend fun fixture(block: suspend (DesktopAppContainer, Path) -> Unit) {
        val root = Files.createTempDirectory("p7-r2-r1-")
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        try {
            c.characterRepository.save(CharacterCard.create("Fixture").copy(id = "card"))
            c.chatRepository.createSession(ChatSession(id = "session", characterCardId = "card", title = "Fixture", createdAt = 1, updatedAt = 1))
            block(c, root)
        } finally { c.close(); root.toFile().deleteRecursively() }
    }
}
