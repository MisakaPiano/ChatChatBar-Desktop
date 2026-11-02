package com.example.chatbar.desktop

import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMediaUsageCustodyTest {
    private fun text() = DesktopUsageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1,
        DesktopUsageSource.CHAT, sessionId = "session", modelId = "model", modelName = "model", startedAt = 1, updatedAt = 1,
        outcome = DesktopUsageOutcome.COMPLETED, counters = DesktopUsageCounters(prompt = 7, completion = 0))
    private fun media(input: Long? = 3, duration: Long? = 1000, bytes: Long? = 64) = text().copy(
        counters = DesktopUsageCounters(), schemaVersion = 2,
        media = DesktopUsageMedia(DesktopUsageMediaKind.FISH_TTS, input, duration, bytes, 200))

    @Test fun `original schema1 bytes coexist with media2 on reopen without mixing units or rewriting old rows`() = runBlocking {
        val root = Files.createTempDirectory("media-custody-"); val gate = DesktopDataOperationCoordinator()
        try {
            val store = DesktopProviderUsageStore(root, gate)
            val old = text(); store.save(old)
            val oldFile = root.resolve("diagnostics/provider-usage/${old.id}.json")
            val original = Files.readAllBytes(oldFile)
            assertFalse(Json.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject.containsKey("media"))
            val observed = media(); store.save(observed)
            val reopened = DesktopProviderUsageStore(root, gate)
            assertEquals(setOf(old, observed), reopened.records().toSet())
            assertContentEquals(original, Files.readAllBytes(oldFile))
            val totals = summarizeDesktopUsage(reopened.records())
            assertEquals(2, totals.attempts); assertEquals(7L, totals.prompt.value)
            assertEquals(1, totals.prompt.reportedAttempts); assertEquals(0, totals.prompt.unreportedAttempts)
            assertEquals(0L, totals.completion.value)
            assertEquals(3L, totals.media.single().inputTextCodePoints.value)
            assertEquals(1000L, totals.media.single().outputAudioDurationMs.value)
            assertEquals(64L, totals.media.single().outputAudioBytes.value)
            assertTrue(DesktopUsageQuery(textOnly = true).accepts(old)); assertFalse(DesktopUsageQuery(textOnly = true).accepts(observed))
            assertFalse(DesktopUsageQuery(mediaKind = DesktopUsageMediaKind.FISH_TTS).accepts(old))
            assertTrue(DesktopUsageQuery(mediaKind = DesktopUsageMediaKind.FISH_TTS, sessionId = "session").accepts(observed))
        } finally { gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }

    @Test fun `unknown and measured zero remain distinct while cumulative media revisions replace one attempt`() {
        val missing = media(null, null, null)
        val zero = media(0, 0, 0)
        val latest = zero.copy(revision = 2, media = zero.media!!.copy(outputAudioBytes = 9))
        val totals = summarizeDesktopUsage(listOf(missing, zero, latest))
        assertEquals(2, totals.attempts)
        assertEquals(0, totals.prompt.unreportedAttempts)
        val metrics = totals.media.single()
        assertEquals(0L, metrics.inputTextCodePoints.value)
        assertEquals(1, metrics.inputTextCodePoints.reportedAttempts); assertEquals(1, metrics.inputTextCodePoints.unreportedAttempts)
        assertEquals(0L, metrics.outputAudioDurationMs.value); assertEquals(9L, metrics.outputAudioBytes.value)
        assertFailsWith<ArithmeticException> { summarizeDesktopUsage(listOf(media(Long.MAX_VALUE), media(1))) }
    }

    @Test fun `mixed token billing negative measurements or changed media identity refuse writes and preserve original bytes`() = runBlocking {
        val root = Files.createTempDirectory("media-refusal-"); val gate = DesktopDataOperationCoordinator()
        try {
            val store = DesktopProviderUsageStore(root, gate); val original = media(); store.save(original)
            val path = root.resolve("diagnostics/provider-usage/${original.id}.json"); val bytes = Files.readAllBytes(path)
            val invalid = listOf(
                original.copy(revision = 2, counters = DesktopUsageCounters(prompt = 1)),
                original.copy(revision = 2, schemaVersion = 1),
                original.copy(revision = 2, media = original.media!!.copy(inputTextCodePoints = 4)),
                original.copy(revision = 2, media = original.media!!.copy(outputAudioBytes = -1)),
                original.copy(revision = 2, media = original.media!!.copy(httpStatus = 999)),
            )
            for (row in invalid) {
                assertFails { store.save(row) }
                assertContentEquals(bytes, Files.readAllBytes(path))
            }
            assertEquals(listOf(original), store.records())
        } finally { gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }
}
