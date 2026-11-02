package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.voice.FishAudioTtsModels
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlin.test.*

class DesktopFishMediaUsageTest {
    private class Fixture(sinkOverride: ((DesktopUsageRecord) -> Unit)? = null) : AutoCloseable {
        val root = Files.createTempDirectory("fish-media-usage-")
        val gate = DesktopDataOperationCoordinator()
        val store = DesktopProviderUsageStore(root, gate)
        val usage = DesktopProviderUsageOwner(store)
        val diagnostics = DesktopTransportDiagnosticsOwner(usageSink = sinkOverride ?: usage::submit)
        val accounts = DesktopAccountStore(InMemoryDesktopSecretStore())
        val accountA = accounts.saveAccount(DesktopAccountService.FISH, null, "Synthetic A", "Fish", "", "synthetic-key-a")
        val accountB = accounts.saveAccount(DesktopAccountService.FISH, null, "Synthetic B", "Fish", "", "synthetic-key-b")
        init { accounts.select(DesktopAccountService.FISH, accountA) }
        val credentials = DesktopFishAudioCredentials(accounts)
        val server = MockWebServer().also { it.start() }
        val transport = DesktopFishVoiceTransport(credentials, DesktopVoiceAudioStore(root), OkHttpClient(),
            server.url("/").toString().trimEnd('/'), today = { LocalDate.of(2026, 11, 1) }, diagnostics = diagnostics)
        fun audio() = MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3()))
        suspend fun rows(): List<DesktopUsageRecord> { usage.closeAndDrain(); return store.records() }
        override fun close() {
            runBlocking { transport.closeAndDrain(); usage.closeAndDrain(); gate.closeAndDrain() }
            server.shutdown(); check(root.toFile().deleteRecursively())
        }
    }

    @Test fun `actual TTS rejection retry keeps frozen account one request and separate local units after reopen`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1").setBody("synthetic-private-rejection"))
            f.server.enqueue(f.audio())
            val text = "  synthetic-private-text🙂  "
            val parent = DesktopUsageContext(DesktopUsageSource.CHAT, taskId = "batch", sessionId = "session", cardId = "card", subjectId = "message")
            val result = withContext(DesktopUsageParent(parent) + f.credentials.captureCall()) {
                f.transport.synthesize(FishAudioTtsModels.S2_1_PRO_FREE, "synthetic-private-reference", text, "session", "voice",
                    onRetry = { _, _ -> f.accounts.select(DesktopAccountService.FISH, f.accountB) })
            }
            assertEquals(2, f.server.requestCount)
            val first = assertNotNull(f.server.takeRequest(1, TimeUnit.SECONDS))
            val second = assertNotNull(f.server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-key-a", first.getHeader("Authorization"))
            assertEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
            val firstBody = first.body.readUtf8()
            assertEquals(firstBody, second.body.readUtf8())
            val wireText = Json.parseToJsonElement(firstBody).jsonObject.getValue("text").jsonPrimitive.content
            assertEquals(text.trim(), wireText)
            val rows = f.rows().sortedBy { it.attempt }
            assertEquals(2, rows.size); assertEquals(1, rows.map { it.requestId }.distinct().size)
            assertEquals(listOf(DesktopUsageOutcome.RETRIED, DesktopUsageOutcome.COMPLETED), rows.map { it.outcome })
            assertTrue(rows.all { it.schemaVersion == 2 && it.accountId == f.accountA && it.taskId == "batch" &&
                it.sessionId == "session" && it.cardId == "card" && it.subjectId == "message" && it.counters == DesktopUsageCounters() })
            assertEquals(listOf(429, 200), rows.map { it.media?.httpStatus })
            assertEquals(wireText.codePointCount(0, wireText.length).toLong(), rows.first().media?.inputTextCodePoints)
            assertNull(rows.first().media?.outputAudioBytes)
            assertEquals(result.byteLength, rows.last().media?.outputAudioBytes)
            assertEquals(result.durationMs, rows.last().media?.outputAudioDurationMs)
            assertEquals(rows.toSet(), DesktopProviderUsageStore(f.root, f.gate).records().toSet())
            val totals = summarizeDesktopUsage(rows)
            assertEquals(0, totals.prompt.unreportedAttempts); assertNull(totals.prompt.value)
            assertEquals(result.durationMs, totals.media.single().outputAudioDurationMs.value)
            assertEquals(1, totals.media.single().outputAudioDurationMs.unreportedAttempts)
            val log = f.diagnostics.entries.value.single()
            assertEquals(2, log.media?.attempt); assertEquals(200, log.media?.httpStatus)
            assertEquals(DesktopTaskStatus.COMPLETED, log.status)
            assertNull(log.serializedRequestBody); assertTrue(log.chunks.isEmpty()); assertTrue(log.outputText.isEmpty())
            val stored = Files.list(f.root.resolve("diagnostics/provider-usage")).use { paths -> paths.toList().joinToString { Files.readString(it) } }
            for (sensitiveValue in listOf(text.trim(), "synthetic-private-reference", "synthetic-key-a", "synthetic-private-rejection", result.path)) {
                assertFalse(stored.contains(sensitiveValue)); assertFalse(log.toString().contains(sensitiveValue))
            }
        }
    }

    @Test fun `actual rejected or accepted broken audio records HTTP without replay or invented output units`() = runBlocking {
        for (accepted in listOf(false, true)) Fixture().use { f ->
            f.server.enqueue(if (accepted) MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("synthetic-private-invalid-mp3")
                else MockResponse().setResponseCode(401).setBody("synthetic-private-rejection"))
            assertFails { f.transport.synthesize(FishAudioTtsModels.S2_1_PRO_FREE, "reference", "hello", "session", "voice") }
            assertEquals(1, f.server.requestCount)
            val row = f.rows().single()
            assertEquals(DesktopUsageOutcome.FAILED, row.outcome)
            assertEquals(if (accepted) 200 else 401, row.media?.httpStatus)
            assertNull(row.media?.outputAudioDurationMs); assertNull(row.media?.outputAudioBytes)
            assertEquals(DesktopUsageCounters(), row.counters)
        }
    }

    @Test fun `diagnostic clear and cancellation during actual retry wait keep numeric rejected attempt without next POST`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "60"))
            val waiting = CompletableDeferred<Unit>()
            val job = launch { f.transport.synthesize(FishAudioTtsModels.S2_1_PRO_FREE, "reference", "hello", "session", "voice",
                onRetry = { _, _ -> waiting.complete(Unit) }) }
            withTimeout(5000) { waiting.await() }
            f.diagnostics.clear("session"); job.cancelAndJoin()
            assertEquals(1, f.server.requestCount); assertTrue(f.diagnostics.entries.value.isEmpty())
            val row = f.rows().single()
            assertEquals(DesktopUsageOutcome.CANCELLED, row.outcome); assertEquals(503, row.media?.httpStatus)
            assertNull(row.media?.outputAudioBytes)
        }
    }

    @Test fun `actual dispatched cancellation drains its callback before recording terminal unknown response`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch { f.transport.synthesize(FishAudioTtsModels.S2_1_PRO_FREE, "reference", "hello", "session", "voice") }
            withContext(Dispatchers.IO) { assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(5000) { job.cancelAndJoin() }
            assertEquals(1, f.server.requestCount)
            val row = f.rows().single()
            assertEquals(DesktopUsageOutcome.CANCELLED, row.outcome)
            assertNull(row.media?.httpStatus); assertNull(row.media?.outputAudioDurationMs)
            assertTrue(f.diagnostics.entries.value.single().cancellationObserved)
        }
    }

    @Test fun `profile refusal and closed dispatch produce no physical request log or usage row`() = runBlocking {
        Fixture().use { f ->
            assertFails { f.transport.synthesize("synthetic-paid-model", "reference", "hello", "session", "voice") }
            f.transport.closeAndDrain()
            assertFails { f.transport.synthesize(FishAudioTtsModels.S2_1_PRO_FREE, "reference", "hello", "session", "voice") }
            assertEquals(0, f.server.requestCount); assertTrue(f.diagnostics.entries.value.isEmpty()); assertTrue(f.rows().isEmpty())
        }
    }

    @Test fun `numeric capture failure stays visible without failing or replaying the original successful TTS`() = runBlocking {
        Fixture { error("synthetic-private-statistics-failure") }.use { f ->
            f.server.enqueue(f.audio())
            val audio = f.transport.synthesize(FishAudioTtsModels.S2_1_PRO_FREE, "reference", "hello", "session", "voice")
            assertTrue(audio.byteLength > 0); assertEquals(1, f.server.requestCount)
            val log = f.diagnostics.entries.value.single()
            assertEquals(DesktopTaskStatus.COMPLETED, log.status)
            assertNotNull(log.error); assertFalse(log.error!!.contains("synthetic-private-statistics-failure"))
            assertEquals(audio.byteLength, log.media?.audioBytes)
            assertTrue(f.rows().isEmpty())
        }
    }
}
