package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopFormatCardAutoFillTest {
    private val valid = """{"name":"合成格式卡","content":"合成正文\n第二行","userTools":[{"type":"STRONG_PROMPT_SUFFIX","text":"合成尾缀"},{"type":"RANDOM_NUMBER","minimum":"1","maximum":"3"}]}"""
    private val character = CharacterCard("synthetic-card", "合成角色卡", basicSetting = "旧资料", createdAt = 0, updatedAt = 0)
    private val model = ModelConfig("synthetic-model", "合成模型", "http://127.0.0.1:1/v1", "", "original-model", createdAt = 0)

    @Test fun `candidate reloads source and exact model and applies only to editor draft before explicit save`() = runBlocking {
        fixture { root, app, editor ->
            var calls = 0
            val ai = controller(app, editor) { _, messages, resolved, _ ->
                calls++
                assertEquals("fresh-model", resolved.modelName)
                assertTrue(messages.last().content.jsonPrimitive.content.contains("生成前更新的完整资料"))
                flowOf(StreamEvent.Delta(valid), StreamEvent.Done)
            }
            ai.open(); optionsReady(ai); ai.selectCharacter(character.id); ai.selectModel(model.id)
            app.characterRepository.save(character.copy(basicSetting = "生成前更新的完整资料"))
            app.modelRepository.saveModel(model.copy(modelName = "fresh-model"))
            ai.generate(); idle(ai)
            assertNotNull(ai.state.value.candidate); assertEquals(1, calls)
            assertTrue(app.formatCardRepository.getAll().isEmpty())
            editor.edit { it.copy(name = "应用时的手填名称", isDefault = true) }
            assertTrue(ai.apply())
            assertEquals("应用时的手填名称", editor.state.value.card!!.name)
            assertTrue(editor.state.value.card!!.isDefault)
            assertEquals(2, editor.state.value.card!!.userTools.size)
            assertTrue(app.formatCardRepository.getAll().isEmpty())
            editor.flushDraft()
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, null))
            if (app.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.CLOSED) app.close()
            val reopened = container(root)
            try {
                reopened.formatCardEditorController.openNew()
                val recovered = reopened.formatCardEditorController.state.value.card!!
                assertEquals("应用时的手填名称", recovered.name)
                assertEquals(editor.state.value.card!!.content, recovered.content)
                assertEquals(editor.state.value.card!!.userTools, recovered.userTools)
                assertTrue(reopened.formatCardRepository.getAll().isEmpty())
                assertTrue(reopened.formatCardEditorController.save())
                assertEquals(recovered.id, reopened.formatCardRepository.getAll().single().id)
            } finally { reopened.close() }
        }
    }

    @Test fun `missing exact model never falls back and following default resolves afresh`() = runBlocking {
        fixture { _, app, editor ->
            val actual = mutableListOf<String>()
            val ai = controller(app, editor) { _, _, resolved, _ -> actual += resolved.modelName; flowOf(StreamEvent.Delta(valid), StreamEvent.Done) }
            ai.open(); optionsReady(ai); ai.selectCharacter(character.id); ai.selectModel("removed-model")
            ai.generate(); idle(ai)
            assertTrue(actual.isEmpty()); assertNull(ai.state.value.candidate); assertNotNull(ai.state.value.error)
            ai.selectModel(null)
            app.modelRepository.saveModel(model.copy(modelName = "new-default"))
            ai.generate(); idle(ai)
            assertEquals(listOf("new-default"), actual); assertNotNull(ai.state.value.candidate)
            ai.close(); ai.open(); optionsReady(ai)
            assertNotNull(ai.state.value.candidate)
            ai.setRequest("另一份合成要求"); assertNull(ai.state.value.candidate)
        }
    }

    @Test fun `busy dismissal and form changes are blocked and cancel retains partial without applying`() = runBlocking {
        fixture { _, app, editor ->
            val started = CompletableDeferred<Unit>(); val drained = CompletableDeferred<Unit>()
            val ai = controller(app, editor) { _, _, _, _ -> flow {
                try { emit(StreamEvent.Delta("合成未完成片段")); started.complete(Unit); awaitCancellation() }
                finally { drained.complete(Unit) }
            } }
            ai.open(); optionsReady(ai); ai.selectCharacter(character.id)
            ai.generate(); withTimeout(5_000) { started.await() }
            ai.close(); ai.setRequest("不可写入"); ai.selectModel("other")
            assertTrue(ai.state.value.open); assertEquals("", ai.state.value.request); assertNull(ai.state.value.modelId)
            ai.cancel(); idle(ai); assertTrue(drained.isCompleted)
            assertNull(ai.state.value.candidate); assertEquals("合成未完成片段", ai.state.value.rawText)
            assertEquals("", editor.state.value.card!!.content); assertTrue(app.formatCardRepository.getAll().isEmpty())
        }
    }

    @Test fun `maintenance cancels registered design then permits a later generation and shutdown drains it`() = runBlocking {
        fixture { _, app, editor ->
            var starts = CompletableDeferred<Unit>(); var stops = CompletableDeferred<Unit>()
            val ai = controller(app, editor) { _, _, _, _ -> flow {
                try { starts.complete(Unit); awaitCancellation() } finally { stops.complete(Unit) }
            } }
            ai.open(); optionsReady(ai); ai.selectCharacter(character.id); ai.generate()
            withTimeout(5_000) { starts.await() }
            app.taskRuntime.withMaintenance {
                assertTrue(stops.isCompleted)
                assertFailsWith<DesktopTaskAdmissionException> { app.taskRuntime.withCardDesign("new") { error("MUST_NOT_RUN") } }
            }
            idle(ai); assertNull(ai.state.value.candidate)
            starts = CompletableDeferred(); stops = CompletableDeferred()
            ai.generate(); withTimeout(5_000) { starts.await() }
            app.taskRuntime.closeAndDrain()
            idle(ai); assertTrue(stops.isCompleted); assertEquals("", editor.state.value.card!!.content)
        }
    }

    @Test fun `new editor identity manual content and existing cards cannot receive a stale candidate`() = runBlocking {
        fixture { _, app, editor ->
            val ai = controller(app, editor) { _, _, _, _ -> flowOf(StreamEvent.Delta(valid), StreamEvent.Done) }
            ai.open(); optionsReady(ai); ai.selectCharacter(character.id); ai.generate(); idle(ai)
            ai.close(); editor.closeClean(); editor.openNew()
            assertFalse(ai.apply()); assertEquals("", editor.state.value.card!!.content)
            ai.open(); optionsReady(ai); ai.selectCharacter(character.id); ai.generate(); idle(ai)
            editor.edit { it.copy(content = "手动正文") }
            assertFalse(ai.apply()); assertEquals("手动正文", editor.state.value.card!!.content)
            editor.edit { it.copy(name = "手动卡") }; assertTrue(editor.save())
            ai.close(); ai.open(); assertFalse(ai.state.value.open)
            assertFalse(editor.canAutoFill)
            assertEquals("手动正文", app.formatCardRepository.getAll().single().content)
        }
    }

    @Test fun `failed repair keeps both previews and safe logs without writing a format`() = runBlocking {
        fixture { _, app, editor ->
            var calls = 0
            val ai = controller(app, editor) { _, _, _, diagnostics ->
                calls++
                diagnostics.onRequest(ProviderRequestEvidence("http://127.0.0.1:1/v1/chat/completions", "{\"synthetic\":true}"))
                flowOf(StreamEvent.Delta("{}"), StreamEvent.Done)
            }
            ai.open(); optionsReady(ai); ai.selectCharacter(character.id); ai.generate(); idle(ai)
            assertEquals(2, calls); assertEquals("{}", ai.state.value.rawText); assertEquals("{}", ai.state.value.repairText)
            assertNull(ai.state.value.candidate); assertNotNull(ai.state.value.error)
            val logs = ai.diagnostics.value.filter { it.sessionId == ai.diagnosticSessionId }
            assertEquals(2, logs.size); assertTrue(logs.all { it.status == DesktopTaskStatus.FAILED })
            assertTrue(app.formatCardRepository.getAll().isEmpty()); assertEquals("", editor.state.value.card!!.content)
        }
    }

    private fun controller(app: DesktopAppContainer, editor: DesktopFormatCardEditorController,
        stream: (FormatCardAutoFillStage, List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>) =
        DesktopFormatCardAutoFillController(editor, app.characterRepository, app.settingsRepository, app.effectiveModelResolver,
            app.taskRuntime, app.dataOperationCoordinator, stream).also { editor.autoFill = it }
    private suspend fun optionsReady(ai: DesktopFormatCardAutoFillController) = withTimeout(5_000) { ai.state.first { !it.loadingOptions } }
    private suspend fun idle(ai: DesktopFormatCardAutoFillController) = withTimeout(5_000) { ai.state.first { !it.busy } }
    private suspend fun fixture(block: suspend (Path, DesktopAppContainer, DesktopFormatCardEditorController) -> Unit) {
        val parent = Files.createTempDirectory("format-card-ai-fixture-").toAbsolutePath().normalize()
        val root = parent.resolve("app-data"); val app = container(root)
        try {
            app.characterRepository.save(character)
            app.modelRepository.saveModel(model)
            app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true, defaultModelId = model.id) }
            val editor = app.formatCardEditorController; editor.openNew()
            block(root, app, editor)
        } finally {
            if (app.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.CLOSED) app.close()
            check(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            parent.toFile().deleteRecursively()
        }
    }
    private fun container(root: Path) = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
        DesktopDataRootProvenance.CLI_OVERRIDE, root.parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
            check(path == "presets/manifest.json")
            """{"entries":[]}""".toByteArray()
        })
}
