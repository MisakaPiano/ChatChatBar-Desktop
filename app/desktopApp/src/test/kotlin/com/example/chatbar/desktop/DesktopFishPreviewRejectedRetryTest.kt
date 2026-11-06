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

class DesktopFishPreviewRejectedRetryTest {
    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("preview-rejected-retry-")
        val store = DesktopFishPreviewStore(directory)
        val accounts = DesktopAccountStore(InMemoryDesktopSecretStore())
        val first = accounts.saveAccount(DesktopAccountService.FISH, null, "Synthetic first", "Fish", "", "synthetic-first")
        val next = accounts.saveAccount(DesktopAccountService.FISH, null, "Synthetic next", "Fish", "", "synthetic-next")
        init { accounts.select(DesktopAccountService.FISH, first) }
        val rows = CopyOnWriteArrayList<DesktopUsageRecord>()
        val diagnostics = DesktopTransportDiagnosticsOwner(usageSink = { rows += it })
        val server = MockWebServer().also { it.start() }
        val transport = DesktopFishPreviewTransport(DesktopFishAudioCredentials(accounts), OkHttpClient(),
            server.url("/").toString().trimEnd('/'), diagnostics)
        fun audio() = MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3()))
        fun latest() = rows.groupBy { it.id }.values.map { it.maxBy { row -> row.revision } }
        override fun close() {
            runBlocking { transport.closeAndDrain() }
            server.shutdown()
            if (Files.exists(directory)) store.close()
        }
    }

    @Test fun `only explicit temporary rejections retry with the original credential payload and separate attempts`() = runBlocking {
        for (status in listOf(408, 425, 429, 500, 503)) Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0").setBody("synthetic rejection"))
            f.server.enqueue(f.audio())
            val waits = mutableListOf<Long>()
            val clip = f.transport.generate("reference", "  original🙂  ", "s2.1-pro-free", f.store,
                onRetry = { attempt, wait ->
                    assertEquals(1, attempt); waits += wait
                    f.accounts.select(DesktopAccountService.FISH, f.next)
                })
            assertTrue(Files.size(clip) > 0)
            assertEquals(2, f.server.requestCount)
            assertTrue(waits.single() in 1_000L..1_500L)
            val first = assertNotNull(f.server.takeRequest(1, TimeUnit.SECONDS))
            val second = assertNotNull(f.server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-first", first.getHeader("Authorization"))
            assertEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
            val rows = f.latest()
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.accountId == f.first })
            assertEquals(setOf(status, 200), rows.map { it.media?.httpStatus }.toSet())
            assertEquals(1, rows.count { it.media?.outputAudioBytes != null })
            assertEquals(2, f.diagnostics.entries.value.single().media?.attempt)
        }
    }

    @Test fun `three rejected attempts exhaust once without writing audio or retrying a fourth POST`() = runBlocking {
        Fixture().use { f ->
            repeat(3) { f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")) }
            f.server.enqueue(f.audio())
            val error = assertFailsWith<DesktopFishPreviewHttpFailure> {
                f.transport.generate("reference", "hello", "s2.1-pro-free", f.store)
            }
            assertEquals(503, error.status); assertEquals(3, error.attempts)
            assertEquals(3, f.server.requestCount)
            assertEquals(3, f.latest().size)
            assertTrue(f.latest().all { it.media?.outputAudioBytes == null })
            assertEquals(0L, Files.list(f.directory).use { it.count() })
        }
    }

    @Test fun `redirect authentication accepted bad media and uncertain connection never replay`() = runBlocking {
        for (status in listOf(301, 401, 403, 200)) Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0")
                .setHeader("Location", f.server.url("/other")).setHeader("Content-Type", "audio/mpeg").setBody("not mp3"))
            f.server.enqueue(f.audio())
            assertFails { f.transport.generate("reference", "hello", "s2.1-pro-free", f.store) }
            assertEquals(1, f.server.requestCount)
            assertEquals(0L, Files.list(f.directory).use { it.count() })
        }
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            f.server.enqueue(f.audio())
            assertFails { f.transport.generate("reference", "hello", "s2.1-pro-free", f.store) }
            assertEquals(1, f.server.requestCount)
            assertNull(f.latest().single().media?.httpStatus)
        }
    }

    @Test fun `cancellation during Retry After drains and releases the shared permit without a new POST`() = runBlocking {
        Fixture().use { f ->
            val permits = DesktopFishTtsAdmission.slots.availablePermits
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            val waiting = CompletableDeferred<Long>()
            val job = launch { f.transport.generate("reference", "hello", "s2.1-pro-free", f.store,
                onRetry = { _, wait -> waiting.complete(wait) }) }
            assertTrue(withTimeout(5000) { waiting.await() } >= 60_000)
            assertEquals(permits - 1, DesktopFishTtsAdmission.slots.availablePermits)
            withTimeout(5000) { job.cancelAndJoin() }
            assertEquals(permits, DesktopFishTtsAdmission.slots.availablePermits)
            assertEquals(1, f.server.requestCount)
            assertEquals(DesktopUsageOutcome.CANCELLED, f.latest().single().outcome)
        }
    }

    @Test fun `picker retry remains visible and closing it stops the waiting request`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "60"))
            val runtime = DesktopFishPreviewRuntime(f.transport, PreviewOutputFake(), stores = { f.store })
            val session = runtime.openSession()
            try {
                session.generate("reference", "hello")
                withTimeout(5000) { while (!session.state.value.message.orEmpty().contains("重试")) delay(10) }
                assertTrue(session.state.value.busy)
                session.requestClose()
                withTimeout(5000) { session.closeAndDrain() }
                assertEquals(1, f.server.requestCount)
            } finally { runtime.closeAndDrain() }
        }
    }
}
