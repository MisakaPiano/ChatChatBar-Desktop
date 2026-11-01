package com.example.chatbar.desktop

import com.example.chatbar.domain.chat.PromptCacheUsage
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class DesktopProviderUsageTest {
    private fun record(request: String = UUID.randomUUID().toString(), attempt: Int = 1) = DesktopUsageRecord(
        UUID.randomUUID().toString(), request, attempt, 1, DesktopUsageSource.CHAT,
        taskId = "logical-task", sessionId = "actual-session", cardId = "actual-card", modelId = "model",
        modelName = "synthetic-model", startedAt = 10, updatedAt = 10)

    @Test fun `cumulative reports replace one attempt while missing partial zero and cache retain their meaning`() {
        val first = record().copy(counters = DesktopUsageCounters(prompt = Int.MAX_VALUE.toLong(), cacheHit = 100))
        val revised = first.copy(revision = 2, counters = first.counters.merge(PromptCacheUsage(completionTokens = 0)))
        val retry = record(first.requestId, 2).copy(outcome = DesktopUsageOutcome.CANCELLED,
            counters = DesktopUsageCounters(prompt = Int.MAX_VALUE.toLong(), completion = 5, cacheHit = 80))
        val unknown = record().copy(outcome = DesktopUsageOutcome.FAILED)
        val result = summarizeDesktopUsage(listOf(first, revised, revised, retry, unknown))
        assertEquals(2, result.requests); assertEquals(3, result.attempts)
        assertEquals(2L * Int.MAX_VALUE, result.prompt.value)
        assertEquals(5L, result.completion.value); assertEquals(2, result.completion.reportedAttempts)
        assertEquals(1, result.completion.unreportedAttempts)
        assertEquals(180L, result.cacheHit.value) // not added to prompt count
        assertNull(result.cacheWrite.value); assertEquals(3, result.cacheWrite.unreportedAttempts)
        assertEquals(revised.counters, revised.counters.merge(PromptCacheUsage(promptTokens = -1)))
        assertNull(summarizeDesktopUsage(listOf(unknown)).prompt.value)
    }

    @Test fun `overflow is visible instead of wrapping and conflicting equal revisions refuse`() {
        assertFailsWith<ArithmeticException> {
            summarizeDesktopUsage(listOf(record().copy(counters = DesktopUsageCounters(prompt = Long.MAX_VALUE)),
                record().copy(counters = DesktopUsageCounters(prompt = 1))))
        }
        val one = record()
        assertFailsWith<IllegalStateException> { summarizeDesktopUsage(listOf(one, one.copy(outcome = DesktopUsageOutcome.FAILED))) }
    }

    @Test fun `query uses real parent attribution and explicit half-open time boundaries`() {
        val value = record().copy(source = DesktopUsageSource.MOMENTS, taskId = "moment-task", sessionId = "actual-session", startedAt = 10)
        assertTrue(DesktopUsageQuery(fromInclusive = 10, untilExclusive = 11, source = DesktopUsageSource.MOMENTS,
            sessionId = "actual-session", modelId = value.modelId).accepts(value))
        assertFalse(DesktopUsageQuery(untilExclusive = 10).accepts(value))
        assertFalse(DesktopUsageQuery(sessionId = "moments:GENERATE").accepts(value))
        assertFalse(DesktopUsageQuery(provider = "invented-provider").accepts(value))
    }

    @Test fun `numeric ledger reopens rejects attribution mutation and does not revert a newer cancelled report`() = runBlocking {
        val root = Files.createTempDirectory("usage-ledger-"); val gate = DesktopDataOperationCoordinator()
        try {
            val store = DesktopProviderUsageStore(root, gate)
            assertTrue(store.records().isEmpty()); assertFalse(Files.exists(root.resolve("diagnostics")))
            val started = record(); store.save(started)
            val finished = started.copy(revision = 3, updatedAt = 20, outcome = DesktopUsageOutcome.CANCELLED,
                counters = DesktopUsageCounters(prompt = 0, completion = 9))
            store.save(finished); store.save(started); store.save(finished)
            val reopened = DesktopProviderUsageStore(root, gate)
            assertEquals(listOf(finished), reopened.records())
            assertEquals(0L, summarizeDesktopUsage(reopened.records()).prompt.value)
            assertFailsWith<IllegalStateException> { store.save(finished.copy(revision = 4, sessionId = "different-session")) }
            assertEquals(listOf(finished), reopened.records())
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }
}
