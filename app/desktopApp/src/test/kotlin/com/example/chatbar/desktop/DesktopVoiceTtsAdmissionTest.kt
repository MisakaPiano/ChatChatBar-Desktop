package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlin.test.*

class DesktopVoiceTtsAdmissionTest {
    @Test fun `formal and picker TTS share five real request permits until body drain`() = runBlocking {
        val server = MockWebServer()
        val entered = CountDownLatch(5); val release = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                check(release.await(8, TimeUnit.SECONDS))
                return MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3()))
            }
        }
        server.start()
        val credentials = DesktopFishAudioCredentials(InMemoryDesktopSecretStore()).also { it.save("synthetic-only") }
        val store = DesktopVoiceAudioStore(Files.createTempDirectory("s7-mixed-slots-"))
        val formal = DesktopFishVoiceTransport(credentials, store, OkHttpClient(), server.url("/").toString().trimEnd('/'), { LocalDate.of(2026,10,19) })
        val preview = DesktopFishPreviewTransport(credentials, OkHttpClient(), server.url("/").toString().trimEnd('/'))
        val previews = List(3) { DesktopFishPreviewStore() }
        val jobs = (0..2).map { n -> async(Dispatchers.IO) {
            formal.synthesize("s2.1-pro-free", "reference", "synthetic", "session", "artifact-$n")
        } } + (0..2).map { n -> async(Dispatchers.IO) {
            preview.generate("reference", "synthetic", "s2.1-pro-free", previews[n]); null
        } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertEquals(5, server.requestCount)
            assertEquals(0, DesktopFishTtsAdmission.slots.availablePermits)
            release.countDown()
            val results = withTimeout(8000) { jobs.awaitAll() }
            assertEquals(6, server.requestCount); assertEquals(5, DesktopFishTtsAdmission.slots.availablePermits)
            results.filterNotNull().forEach { store.deleteIfOwned(it.path) }
        } finally {
            release.countDown(); jobs.forEach { it.cancelAndJoin() }
            formal.closeAndDrain(); preview.closeAndDrain(); previews.forEach { it.close() }; server.shutdown()
        }
    }
}
