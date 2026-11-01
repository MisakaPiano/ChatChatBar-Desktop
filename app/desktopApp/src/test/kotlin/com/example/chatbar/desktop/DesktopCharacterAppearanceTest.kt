package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopCharacterAppearanceTest {
    private val person = CharacterInfo("person", "合成人物", appearance = "原外貌", clothing = "原服装",
        appearanceImage = "synthetic-avatar.png", fishAudioVoice = FishAudioVoiceBinding("voice", "合成声线"), desktopEnabled = false)
    private val card = CharacterCard("card", "合成卡", greeting = "合成开场", characters = listOf(person),
        defaultImageNegativePrompt = "synthetic-negative", createdAt = 1, updatedAt = 1)
    private val model = ModelConfig("model", "合成模型", "http://127.0.0.1:1/v1", "fixture-key", "original",
        isMultimodal = true, createdAt = 1)
    private val output = """{"appearance":"","clothing":"新服装"}"""

    @Test fun `direct and exact linked model produce explicit text candidate preserving all other person fields`() = runBlocking {
        for (linked in listOf(false, true)) fixture { source, app, editor, picker ->
            if (linked) {
                app.modelRepository.saveModel(model.copy(id = "exact-vision", modelName = "exact-vision"))
                app.modelRepository.saveModel(model.copy(isMultimodal = false, visionModelId = "exact-vision"))
            }
            var calls = 0
            val draftSession = assertNotNull(editor.state.value.draftSessionId)
            val ai = controller(app, editor, picker) { messages, selected, diagnostics ->
                calls++; assertEquals(if (linked) "exact-vision" else "original", selected.modelName)
                diagnostics.onAttemptStarted(1)
                diagnostics.onResponseChunk("""{"usage":{"prompt_tokens":3,"completion_tokens":1}}""")
                assertTrue(messages.last().content.jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" })
                flowOf(StreamEvent.Delta(output), StreamEvent.Done)
            }
            ai.open(person.id); idle(ai)
            withTimeout(5_000) { app.providerUsage.state.first { it.pending == 0 } }
            val usage = DesktopProviderUsageStore(source.parent.resolve("data"), app.dataOperationCoordinator).records().single()
            assertEquals(DesktopUsageSource.CHARACTER_CARD, usage.source)
            assertEquals(card.id, usage.cardId); assertEquals(person.id, usage.personId)
            assertEquals(draftSession, usage.editorSessionId); assertNull(usage.sessionId)
            assertNotNull(usage.taskId); assertEquals(3L, usage.counters.prompt)
            assertEquals(linked, ai.state.value.route?.usesLinkedVisionModel)
            assertNotNull(ai.state.value.candidate); assertEquals(card, editor.state.value.card)
            assertTrue(ai.revealTaskDraft(draftSession)); assertFalse(ai.revealTaskDraft("other-draft"))
            assertEquals(DesktopOwnedWorkKind.CHARACTER_APPEARANCE, app.taskRuntime.ownedWork.value.single().kind)
            assertEquals(card, app.characterRepository.getById(card.id))
            assertTrue(ai.apply())
            assertFalse(ai.revealTaskDraft(draftSession))
            assertEquals(person.copy(clothing = "新服装"), editor.state.value.card!!.characters.single())
            assertEquals(card, app.characterRepository.getById(card.id))
            assertEquals(1, calls); assertNull(ai.state.value.preview)
            assertTrue(editor.save())
            assertEquals(person.copy(clothing = "新服装"), app.characterRepository.getById(card.id)!!.characters.single())
        }
    }

    @Test fun `manual change rejects late appearance candidate and missing linked vision never generates`() = runBlocking {
        fixture { _, app, editor, picker ->
            var calls = 0
            val ai = controller(app, editor, picker) { _, _, _ -> calls++; flowOf(StreamEvent.Delta(output), StreamEvent.Done) }
            ai.open(person.id); idle(ai)
            editor.updateCharacter(person.id) { it.copy(profile = "手动简介") }
            assertFalse(ai.apply()); assertEquals("原服装", editor.state.value.card!!.characters.single().clothing)
            ai.close()
            app.modelRepository.saveModel(model.copy(isMultimodal = false, visionModelId = "missing"))
            ai.open(person.id); idle(ai)
            assertNull(ai.state.value.candidate); assertNotNull(ai.state.value.error)
            assertEquals(1, calls); assertEquals(card, app.characterRepository.getById(card.id))
        }
    }

    @Test fun `truncated valid-looking appearance is only preview and never applied`() = runBlocking {
        fixture { _, app, editor, picker ->
            val ai = controller(app, editor, picker) { _, _, _ ->
                flowOf(StreamEvent.Delta(output), StreamEvent.Error("synthetic", cause = ModelResponseTruncatedException()))
            }
            ai.open(person.id); idle(ai)
            assertEquals(output, ai.state.value.raw); assertNull(ai.state.value.candidate)
            assertTrue(ai.state.value.error.orEmpty().contains("截断"))
            assertFalse(ai.state.value.error.orEmpty().contains("请检查模型输出上限"))
            assertTrue(ai.state.value.status.contains("失败"))
            assertFalse(ai.apply()); assertEquals(card, editor.state.value.card)
        }
    }

    @Test fun `maintenance cancels and drains image text request retaining source and original person`() = runBlocking {
        fixture { source, app, editor, picker ->
            val original = Files.readAllBytes(source)
            val entered = CompletableDeferred<Unit>(); val drained = CompletableDeferred<Unit>()
            val ai = controller(app, editor, picker) { _, _, _ -> flow {
                try { emit(StreamEvent.Delta("未完成")); entered.complete(Unit); awaitCancellation() }
                finally { drained.complete(Unit) }
            } }
            ai.open(person.id); withTimeout(5_000) { entered.await() }
            app.taskRuntime.withMaintenance { assertTrue(drained.isCompleted) }
            idle(ai); assertNull(ai.state.value.candidate); assertEquals(card, editor.state.value.card)
            assertContentEquals(original, Files.readAllBytes(source))
            ai.close(); assertNull(ai.state.value.preview)
        }
    }

    private fun controller(app: DesktopAppContainer, editor: DesktopCharacterEditorController, picker: DesktopFilePicker,
        stream: (List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>) =
        DesktopCharacterAppearanceController(editor, app.settingsRepository, app.effectiveModelResolver,
            app.taskRuntime, app.dataOperationCoordinator, picker, stream).also { editor.appearanceAi = it }
    private suspend fun idle(ai: DesktopCharacterAppearanceController) = withTimeout(5_000) { ai.state.first { !it.busy } }
    private suspend fun fixture(block: suspend (Path, DesktopAppContainer, DesktopCharacterEditorController, DesktopFilePicker) -> Unit) {
        val parent = Files.createTempDirectory("appearance-ai-fixture-").toAbsolutePath().normalize()
        val source = parent.resolve("synthetic-image.png")
        Files.write(source, DesktopImageEditing.png(java.awt.image.BufferedImage(12, 12, java.awt.image.BufferedImage.TYPE_INT_RGB)))
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType) = source
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("No export")
        }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            app.characterRepository.save(card); app.modelRepository.saveModel(model)
            app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true, defaultModelId = model.id) }
            val editor = app.characterEditorController; editor.openExisting(card.id)
            block(source, app, editor, picker)
        } finally {
            app.close()
            check(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            parent.toFile().deleteRecursively()
        }
    }
}
