package com.example.chatbar.utils

import com.example.chatbar.domain.chat.PromptCacheUsage
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage
import com.example.chatbar.domain.prompt.AiTaskFailureKind
import org.junit.Assert.*
import org.junit.Test

class AiRequestLogTest {
    private fun start(session: String) = DebugLogManager.startRequest(
        session, "fixture", "https://example.test/v1", "{}", "", emptyList(),
        AiTaskContext(AiTaskKind.FORMAT_CARD, taskId = session)
    )

    @Test
    fun concurrentRequestsWithSameSessionRemainIndependent() {
        val session = "concurrent-log-fixture"
        DebugLogManager.clearLogs(session)
        val first = start(session)
        val second = start(session)
        DebugLogManager.appendResponseChunk(first, "", "one")
        DebugLogManager.appendResponseChunk(second, "", "two")
        DebugLogManager.completeRequest(first)
        DebugLogManager.recordPromptCacheUsage(second, PromptCacheUsage(promptTokens = 12, completionTokens = 4))
        DebugLogManager.completeRequest(second)
        val entries = DebugLogManager.logs.value.associateBy { it.id }
        assertEquals("one", entries.getValue(first).rawAiOutputText)
        assertEquals("two", entries.getValue(second).rawAiOutputText)
        assertNull(entries.getValue(first).apiPromptTokens)
        assertNull(entries.getValue(first).apiCompletionTokens)
        assertEquals(12, entries.getValue(second).apiPromptTokens)
        assertEquals(4, entries.getValue(second).apiCompletionTokens)
        DebugLogManager.clearLogs(session)
    }

    @Test
    fun smallDeltasAccumulateEstimatesAndLongLogsAreBounded() {
        val session = "bounded-log-fixture"
        val id = start(session)
        repeat(20) { DebugLogManager.appendResponseChunk(id, "", "a") }
        assertEquals(8, DebugLogManager.logs.value.single { it.id == id }.estimatedCompletionTokens)
        DebugLogManager.appendResponseChunk(id, "", "x".repeat(100_000))
        val entry = DebugLogManager.logs.value.single { it.id == id }
        assertTrue(entry.logTruncated)
        assertTrue(entry.rawAiOutputText.length <= 65_536)
        DebugLogManager.clearLogs(session)
    }

    @Test
    fun repairMarksPrecedingInvalidResponseWithoutChangingItsRawText() {
        val session = "repair-log-fixture"
        val original = start(session)
        DebugLogManager.appendResponseChunk(original, "", "invalid JSON fixture")
        DebugLogManager.completeRequest(original)
        val repair = DebugLogManager.startRequest(
            session, "fixture", "https://example.test", "{}", "", emptyList(),
            AiTaskContext(AiTaskKind.FORMAT_CARD, AiTaskStage.REPAIR, taskId = session)
        )
        val failed = DebugLogManager.logs.value.single { it.id == original }
        assertEquals(AiTaskFailureKind.FORMAT, failed.failureKind)
        assertEquals("invalid JSON fixture", failed.rawAiOutputText)
        assertFalse(DebugLogManager.logs.value.single { it.id == repair }.isCompleted)
        DebugLogManager.clearLogs(session)
    }

    @Test
    fun credentialsAndImageBytesAreRemovedFromDiagnosticCopy() {
        val raw = """{"api_key":"test-secret","authorization":"Bearer another-secret","image":"data:image/png;base64,aW1hZ2U="}"""
        val clean = DebugLogManager.sanitizeForDisplay(raw)
        assertFalse(clean.contains("test-secret"))
        assertFalse(clean.contains("another-secret"))
        assertFalse(clean.contains("aW1hZ2U="))
        assertTrue(clean.contains("<omitted>"))
    }
}
