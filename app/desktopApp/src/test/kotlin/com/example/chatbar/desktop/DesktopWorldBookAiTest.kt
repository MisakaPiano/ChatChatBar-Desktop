package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.search.*
import com.example.chatbar.domain.worldbook.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal val worldBookOfflineResearch = object : WorldBookResearchPort {
    override suspend fun research(request: String, book: WorldBook, targets: List<WorldBookEntry>, model: ModelConfig,
        options: CharacterResearchOptions, referenceDocument: CharacterReferenceDocument?, session: WorldBookResearchSession?,
        resumeFrom: ResearchDebugSnapshot?, onSession: (WorldBookResearchSession) -> Unit, onDebug: (ResearchDebugSnapshot) -> Unit,
        onVisibleOutput: (String, String, String) -> Unit, onStatus: (String) -> Unit) =
        WorldBookResearchResult(session = session ?: WorldBookResearchSession())
}

class DesktopWorldBookAiTest {
    private val model = ModelConfig("model", "合成模型", "http://127.0.0.1:1/v1", "", "synthetic", createdAt = 1)
    private val book = WorldBook("book", "合成世界书", entries = listOf(
        WorldBookEntry("old", "旧条目", listOf("旧词"), "原正文", enabled = false, sticky = 4, priority = 7)))
    private val create = """{"entries":[{"name":"山脉","keys":["群峰"]},{"name":"河流","keys":["清溪"]}],"hasMore":false}"""

    @Test fun `create uses exact fresh model selected candidates apply draft then normal save`() = runBlocking {
        fixture { app, editor, book ->
            val ai = controller(app, editor) { _, _, _, actual, _ ->
                assertEquals("fresh-model", actual.modelName); flowOf(StreamEvent.Delta(create), StreamEvent.Done)
            }
            ai.open(WorldBookAiOperation.CREATE); ready(ai); ai.setRequest("合成要求"); ai.selectModel(model.id)
            app.modelRepository.saveModel(model.copy(modelName = "fresh-model"))
            ai.generate(); idle(ai); assertNull(ai.state.value.error)
            val draft = assertNotNull(editor.state.value.draftSessionId)
            assertTrue(ai.revealTaskDraft(draft)); assertFalse(ai.revealTaskDraft("other-draft"))
            assertEquals(DesktopOwnedWorkKind.WORLD_BOOK_AI, app.taskRuntime.ownedWork.value.single().kind)
            val plans = ai.state.value.plans; assertEquals(2, plans.size)
            ai.selectCandidate(plans.last().candidateId, false)
            assertEquals(book, editor.state.value.book); assertEquals(book, app.worldBookRepository.getById(book.id))
            assertTrue(ai.apply()); assertEquals(listOf("旧条目", "山脉"), editor.state.value.book!!.entries.map { it.name })
            assertFalse(ai.revealTaskDraft(draft))
            assertEquals(book.entries.first(), editor.state.value.book!!.entries.first())
            assertEquals("", editor.state.value.book!!.entries.last().content)
            editor.flushDraft(); assertEquals(book, app.worldBookRepository.getById(book.id))
            assertTrue(editor.save()); assertEquals(2, app.worldBookRepository.getById(book.id)!!.entries.size)
        }
    }

    @Test fun `fill resumes completed batches and changing actual model invalidates retained checkpoint`() = runBlocking {
        fixture { app, editor, book ->
            val targets = (1..6).map { WorldBookEntry("target$it", "主题$it", listOf("词$it"), enabled = false, cooldown = 3) }
            editor.edit { it.copy(entries = it.entries + targets) }
            val original = editor.state.value.book!!
            var calls = 0; var failSecond = true
            val batchIds = mutableListOf<List<String>>()
            val ai = controller(app, editor) { op, stage, messages, _, _ ->
                assertEquals(WorldBookAiOperation.FILL, op); assertEquals(WorldBookAiStage.GENERATE, stage)
                val ids = Json.parseToJsonElement(messages.last().content.jsonPrimitive.content).jsonObject.getValue("targets")
                    .jsonArray.map { it.jsonObject.getValue("targetId").jsonPrimitive.content }
                calls++; batchIds += ids
                if (calls == 2 && failSecond) flowOf(StreamEvent.Error("fixture"))
                else flowOf(StreamEvent.Delta(buildJsonObject { put("entries", buildJsonArray { ids.forEach { id ->
                    add(buildJsonObject { put("targetId", id); put("content", "新正文-$id") })
                } }) }.toString()), StreamEvent.Done)
            }
            ai.open(WorldBookAiOperation.FILL); ready(ai); ai.generate(); idle(ai)
            assertEquals(5, ai.state.value.contents.size); assertTrue(ai.state.value.canResume)
            assertEquals(original, editor.state.value.book)
            failSecond = false; ai.generate(resume = true); idle(ai)
            assertEquals(6, ai.state.value.contents.size); assertEquals(listOf(5, 1, 1), batchIds.map { it.size })
            ai.generate(); idle(ai); assertFalse(ai.state.value.canResume)
            // Start a partial task again, then change the resolved model while retaining its ID.
            calls = 0; batchIds.clear(); failSecond = true; ai.generate(); idle(ai)
            assertEquals(5, ai.state.value.contents.size)
            app.modelRepository.saveModel(model.copy(modelName = "replacement"))
            failSecond = false; ai.generate(resume = true); idle(ai)
            assertEquals(listOf(5, 1, 5, 1), batchIds.map { it.size })
            assertTrue(ai.state.value.warning.contains("断点失效"))
            ai.selectAll(false); ai.selectCandidate("target6", true); assertTrue(ai.apply())
            assertEquals(original.entries.dropLast(1), editor.state.value.book!!.entries.dropLast(1))
            assertEquals(targets.last().copy(content = "新正文-target6"), editor.state.value.book!!.entries.last())
            assertEquals(book, app.worldBookRepository.getById(book.id))
        }
    }

