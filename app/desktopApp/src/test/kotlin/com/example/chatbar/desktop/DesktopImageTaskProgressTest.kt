package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopImageTaskProgressTest {
    @Test fun `runtime history pruning retires associated process memory`() = runBlocking {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            var first = ""
            repeat(42) { index ->
                val id = f.c.taskRuntime.launchNovelAi("fixture", "session", f.source.id) {
                    it.designSnapshot("【研究】\nfixture $index")
                }
                if (index == 0) first = id
                f.terminal(id)
            }
            val runtime = f.c.taskRuntime
            assertEquals(40, runtime.tasks.value.size)
            assertEquals(runtime.tasks.value.map { it.taskId }.toSet(), runtime.imageProgress.states.value.keys)
            assertFalse(first in runtime.imageProgress.states.value)
        } finally { f.close() }
    }

    @Test fun `automatic image handoff consumes shared cumulative callback and stops only its own task`() = runBlocking {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val transport = object : NovelAiTextTransport {
                override fun streamText(stage: NovelAiTextStage, messages: List<ChatApiMessage>, modelConfig: com.example.chatbar.data.local.entity.ModelConfig) = kotlinx.coroutines.flow.flow {
                    if (stage == NovelAiTextStage.PLAN) {
                        emit(StreamEvent.Delta("""{"sceneDescription":"adult reading","queries":[]}""")); emit(StreamEvent.Done)
                    } else {
                        emit(StreamEvent.Delta("automatic actual output")); awaitCancellation()
                    }
                }
            }
            val designer = NovelAiPromptDesigner(transport, NovelAiTagResearchService(LlmNovelAiTagSearchPlanner(transport),
                object : NovelAiTagSearchClient { override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList()) }), NovelAiPromptPostProcessor(emptyList()))
            val session = f.c.chatRepository.getSession("session")!!
            f.c.chatRepository.saveSessionSettingsDraft(session, session.copy(automaticImageGenerationEnabled = true))
            val automatic = DesktopAutomaticChatImages(f.c.chatRepository, f.c.characterRepository, f.c.settingsRepository,
                f.resolver, { designer }, f.c.characterResourceStore, f.c.dataOperationCoordinator, f.c.desktopSecretStore,
                launch = { s, m, work -> f.c.taskRuntime.launchNovelAi("automatic", s, m, retryable = true, work = work) })
            val before = f.c.chatRepository.getMessages("session")
            automatic.completed(DesktopRealChatResult(before.first(), true, f.source, ProviderCompletionMetadata("stop"))) { false }
            val runtime = f.c.taskRuntime
            val id = runtime.tasks.value.single().taskId
            withTimeout(10000) { runtime.imageProgress.states.first { it[id]?.designText?.contains("automatic actual output") == true } }
            val entry = runtime.tasks.value.single()
            assertEquals(DesktopTaskKind.NOVELAI, entry.kind); assertEquals(f.source.id, entry.targetMessageId)
            assertEquals("session", entry.sessionId)
            assertTrue(runtime.requestUserStop(id)); assertEquals(DesktopTaskStatus.USER_STOPPED, f.terminal(id).status)
            assertEquals(before, f.c.chatRepository.getMessages("session"))
            assertTrue(f.requests.isEmpty())
        } finally { f.close() }
    }

    @Test fun `cumulative research design repair replaces snapshots and flushes stages`() = runTest {
        val progress = DesktopImageTaskProgress(backgroundScope)
        progress.admit("task")
        progress.design("task", "【研究】\nA")
        assertEquals("【研究】\nA", progress.states.value.getValue("task").designText)
        repeat(100) { progress.design("task", "【研究】\nA$it") }
        assertEquals("【研究】\nA", progress.states.value.getValue("task").designText)
        runCurrent(); advanceTimeBy(81); runCurrent()
        assertEquals("【研究】\nA99", progress.states.value.getValue("task").designText)
        val design = "【研究】\nA99\n\n【最终 Prompt 设计】\nB"
        progress.design("task", design)
        assertEquals(design, progress.states.value.getValue("task").designText)
        val repair = "$design\n\n【JSON 修复】\nC"
        progress.design("task", repair)
        assertEquals(repair, progress.states.value.getValue("task").designText)
        progress.generation("task", "Step 1")
        assertEquals(DesktopImageProcessPhase.GENERATION, progress.states.value.getValue("task").phase)
        progress.generation("task", "Step 28"); progress.flush("task")
        assertEquals("Step 28", progress.states.value.getValue("task").generationStatus)
        assertEquals(repair, progress.states.value.getValue("task").designText)
    }

    @Test fun `bounded redacted retention and removal cancel trailing emissions`() = runTest {
        val progress = DesktopImageTaskProgress(backgroundScope)
        progress.admit("task")
        progress.design("task", "【研究】\nBearer synthetic-token " + "x".repeat(100000))
        val value = progress.states.value.getValue("task")
        assertTrue(value.truncated)
        assertTrue(value.designText.length <= DesktopImageTaskProgress.TEXT_LIMIT + 20)
        assertFalse(value.designText.contains("synthetic-token"))
        progress.design("task", "【研究】\nlate")
        progress.retain(emptySet()); advanceTimeBy(100); runCurrent()
        assertTrue(progress.states.value.isEmpty())
        progress.design("task", "orphan"); progress.generation("task", "orphan")
        assertTrue(progress.states.value.isEmpty())
    }

    @Test fun `retry fresh ownership ignores stale callbacks retains terminal and leaves durable source intact`() = runBlocking {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val runtime = f.c.taskRuntime
            var attempts = 0
            val ready = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            lateinit var old: (String) -> Unit
            val id = runtime.launchNovelAi("fixture", "session", f.source.id, retryable = true) { report ->
                attempts++
                if (attempts == 1) { old = report; report.designSnapshot("【研究】\nfirst"); error("fake") }
                else { ready.complete(Unit); release.await(); report.generationStatus("Step 28") }
            }
            assertEquals(DesktopTaskStatus.FAILED, f.terminal(id).status)
            assertEquals("【研究】\nfirst", runtime.imageProgress.states.value.getValue(id).designText)
            old.designSnapshot("stale after terminal")
            assertEquals("【研究】\nfirst", runtime.imageProgress.states.value.getValue(id).designText)
            val retry = assertNotNull(runtime.retryImageTask(id))
            withTimeout(10000) { ready.await() }
            assertNotEquals(id, retry)
            assertFalse(id in runtime.imageProgress.states.value)
            assertEquals("", runtime.imageProgress.states.value.getValue(retry).designText)
            old.designSnapshot("stale old job")
            release.complete(Unit); assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(retry).status)
            assertEquals("Step 28", runtime.imageProgress.states.value.getValue(retry).generationStatus)
            assertTrue(runtime.dismissImageTask(retry)); assertFalse(retry in runtime.imageProgress.states.value)
            assertEquals(f.source, ChatRepository(JsonFileStorage(f.c.appDataRoot)).getMessage(f.source.id, "session"))
            assertFalse(runtime.tasks.value.any { it.message.contains("first") })
            assertTrue(runtime.diagnostics.entries.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun `manual shared designer actual output is retained separately from task summary and entity`() = runBlocking {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val id = f.service.generateFromAssistant(f.source)
            assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(id).status)
            val process = f.c.taskRuntime.imageProgress.states.value.getValue(id)
            assertTrue(process.designText.contains("sunlit reading room, watercolor"))
            assertEquals(DesktopImageProcessPhase.GENERATION, process.phase)
            val messages = ChatRepository(JsonFileStorage(f.c.appDataRoot)).getMessages("session")
            assertEquals(f.source, messages.first { it.id == f.source.id })
            assertFalse(messages.any { it.content.contains("sunlit reading room, watercolor") })
        } finally { f.close() }
    }

    @Test fun `user stop and app cancellation retain terminal content without deleting saved output`() = runBlocking {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val saved = f.service.generateFromAssistant(f.source); f.terminal(saved)
            val before = f.c.chatRepository.getMessages("session")
            val ready = CompletableDeferred<Unit>()
            val id = f.c.taskRuntime.launchNovelAi("fixture", "session", f.source.id) { report ->
                report.designSnapshot("【研究】\nheld"); ready.complete(Unit); awaitCancellation()
            }
            withTimeout(10000) { ready.await() }
            assertTrue(f.c.taskRuntime.requestUserStop(id))
            assertEquals(DesktopTaskStatus.USER_STOPPED, f.terminal(id).status)
            assertTrue(f.c.taskRuntime.imageProgress.states.value.getValue(id).designText.contains("held"))
            assertEquals(before, f.c.chatRepository.getMessages("session"))
            val started = CompletableDeferred<Unit>()
            val cancel = f.c.taskRuntime.launchNovelAi("fixture", "session", f.source.id) { started.complete(Unit); awaitCancellation() }
            withTimeout(10000) { started.await() }; f.c.taskRuntime.closeAndDrain()
            assertEquals(DesktopTaskStatus.CANCELLED, f.terminal(cancel).status)
        } finally { f.close() }
    }
}
