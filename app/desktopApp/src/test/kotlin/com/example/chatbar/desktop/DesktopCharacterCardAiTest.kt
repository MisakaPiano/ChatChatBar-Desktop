package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.chat.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DesktopCharacterCardAiTest {
    private val person = CharacterInfo("person", "合成人物", profile = "原简介",
        appearanceImage = "synthetic-avatar.png", fishAudioVoice = FishAudioVoiceBinding("synthetic-voice", "合成声线"), desktopEnabled = false)
    private val card = CharacterCard("synthetic-card", "合成卡", greeting = "合成开场", characters = listOf(person),
        defaultImageNegativePrompt = "synthetic-negative", createdAt = 1, updatedAt = 1)
    private val model = ModelConfig("synthetic-model", "合成模型", "http://127.0.0.1:1/v1", "", "original-model", createdAt = 1)
    private val fill = """{"characters":[{"targetIndex":0,"appearance":"新外观"}]}"""

    @Test fun `fill exact fresh model diff apply ordinary draft and save preserve owned person configuration`() = runBlocking {
        fixture { _, app, editor ->
            val ai = controller(app, editor) { _, _, _, actual, _ ->
                assertEquals("fresh-model", actual.modelName); flowOf(StreamEvent.Delta(fill), StreamEvent.Done)
            }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.setRequest("补全外观"); ai.selectModel(model.id)
            app.modelRepository.saveModel(model.copy(modelName = "fresh-model"))
            ai.generate(); idle(ai)
            val merged = assertNotNull(ai.state.value.candidate)
            assertEquals(person.copy(appearance = "新外观"), merged.characters.single())
            assertTrue(desktopCharacterCardAiDiff(card, merged).any { it.before == "" && it.after == "新外观" })
            assertEquals(card, app.characterRepository.getById(card.id))
            assertTrue(ai.apply()); assertEquals(merged, editor.state.value.card)
            editor.flushDraft()
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, card.id))
            assertEquals(card, app.characterRepository.getById(card.id))
            assertTrue(editor.save())
            assertEquals(person.copy(appearance = "新外观"), app.characterRepository.getById(card.id)!!.characters.single())
        }
    }

    @Test fun `missing selected model does not fall back and stale manual edits cannot receive a candidate`() = runBlocking {
        fixture { _, app, editor ->
            var calls = 0
            val ai = controller(app, editor) { _, _, _, _, _ -> calls++; flowOf(StreamEvent.Delta(fill), StreamEvent.Done) }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.setRequest("补全外观"); ai.selectModel("missing-model")
            ai.generate(); idle(ai); assertEquals(0, calls); assertNull(ai.state.value.candidate)
            ai.selectModel(model.id); ai.generate(); idle(ai); assertEquals(1, calls)
            editor.edit { it.copy(basicSetting = "手动更改") }
            assertFalse(ai.apply()); assertEquals("手动更改", editor.state.value.card!!.basicSetting)
            assertEquals(card, app.characterRepository.getById(card.id))
        }
    }

    @Test fun `completed malformed generation retries only repair and cancellation never produces a candidate`() = runBlocking {
        fixture { _, app, editor ->
            val stages = mutableListOf<CharacterCardAiStage>(); var firstRepair = true
            val ai = controller(app, editor) { _, stage, _, _, _ ->
                stages += stage
                if (stage == CharacterCardAiStage.GENERATE) flowOf(StreamEvent.Delta("not-json"), StreamEvent.Done)
                else if (firstRepair) { firstRepair = false; flowOf(StreamEvent.Error("synthetic failure")) }
                else flowOf(StreamEvent.Delta(fill), StreamEvent.Done)
            }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.setRequest("补全外观")
            ai.generate(); idle(ai); assertNull(ai.state.value.candidate)
            ai.generate(retry = true); idle(ai)
            assertEquals(listOf(CharacterCardAiStage.GENERATE, CharacterCardAiStage.REPAIR, CharacterCardAiStage.REPAIR), stages)
            assertNotNull(ai.state.value.candidate)
            assertEquals(card, editor.state.value.card)
        }
    }

    @Test fun `maintenance cancels active request drains it and preserves partial preview without applying`() = runBlocking {
        fixture { _, app, editor ->
            val entered = CompletableDeferred<Unit>(); val drained = CompletableDeferred<Unit>()
            val ai = controller(app, editor) { _, _, _, _, _ -> flow {
                try { emit(StreamEvent.Delta("未完成正文")); entered.complete(Unit); awaitCancellation() }
                finally { drained.complete(Unit) }
            } }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.setRequest("合成要求"); ai.generate()
            withTimeout(5_000) { entered.await() }
            ai.close(); assertTrue(ai.state.value.open)
            app.taskRuntime.withMaintenance { assertTrue(drained.isCompleted) }
            idle(ai); assertNull(ai.state.value.candidate); assertEquals("未完成正文", ai.state.value.raw)
            assertEquals(card, editor.state.value.card); assertEquals(card, app.characterRepository.getById(card.id))
        }
    }

    @Test fun `freeform supports rewrite only and retains roster while applying explicit body change`() = runBlocking {
        fixture { _, app, editor ->
            editor.edit { it.copy(editMode = CharacterEditMode.FREEFORM, freeformCharacterText = "原自由正文") }
            val ai = controller(app, editor) { operation, _, _, _, _ ->
                assertEquals(CharacterCardAiOperation.REWRITE, operation)
                flowOf(StreamEvent.Delta("""{"freeformCharacterText":"新自由正文","deleteCharacterIds":["person"]}"""), StreamEvent.Done)
            }
            ai.open(CharacterCardAiOperation.FILL); assertFalse(ai.state.value.open)
            ai.open(CharacterCardAiOperation.REWRITE); ready(ai); ai.setRequest("改正文"); ai.generate(); idle(ai)
            assertEquals(listOf(person), ai.state.value.candidate!!.characters)
            assertTrue(ai.apply()); assertEquals("新自由正文", editor.state.value.card!!.freeformCharacterText)
            assertEquals(card, app.characterRepository.getById(card.id))
        }
    }

    private fun controller(app: DesktopAppContainer, editor: DesktopCharacterEditorController,
        stream: (CharacterCardAiOperation, CharacterCardAiStage, List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>) =
        DesktopCharacterCardAiController(editor, app.settingsRepository, app.effectiveModelResolver,
            app.taskRuntime, app.dataOperationCoordinator, stream).also { editor.cardAi = it }
    private suspend fun ready(ai: DesktopCharacterCardAiController) = withTimeout(5_000) { ai.state.first { !it.loading } }
    private suspend fun idle(ai: DesktopCharacterCardAiController) = withTimeout(5_000) { ai.state.first { !it.busy } }
    private suspend fun fixture(block: suspend (Path, DesktopAppContainer, DesktopCharacterEditorController) -> Unit) {
        val parent = Files.createTempDirectory("character-card-ai-fixture-").toAbsolutePath().normalize()
        val root = parent.resolve("app-data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            app.characterRepository.save(card)
            app.modelRepository.saveModel(model)
            app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true, defaultModelId = model.id) }
            val editor = app.characterEditorController; editor.openExisting(card.id)
            block(root, app, editor)
        } finally {
            if (app.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.CLOSED) app.close()
            check(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            parent.toFile().deleteRecursively()
        }
    }
}
