package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.voice.FishAudioApiException
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlin.test.*

class DesktopFishRejectedRetryTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("fish-rejection-")
        val server = MockWebServer().also { it.start() }
        val credentials = DesktopFishAudioCredentials(InMemoryDesktopSecretStore()).also { it.save("synthetic-first") }
        val transport = DesktopFishVoiceTransport(credentials, DesktopVoiceAudioStore(root), OkHttpClient(),
            server.url("/").toString().trimEnd('/'), today = { LocalDate.of(2026, 11, 1) })
        fun audio() = MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3()))
        override fun close() {
            runBlocking { transport.closeAndDrain() }
            server.shutdown(); root.toFile().deleteRecursively()
        }
    }

    @Test fun `known temporary rejection retries with fresh attempt same payload and frozen credential`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1").setBody("synthetic rejection"))
            f.server.enqueue(f.audio())
            val waits = mutableListOf<Long>()
            val audio = f.transport.synthesize("s2.1-pro-free", "reference", "hello", "session", "voice",
                onRetry = { attempt, wait ->
                    assertEquals(1, attempt); waits += wait
                    f.credentials.save("synthetic-changed-after-admission")
                })
            assertTrue(audio.byteLength > 0)
            assertEquals(2, f.server.requestCount)
            assertTrue(waits.single() in 1_000L..1_500L)
            val first = assertNotNull(f.server.takeRequest(1, TimeUnit.SECONDS))
            val second = assertNotNull(f.server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
        }
    }

    @Test fun `permanent rejection and accepted broken body never replay synthesis`() = runBlocking {
        for (accepted in listOf(false, true)) Fixture().use { f ->
            f.server.enqueue(if (accepted) MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("not mp3")
                else MockResponse().setResponseCode(401).setBody("synthetic unauthorized"))
            val error = assertFails { f.transport.synthesize("s2.1-pro-free", "reference", "hello", "session", "voice") }
            if (!accepted) assertEquals(401, assertIs<FishAudioApiException>(error).statusCode)
            assertEquals(1, f.server.requestCount)
            Files.walk(f.root).use { files -> assertEquals(0, files.filter { it.toString().endsWith(".mp3") }.count()) }
        }
    }

    @Test fun `cancel during rejected-response backoff stops before another POST`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "60"))
            val waiting = CompletableDeferred<Unit>()
            val job = launch { f.transport.synthesize("s2.1-pro-free", "reference", "hello", "session", "voice",
                onRetry = { _, _ -> waiting.complete(Unit) }) }
            withTimeout(5000) { waiting.await() }
            job.cancelAndJoin()
            assertEquals(1, f.server.requestCount)
            assertTrue(job.isCancelled)
        }
    }

    @Test fun `connection lost after POST has uncertain acceptance and is never retried`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            f.server.enqueue(f.audio())
            assertFails { f.transport.synthesize("s2.1-pro-free", "reference", "hello", "session", "voice") }
            assertEquals(1, f.server.requestCount)
        }
    }
}
