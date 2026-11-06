package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlin.test.*

class DesktopFishPreviewUsageTest {
    private class Fixture(sinkFailure: Boolean = false) : AutoCloseable {
        val directory = Files.createTempDirectory("fish-preview-usage-")
        val store = DesktopFishPreviewStore(directory)
        val rows = CopyOnWriteArrayList<DesktopUsageRecord>()
        val diagnostics = DesktopTransportDiagnosticsOwner(usageSink = {
            if (sinkFailure) error("private-sink-failure") else rows += it
        })
        val accounts = DesktopAccountStore(InMemoryDesktopSecretStore())
        val a = accounts.saveAccount(DesktopAccountService.FISH, null, "Synthetic A", "Fish", "", "synthetic-key-a")
        val b = accounts.saveAccount(DesktopAccountService.FISH, null, "Synthetic B", "Fish", "", "synthetic-key-b")
        init { accounts.select(DesktopAccountService.FISH, a) }
        val credentials = DesktopFishAudioCredentials(accounts)
        val server = MockWebServer().also { it.start() }
        val transport = DesktopFishPreviewTransport(credentials, OkHttpClient(), server.url("/").toString().trimEnd('/'), diagnostics)
        fun audio() = MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3()))
        fun latest() = rows.groupBy { it.id }.values.map { values -> values.maxBy { it.revision } }
        override fun close() {
            runBlocking { transport.closeAndDrain() }
            server.shutdown()
            if (Files.exists(directory)) store.close()
        }
    }

    @Test fun `generated picker preview uses frozen account and local units while cached replay adds no call or row`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(f.audio())
            val frozen = f.transport.captureCall()
            f.accounts.select(DesktopAccountService.FISH, f.b)
            val text = "  private-preview🙂  "
            val clip = withContext(frozen) { f.transport.generate("private-reference", text, "s2.1-pro-free", f.store) }
            val wire = assertNotNull(f.server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-key-a", wire.getHeader("Authorization"))
            val row = f.latest().single()
            assertEquals(f.a, row.accountId); assertEquals(DesktopUsageSource.VOICE_PREVIEW, row.source)
            assertNull(row.sessionId); assertNull(row.cardId); assertNull(row.taskId)
            assertEquals(DesktopUsageOutcome.COMPLETED, row.outcome)
            assertEquals(DesktopUsageCounters(), row.counters)
            assertEquals(200, row.media?.httpStatus)
            assertEquals(text.trim().codePointCount(0, text.trim().length).toLong(), row.media?.inputTextCodePoints)
            assertEquals(Files.size(clip), row.media?.outputAudioBytes)
            assertTrue(assertNotNull(row.media?.outputAudioDurationMs) > 0)
            for (privateValue in listOf(text.trim(), "private-reference", "synthetic-key-a", clip.toString())) {
                assertFalse(row.toString().contains(privateValue)); assertFalse(f.diagnostics.entries.value.single().toString().contains(privateValue))
            }
        }
        Fixture().use { f ->
            f.server.enqueue(f.audio())
            val output = PreviewOutputFake()
            val runtime = DesktopFishPreviewRuntime(f.transport, output, stores = { f.store })
            val session = runtime.openSession(DesktopUsageContext(DesktopUsageSource.CHARACTER_CARD,
                cardId = "actual-card", editorSessionId = "actual-editor", personId = "actual-person"))
            try {
                session.generate("voice", "first")
                await { session.state.value.playing }
                val origin = f.latest().single()
                assertEquals(DesktopUsageSource.CHARACTER_CARD, origin.source)
                assertEquals("actual-card", origin.cardId); assertEquals("actual-editor", origin.editorSessionId)
                assertEquals("actual-person", origin.personId); assertNull(origin.sessionId); assertNull(origin.taskId)
                session.stop(); await { !session.state.value.playing && !session.state.value.busy }
                val snapshots = f.rows.toList()
                session.generate("voice", "changed and not sent")
                await { session.state.value.playing && session.state.value.cached }
                assertEquals(1, f.server.requestCount); assertEquals(snapshots, f.rows.toList())
            } finally { runtime.closeAndDrain() }
        }
    }

    @Test fun `preview permanent rejection broken media and closed admission keep observed HTTP without replay or invented units`() = runBlocking {
        for (code in listOf(401, 403, 200)) Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "audio/mpeg")
                .setHeader("Retry-After", "0").setBody("private-invalid-audio"))
            assertFails { f.transport.generate("voice", "hello", "s2.1-pro-free", f.store) }
            assertEquals(1, f.server.requestCount)
            val row = f.latest().single()
            assertEquals(DesktopUsageOutcome.FAILED, row.outcome); assertEquals(code, row.media?.httpStatus)
            assertNull(row.media?.outputAudioDurationMs); assertNull(row.media?.outputAudioBytes)
        }
        Fixture().use { f ->
            assertFails { f.transport.generate("voice", "hello", "paid-model", f.store) }
            f.transport.closeAndDrain()
            assertFails { f.transport.generate("voice", "hello", "s2.1-pro-free", f.store) }
            assertEquals(0, f.server.requestCount); assertTrue(f.rows.isEmpty()); assertTrue(f.diagnostics.entries.value.isEmpty())
        }
    }

    @Test fun `dispatched preview cancellation drains before terminal capture and log clear does not erase usage`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch { f.transport.generate("voice", "hello", "s2.1-pro-free", f.store) }
            assertNotNull(withContext(Dispatchers.IO) { f.server.takeRequest(5, TimeUnit.SECONDS) })
            f.diagnostics.clear("voice-preview")
            withTimeout(5000) { job.cancelAndJoin() }
            val row = f.latest().single()
            assertEquals(DesktopUsageOutcome.CANCELLED, row.outcome); assertNull(row.media?.httpStatus)
            assertNull(row.media?.outputAudioBytes); assertTrue(f.diagnostics.entries.value.isEmpty())
            assertEquals(1, f.server.requestCount)
        }
        Fixture(sinkFailure = true).use { f ->
            f.server.enqueue(f.audio())
            val clip = f.transport.generate("voice", "hello", "s2.1-pro-free", f.store)
            assertTrue(Files.size(clip) > 0); assertEquals(1, f.server.requestCount)
            assertEquals(DesktopTaskStatus.COMPLETED, f.diagnostics.entries.value.single().status)
            assertNotNull(f.diagnostics.entries.value.single().error); assertTrue(f.rows.isEmpty())
        }
    }

    @Test fun `official sample download and cached sample playback never create a synthesis usage row`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(f.audio())
            val samples = DesktopFishSampleTransport(OkHttpClient()) { f.server.url("/sample.mp3") }
            val runtime = DesktopFishPreviewRuntime(f.transport, PreviewOutputFake(), stores = { f.store }, samples = samples)
            val session = runtime.openSession()
            try {
                val url = DesktopFishSamplePolicy.ORIGIN + "/task/" + "a".repeat(32) + ".mp3"
                session.officialSample("voice", url); await { session.state.value.playing }
                val wire = assertNotNull(f.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("GET", wire.method); assertNull(wire.getHeader("Authorization"))
                session.stop(); await { !session.state.value.playing && !session.state.value.busy }
                session.officialSample("voice", url); await { session.state.value.playing && session.state.value.cached }
                assertEquals(1, f.server.requestCount); assertTrue(f.rows.isEmpty()); assertTrue(f.diagnostics.entries.value.isEmpty())
            } finally { runtime.closeAndDrain() }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
}