    @Test fun `missing selection never falls back and manual changes reject late apply`() = runBlocking {
        fixture { app, editor, book ->
            var calls = 0
            val ai = controller(app, editor) { _, _, _, _, _ -> calls++; flowOf(StreamEvent.Delta(create), StreamEvent.Done) }
            ai.open(WorldBookAiOperation.CREATE); ready(ai); ai.setRequest("合成要求"); ai.selectModel("missing")
            ai.generate(); idle(ai); assertEquals(0, calls); assertTrue(ai.state.value.plans.isEmpty())
            ai.selectModel(null); ai.generate(); idle(ai); assertEquals(1, calls)
            editor.edit { it.copy(description = "手动变更") }
            assertFalse(ai.apply()); assertEquals("手动变更", editor.state.value.book!!.description)
            assertEquals(book, app.worldBookRepository.getById(book.id))
        }
    }

    @Test fun `maintenance joins active stream leaving partial output but no applied candidates`() = runBlocking {
        fixture { app, editor, book ->
            val entered = CompletableDeferred<Unit>(); val drained = CompletableDeferred<Unit>()
            val ai = controller(app, editor) { _, _, _, _, _ -> flow {
                try { emit(StreamEvent.Delta("未完成输出")); entered.complete(Unit); awaitCancellation() }
                finally { drained.complete(Unit) }
            } }
            ai.open(WorldBookAiOperation.CREATE); ready(ai); ai.setRequest("合成要求"); ai.generate()
            withTimeout(5_000) { entered.await() }
            ai.close(); assertTrue(ai.state.value.open)
            app.taskRuntime.withMaintenance { assertTrue(drained.isCompleted) }
            idle(ai); assertTrue(ai.state.value.plans.isEmpty()); assertEquals("未完成输出", ai.state.value.outputs.last().text)
            assertEquals(book, editor.state.value.book); assertEquals(book, app.worldBookRepository.getById(book.id))
        }
    }

    @Test fun `fresh attempt removes stale resume affordance before early model failure`() = runBlocking {
        fixture { app, editor, book ->
            var calls = 0
            val ai = controller(app, editor) { _, _, _, _, _ -> calls++; flowOf(StreamEvent.Error("fixture")) }
            ai.open(WorldBookAiOperation.CREATE); ready(ai); ai.setRequest("合成要求"); ai.generate(); idle(ai)
            assertTrue(ai.state.value.canResume)
            app.settingsRepository.updateAppSettings { it.copy(defaultModelId = "missing") }
            app.modelRepository.deleteModel(model.id)
            ai.generate(); idle(ai)
            assertFalse(ai.state.value.canResume); assertEquals(1, calls)
            assertTrue(ai.state.value.outputs.isEmpty()); assertEquals(book, editor.state.value.book)
        }
    }

    @Test fun `prestart cancellation resets busy without requests and can retry`() = runBlocking {
        fixture { app, editor, book ->
            val dispatcher = QueuedDispatcher(); val scope = CoroutineScope(SupervisorJob() + dispatcher); var calls = 0
            val ai = controller(app, editor, scope) { _, _, _, _, _ -> calls++; flowOf(StreamEvent.Delta(create), StreamEvent.Done) }
            try {
                ai.open(WorldBookAiOperation.CREATE); dispatcher.until { !ai.state.value.loading }; ai.setRequest("合成要求")
                ai.generate(); ai.cancel(); dispatcher.runQueued()
                assertFalse(ai.state.value.busy); assertEquals(0, calls); assertTrue(ai.state.value.plans.isEmpty())
                ai.generate(); dispatcher.until { !ai.state.value.busy }; assertEquals(1, calls); assertEquals(2, ai.state.value.plans.size)
            } finally { scope.cancel(); dispatcher.until { scope.coroutineContext[Job]!!.isCompleted } }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runQueued() { while (true) (queue.poll() ?: return).run() }
        suspend fun until(condition: () -> Boolean) = withTimeout(5_000) {
            while (!condition()) { runQueued(); yield(); delay(1) }; runQueued()
        }
    }
    private fun controller(app: DesktopAppContainer, editor: DesktopWorldBookEditorController,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        stream: (WorldBookAiOperation, WorldBookAiStage, List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>) =
        DesktopWorldBookAiController(editor, app.settingsRepository, app.effectiveModelResolver, app.taskRuntime, app.dataOperationCoordinator,
            stream, research = { worldBookOfflineResearch }, scope = scope).also { editor.ai = it }
    private suspend fun ready(ai: DesktopWorldBookAiController) = withTimeout(5_000) { ai.state.first { !it.loading } }
    private suspend fun idle(ai: DesktopWorldBookAiController) = withTimeout(5_000) { ai.state.first { !it.busy } }
    private suspend fun fixture(block: suspend (DesktopAppContainer, DesktopWorldBookEditorController, WorldBook) -> Unit) {
        val parent = Files.createTempDirectory("world-book-ai-").toAbsolutePath().normalize()
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            app.worldBookRepository.save(book); app.modelRepository.saveModel(model)
            app.settingsRepository.updateAppSettings { it.copy(defaultModelId = model.id, allowCleartextModelApi = true,
                worldBookAiResearchSourceMode = CharacterResearchSourceMode.NONE) }
            val editor = app.worldBookEditorController; editor.openExisting(book.id)
            block(app, editor, assertNotNull(app.worldBookRepository.getById(book.id)))
        } finally {
            if (app.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.CLOSED) app.close()
            check(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            parent.toFile().deleteRecursively()
        }
    }
}
