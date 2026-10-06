package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.imageprompt.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopPhase7ReauditTest {
    @Test fun `repeated picks share pending list and independent removal with expanded composer`() = runBlocking { fixture { c, root ->
        val controller = c.primaryChatController
        controller.refresh(); controller.selectSession("session")
        repeat(3) { controller.pickImage() }
        val pending = controller.state.value.pendingImages
        assertEquals(3, pending.size); assertEquals(3, pending.map { it.id }.distinct().size)
        controller.removePendingImage(pending[1].id)
        assertEquals(listOf(pending[0].id, pending[2].id), controller.state.value.pendingImages.map { it.id })
        val composer = DesktopComposerInput("session", "")
        composer.hasAttachments = controller.state.value.pendingImages.isNotEmpty(); composer.open(true)
        assertTrue(composer.canSend(true)); assertTrue(composer.expanded)
        val saved = c.chatImages.persistUser("session", "", controller.state.value.pendingImages)
        assertEquals(2, saved.images.size); saved.images.forEach { assertTrue(Files.exists(root.resolve(it))) }
        val panel = source("DesktopPrimaryChatPanel.kt")
        assertTrue(panel.contains("attachments = { DesktopPendingImageStrip(state.pendingImages"))
        assertTrue(panel.contains("DesktopPendingImageStrip(state.pendingImages, running == null"))
    } }

    @Test fun `message edit publishes references before exact cleanup and retains matching metadata`() = runBlocking { fixture { c, root ->
        val old = c.chatImages.persistUser("session", "old", listOf(pending(), pending()))
        c.chatRepository.updateMessage(old.copy(generatedImageMetadata = old.images.map(::metadata)))
        val opening = c.chatRepository.getMessage(old.id, "session")!!
        val cleanup = DesktopOwnedImageCleanup(root, c.characterResourceStore, c.dataOperationCoordinator)
        val store = DesktopChatImages(c.characterResourceStore, c.chatRepository, c.dataOperationCoordinator, cleanup = { candidates ->
            val durable = c.chatRepository.getMessage(old.id, "session")!!
            assertTrue(candidates.none { it in durable.images })
            cleanup.deleteUnreferenced(candidates)
        })
        store.editMessage(opening, "edited", listOf(old.images[1]), listOf(pending()))
        val saved = c.chatRepository.getMessage(old.id, "session")!!
        assertEquals("edited", saved.content); assertEquals(2, saved.images.size)
        assertEquals(listOf(old.images[1]), saved.generatedImageMetadata.map { it.imagePath })
        assertFalse(Files.exists(root.resolve(old.images[0]))); saved.images.forEach { assertTrue(Files.exists(root.resolve(it))) }
        assertEquals(saved, com.example.chatbar.data.repository.ChatRepository(JsonFileStorage(root)).getMessage(old.id, "session"))
    } }

    @Test fun `edit failure after durable write retains both obsolete and newly committed images`() = runBlocking { fixture { c, root ->
        val old = c.chatImages.persistUser("session", "old", listOf(pending()))
        var cleaned = false
        val store = DesktopChatImages(c.characterResourceStore, c.chatRepository, c.dataOperationCoordinator,
            cleanup = { cleaned = true }, updateMessage = { c.chatRepository.updateMessage(it); error("after commit") })
        assertFails { store.editMessage(old, "edited", emptyList(), listOf(pending())) }
        val saved = c.chatRepository.getMessage(old.id, "session")!!
        assertNotEquals(old.images, saved.images); assertFalse(cleaned)
        (old.images + saved.images).forEach { assertTrue(Files.exists(root.resolve(it))) }
    } }

    @Test fun `stale or corrupt edit authority blocks allocation and preserves originals`() = runBlocking { fixture { c, root ->
        val old = c.chatImages.persistUser("session", "old", listOf(pending()))
        c.chatRepository.updateMessage(old.copy(content = "newer"))
        assertFails { c.chatImages.editMessage(old, "stale", emptyList(), listOf(pending())) }
        val before = Files.list(root.resolve("images")).use { it.count() }
        val record = root.resolve("entities/chat_messages/${old.sessionId}_${old.id}.json")
        assertTrue(Files.isRegularFile(record))
        Files.writeString(record, "{corrupt")
        assertFails { c.chatImages.editMessage(old, "bad", emptyList(), listOf(pending())) }
        assertEquals(before, Files.list(root.resolve("images")).use { it.count() })
        assertTrue(Files.exists(root.resolve(old.images.single())))
    } }

    @Test fun `deleting generated image removes matching metadata and last empty derived message only`() = runBlocking { fixture { c, root ->
        val source = c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "source reply"))
        val plan = NovelAiPromptPlan(baseCaption = "landscape", characterCaptions = emptyList())
        persistDesktopChatImages(c.chatRepository, c.characterResourceStore, c.dataOperationCoordinator, source,
            plan, NovelAiImageSize(16, 16, "fixture"), listOf(png(), png()), NovelAiGenerationRecipe(basePrompt = "landscape")) { true }
        var imageMessage = c.chatRepository.getMessages("session").single { it.generatedFromMessageId == source.id }
        val removed = imageMessage.images.first()
        c.chatImages.deleteImage(imageMessage, removed)
        imageMessage = c.chatRepository.getMessage(imageMessage.id, "session")!!
        assertEquals(1, imageMessage.images.size); assertEquals(imageMessage.images, imageMessage.generatedImageMetadata.map { it.imagePath })
        assertFalse(Files.exists(root.resolve(removed)))
        c.chatImages.deleteImage(imageMessage, imageMessage.images.single())
        assertNull(c.chatRepository.getMessage(imageMessage.id, "session"))
        assertNotNull(c.chatRepository.getMessage(source.id, "session"))
        assertFalse(Files.exists(root.resolve(imageMessage.images.single())))
    } }

    @Test fun `manual requirements persist only preference and task belongs to source assistant`() = runBlocking { fixture { c, _ ->
        val originalSession = c.chatRepository.getSession("session")!!
        val reply = c.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "source"))
        val service = c.primaryChatController.imageRegeneration!!
        // No model/secret is configured: task stops before any HTTP request, after admission/persistence.
        val id = service.generateFromAssistant(reply, DesktopChatImageRequirements("only this image", "saved preference"))
        val task = withTimeout(5000) { c.taskRuntime.tasks.first { tasks -> tasks.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } } }.single { it.taskId == id }
        assertEquals(DesktopTaskStatus.FAILED, task.status)
        assertEquals(reply.id, task.targetMessageId)
        assertEquals(listOf(task), desktopImageTasksForMessage(listOf(task, task.copy(taskId = "other", targetMessageId = "different")), reply))
        assertEquals("saved preference", c.chatRepository.getSession("session")!!.imagePromptPreference)
        assertEquals(originalSession.supplementarySetting, c.chatRepository.getSession("session")!!.supplementarySetting)
        assertFalse(c.chatRepository.getSession("session").toString().contains("only this image"))
        val transient = service.generateFromAssistant(reply, DesktopChatImageRequirements("once", "not persisted", persistPreference = false))
        withTimeout(5000) { c.taskRuntime.tasks.first { tasks -> tasks.any { it.taskId == transient && it.status != DesktopTaskStatus.RUNNING } } }
        assertEquals("saved preference", c.chatRepository.getSession("session")!!.imagePromptPreference)
        val ui = source("DesktopImageViewer.kt")
        assertTrue(ui.contains("generateFromAssistant(message, requirements)"))
        assertTrue(ui.contains("desktopImageTasksForMessage(tasks, message)"))
        assertTrue(ui.contains("generate(DesktopChatImageRequirements(imageHint, preference))"))
        val serviceSource = source("DesktopChatImageRegeneration.kt")
        assertTrue(serviceSource.contains("imageContentHint = requirements?.imageContentHint.orEmpty()"))
        assertTrue(serviceSource.contains("finalPromptRequirement = preference"))
    } }

    @Test fun `entry account and post-success reconciliation preserve local spending until server acknowledges`() = runBlocking { fixture { c, root ->
        var calls = 0
        var usage = NovelAiAccountUsage(100, 3, true, 10.0, false)
        var entered: CompletableDeferred<Unit>? = null; var release: CompletableDeferred<Unit>? = null
        var fail = false
        val controller = studio(c, root) {
            calls++; entered?.complete(Unit); release?.await()
            if (fail) error("sensitive provider detail must not escape")
            usage
        }
        controller.load(); assertEquals(1, calls); assertEquals(173, controller.state.value.accountUi.approximateV5Images)
        entered = CompletableDeferred(); release = CompletableDeferred()
        val work = async { controller.accountAfterSuccess(NovelAiGenerationCost(NovelAiGenerationChargeKind.V5_ALLOWANCE, anlas = 2), 1) }
        entered.await()
        assertEquals(98, controller.state.value.accountUi.displayAnlas?.toInt())
        assertEquals(172, controller.state.value.accountUi.approximateV5Images)
        release.complete(Unit); work.await(); entered = null; release = null
        assertEquals(98, controller.state.value.accountUi.displayAnlas?.toInt()) // stale server data does not reset local accounting
        usage = usage.copy(anlas = 98, v5AllowancePercent = 172.0 / 17.3)
        controller.refreshAccount(); assertEquals(0, controller.state.value.accountUi.localAnlasSpent.toInt())
        assertEquals(0, controller.state.value.accountUi.localV5AllowanceSpent)
        fail = true; controller.refreshAccount()
        assertNull(controller.state.value.account); assertNotNull(controller.state.value.accountUi.error)
        assertFalse(controller.state.value.toString().contains("sensitive provider detail"))
        assertEquals(NovelAiGenerationChargeKind.ANLAS, NovelAiImageCostEstimator.estimate(NovelAiGenerationSettings(), controller.state.value.account).kind)
    } }

    @Test fun `history uses shared filter date albums persisted preference and complete recipe detail`() = runBlocking { fixture { c, _ ->
        val time = java.util.Calendar.getInstance().apply { set(2026, 9, 6, 12, 0, 0) }.timeInMillis
        val entry = NovelAiGenerationHistoryEntry(createdAt = time, images = listOf(NovelAiGenerationHistoryImage("images/a.png", 17), NovelAiGenerationHistoryImage("images/b.png", 18)),
            recipe = NovelAiGenerationRecipe(basePrompt = "Landscape", negativePrompt = "excluded-query", settings = NovelAiGenerationSettings(steps = 20)))
        assertEquals(2, NovelAiHistoryFilterPolicy.filter(listOf(entry), "landscape", desktopHistoryDateFilter("2026-10")).size)
        assertTrue(NovelAiHistoryFilterPolicy.filter(listOf(entry), "excluded-query", null).isEmpty())
        assertTrue(NovelAiHistoryFilterPolicy.filter(listOf(entry), "", desktopHistoryDateFilter("2025")).isEmpty())
        assertFails { desktopHistoryDateFilter("2026-02-30") }
        val album = foldHistoryImages(NovelAiHistoryFilterPolicy.filter(listOf(entry), "", null), NovelAiHistoryFoldType.DAY).single()
        assertEquals(2, album.images.size)
        val preference = NovelAiHistoryFoldPreference(1, true, NovelAiHistoryFoldType.STYLE)
        c.novelAiStudioController.repository.saveHistoryFoldPreference(preference)
        assertEquals(preference, NovelAiStudioStateRepository(c.jsonFileStorage).loadHistoryFoldPreferences()[1])
        val details = desktopHistoryRecipeDetails(entry, entry.images[1]).toMap()
        assertEquals("18", details["实际 Seed"]); assertEquals("Landscape", details["基础 Prompt"])
        assertEquals("excluded-query", details["负面 Prompt"]); assertTrue(details.getValue("数量 / Steps").endsWith("20"))
        val visible = NovelAiHistoryImageSelection(entry.id, entry.images[0].path)
        val hidden = NovelAiHistoryImageSelection(entry.id, entry.images[1].path)
        c.novelAiStudioController.toggleSelection(visible); c.novelAiStudioController.toggleSelection(hidden)
        c.novelAiStudioController.retainHistorySelection(setOf(visible))
        assertEquals(setOf(visible), c.novelAiStudioController.state.value.selected)
    } }

    @Test fun `missing guidance cannot silently mutate draft and confirmation is explicit`() = runBlocking { fixture { c, root ->
        val controller = studio(c, root) { NovelAiAccountUsage(0, 0, false, null, false) }
        controller.load(); controller.edit { it.copy(basePrompt = "current") }
        val image = NovelAiGenerationHistoryImage("images/history.png", 17)
        val entry = NovelAiGenerationHistoryEntry(images = listOf(image), recipe = NovelAiGenerationRecipe(basePrompt = "historical",
            imageGuidance = NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.IMAGE_TO_IMAGE)))
        val before = controller.repository.loadDraft()
        assertFalse(controller.applyHistory(entry, image, NovelAiHistoryApplyMode.FULL))
        assertEquals(before, controller.repository.loadDraft())
        assertTrue(controller.applyHistory(entry, image, NovelAiHistoryApplyMode.SEED_ONLY))
        assertEquals("current", controller.repository.loadDraft().basePrompt)
        assertTrue(controller.applyHistory(entry, image, NovelAiHistoryApplyMode.NEW_SEED, allowMissingGuidance = true))
        assertEquals("historical", controller.repository.loadDraft().basePrompt)
        assertTrue(controller.state.value.status.contains("非完整复现"))
    } }

    @Test fun `reverse progress preserves separate full content reasoning and stage with direct guidance wiring`() {
        val progress = DesktopReversePromptProgress(reasoning = "private reasoning").content("【图片理解】\nscene\n【设计】\n" + "x".repeat(5000))
        assertEquals("设计", progress.stage); assertEquals("private reasoning", progress.reasoning)
        assertTrue(progress.content.length > 5000)
        val panel = source("DesktopNovelAiStudioPanel.kt")
        assertTrue(panel.contains("BootstrapButton(\"图像引导\") { auxiliary = \"图像引导\" }"))
        assertTrue(panel.contains("DesktopMarkdownText(state.reverseProgress.reasoning)"))
        assertTrue(panel.contains("DesktopMarkdownText(state.reverseProgress.content)"))
        assertTrue(panel.contains("DesktopStudioHistory(controller"))
    }

    private fun studio(c: DesktopAppContainer, root: Path, account: suspend () -> NovelAiAccountUsage) = DesktopNovelAiStudioController(
        c.jsonFileStorage, c.characterResourceStore, UnconfiguredDesktopFilePicker, c.dataOperationCoordinator, {}, c.novelAiGenerationRuntime,
        DesktopNovelAiGuidance(root, c.characterResourceStore, c.desktopSecretStore), c.taskRuntime, c.novelAiInfrastructure,
        c.settingsRepository, c.effectiveModelResolver, c.characterRepository, account)
    private fun metadata(path: String) = GeneratedImageMetadata(path, "scene", negativePrompt = "", sizePreset = "fixture", width = 16, height = 16)
    private fun png() = DesktopImageEditing.png(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))
    private fun pending() = DesktopPendingImage(bytes = png())
    private fun source(name: String) = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop", name))
    private suspend fun fixture(block: suspend (DesktopAppContainer, Path) -> Unit) {
        val parent = Files.createTempDirectory("p7-reaudit-"); val root = parent.resolve("data")
        val image = parent.resolve("fixture.png"); Files.write(image, png())
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType) = image
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
        }
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, filePicker = picker)
        try {
            c.characterRepository.save(CharacterCard.create("Fixture").copy(id = "card"))
            c.chatRepository.createSession(ChatSession(id = "session", characterCardId = "card", title = "Fixture", createdAt = 1, updatedAt = 1))
            block(c, root)
        } finally { c.close(); parent.toFile().deleteRecursively() }
    }
}
