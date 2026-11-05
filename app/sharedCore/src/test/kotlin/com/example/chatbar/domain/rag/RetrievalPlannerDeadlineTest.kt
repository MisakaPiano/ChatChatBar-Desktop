package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RetrievalPlannerDeadlineTest {
    @Test fun `production planner accepts twenty second result but cancels at its exact two minute deadline`() = runTest {
        var hold = false
        var cancelled = false
        val client = object : MemoryRagTextClient {
            override suspend fun completeTextStreaming(taskContext: MemoryRagTask, messages: List<ChatApiMessage>,
                modelConfig: ModelConfig, disableThinking: Boolean, isolatedTaskParameters: Boolean,
                responseFormatJson: Boolean, onDelta: (String) -> Unit): String {
                assertFalse(disableThinking); assertFalse(isolatedTaskParameters)
                if (hold) try { awaitCancellation() } finally { cancelled = true }
                delay(20_000)
                return """{"t":["fixture"],"q":["query"],"e":[]}"""
            }
        }
        val planner = RetrievalPlanner(client, StandardTestDispatcher(testScheduler))
        val model = ModelConfig.create("Synthetic", "https://example.invalid/v1", "fixture-key", "fixture")
        val slow = async { planner.plan("fixture", emptyList(), "fixture", model) }
        runCurrent(); advanceTimeBy(15_001); runCurrent(); assertFalse(slow.isCompleted)
        advanceTimeBy(4_999); runCurrent(); assertNotNull(slow.await().plan)
        hold = true
        val stalled = async { planner.plan("fixture", emptyList(), "fixture", model) }
        runCurrent(); advanceTimeBy(119_999); runCurrent(); assertFalse(stalled.isCompleted); assertFalse(cancelled)
        advanceTimeBy(1); runCurrent()
        val result = stalled.await()
        assertNull(result.plan); assertEquals("timeout after 120000ms", result.failureReason); assertTrue(cancelled)
    }
}
