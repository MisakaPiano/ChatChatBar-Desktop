package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlin.test.*

/** Independent MPEG-1 Layer-III silence fixture; never calls the production validator to build bytes. */
internal fun fishPreviewMp3(frames: Int = 50) = ByteArray(frames * 417).also { bytes ->
    repeat(frames) { n -> byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0).copyInto(bytes, n * 417) }
}

internal class PreviewOutputFake : DesktopAudioOutput {
    var playing = false
    var paused = false
    var plays = 0
    var closes = 0
    var failClose = false
    override suspend fun play(path: Path) { check(Files.size(path) > 0); plays++; playing = true }
    override suspend fun pause(): Long { check(playing); playing = false; paused = true; return 1234L }
    override suspend fun resume() { check(paused); paused = false; playing = true }
    override suspend fun positionMs(): Long = 1234L
    override suspend fun isPlaying() = playing
    override suspend fun stopAndDrain() { check(!failClose); playing = false; paused = false }
    override suspend fun closeAndDrain() { stopAndDrain(); closes++ }
}

class DesktopFishPreviewTest {
    @Test fun `cached raw bytes and file identity are rechecked and outside files stay untouched`() {
        val parent = Files.createTempDirectory("f1-preservation-")
        val sentinel = parent.resolve("outside.txt"); Files.writeString(sentinel, "synthetic outside evidence")
        val before = Files.readAllBytes(sentinel)
        val dir = Files.createDirectory(parent.resolve("owned"))
        val store = DesktopFishPreviewStore(dir)
        val bytes = fishPreviewMp3()
        val clip = store.write(bytes.inputStream(), bytes.size.toLong()) {}
        store.verify(clip)
        Files.write(clip, bytes.copyOf().also { it[100] = 1 })
        assertFails { store.verify(clip) }
        val retained = dir.resolve("retained-original.mp3")
        Files.move(clip, retained); Files.write(clip, bytes)
        assertFails { store.verify(clip) }; assertFails { store.close() }
        assertContentEquals(before, Files.readAllBytes(sentinel))
        assertTrue(Files.exists(clip)); assertTrue(Files.exists(retained))
        // Fixture is deliberately preserved: no production cleanup of replaced/unknown files.
    }

    @Test fun `outstanding session leases bound aggregate retained cache capacity`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val runtime = DesktopFishPreviewRuntime(transport(server), PreviewOutputFake())
            repeat(4) { runtime.openSession() }
            assertFails { runtime.openSession() }
            runtime.closeAndDrain()
            assertEquals(0, server.requestCount)
        }
    }
    private suspend fun await(check: () -> Boolean) = withTimeout(8_000) { while (!check()) delay(10) }
    private fun transport(server: MockWebServer) = DesktopFishPreviewTransport(
        DesktopFishAudioCredentials(InMemoryDesktopSecretStore()).also { it.save("synthetic-only") },
        OkHttpClient(), server.url("/").toString().trimEnd('/'))
    private fun audio() = MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3()))

    @Test fun `explicit free request uses shared serialization and first result cache never resends changed text`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(audio()); server.start()
            val dir = Files.createTempDirectory("f1-cache-test-")
            val output = PreviewOutputFake()
            val runtime = DesktopFishPreviewRuntime(transport(server), output, stores = { DesktopFishPreviewStore(dir) })
            val session = runtime.openSession()
            try {
                assertEquals(0, server.requestCount)
                session.generate("fixture-voice", "初回テストです。")
                await { session.state.value.playing }
                val req = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/v1/tts", req.path); assertEquals("s2.1-pro-free", req.getHeader("model"))
                val body = req.body.readUtf8()
                assertTrue(body.contains("\"reference_id\":\"fixture-voice\"")); assertTrue(body.contains("\"format\":\"mp3\""))
                session.stop(); await { !session.state.value.playing && !session.state.value.busy }
                session.generate("fixture-voice", "別のテキストです。")
                await { session.state.value.playing && session.state.value.cached }
                assertTrue(session.state.value.message!!.contains("新输入未发送"))
                assertEquals(1, server.requestCount)
            } finally { runtime.closeAndDrain() }
            assertFalse(Files.exists(dir)); assertEquals(1, output.closes)
        }
    }

    @Test fun `blank oversized paid and stale models do not send any HTTP`() = runBlocking {
        for ((model, text) in listOf("s2.1-pro-free" to " ", "s2.1-pro-free" to "a".repeat(121),
            "s2.1-pro" to "hello", "unknown" to "hello")) {
            MockWebServer().use { server ->
                server.start()
                val runtime = DesktopFishPreviewRuntime(transport(server), PreviewOutputFake(), model = { model })
                val session = runtime.openSession()
                session.generate("fixture", text)
                await { session.state.value.message != null }
                runtime.closeAndDrain()
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun `HTTP failures including retry after redirect and auth never create a second physical request`() = runBlocking {
        for (code in listOf(301, 401, 403, 429, 503)) MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "0")
                .setHeader("Location", server.url("/other")).setBody("private synthetic response"))
            server.enqueue(audio())
            val dir = Files.createTempDirectory("f1-errors-")
            val store = DesktopFishPreviewStore(dir)
            val t = transport(server)
            try {
                val error = assertFailsWith<IllegalStateException> { t.generate("voice", "test", "s2.1-pro-free", store) }
                assertFalse(error.toString().contains("private synthetic"))
                assertEquals(1, server.requestCount)
                assertEquals(0L, Files.list(dir).use { it.count() })
            } finally { t.closeAndDrain(); store.close() }
        }
    }

    @Test fun `cancel after headers joins physical body and cleanup before runtime closes`() = runBlocking {
        MockWebServer().use { server ->
            // MockWebServer throttleBody also throttles reading the POST request body, before
            // takeRequest can return. Delay RESPONSE body only; upload must finish normally.
            server.enqueue(audio().setBodyDelay(2, TimeUnit.SECONDS)); server.start()
            val dir = Files.createTempDirectory("f1-cancel-")
            val output = PreviewOutputFake()
            // Native identity initialization is fixture setup, not the body-cancellation window.
            val owned = DesktopFishPreviewStore(dir)
            val failure = java.util.concurrent.atomic.AtomicReference<Exception>()
            val runtime = DesktopFishPreviewRuntime(transport(server), output, stores = { owned }, failureObserver = { failure.set(it) })
            val session = runtime.openSession()
            try {
                session.generate("fixture", "test")
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) },
                    "Synthetic cancellation readiness: ${session.state.value}; ${failure.get()}")
                await { Files.list(dir).use { it.anyMatch { path -> path.toString().endsWith(".part") } } }
            } finally { withTimeout(8_000) { runtime.closeAndDrain() } }
            assertFalse(Files.exists(dir)); assertEquals(0, output.plays)
        }
    }

    @Test fun `native release failure retains cache and later confirmed close can release it`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(audio()); server.start()
            val dir = Files.createTempDirectory("f1-retention-")
            val output = PreviewOutputFake()
            val runtime = DesktopFishPreviewRuntime(transport(server), output, stores = { DesktopFishPreviewStore(dir) })
            runtime.openSession().generate("fixture", "test")
            await { output.playing }; output.failClose = true
            assertFails { runtime.closeAndDrain() }
            assertTrue(Files.list(dir).use { it.anyMatch { path -> path.toString().endsWith(".mp3") } })
            output.failClose = false
            runtime.closeAndDrain(); assertFalse(Files.exists(dir))
        }
    }

    @Test fun `bounded store rejects invalid truncated over quota and preserves unknown files`() {
        for (bytes in listOf(byteArrayOf(), "ID3invalid".toByteArray(), fishPreviewMp3().dropLast(1).toByteArray())) {
            val dir = Files.createTempDirectory("f1-invalid-")
            val store = DesktopFishPreviewStore(dir)
            assertFails { store.write(bytes.inputStream(), bytes.size.toLong()) {} }
            store.close(); assertFalse(Files.exists(dir))
        }
        val dir = Files.createTempDirectory("f1-quota-")
        val store = DesktopFishPreviewStore(dir, 100, 100)
        assertFails { store.write(fishPreviewMp3().inputStream(), -1) {} }
        val unknown = dir.resolve("retain.txt"); Files.writeString(unknown, "synthetic retained")
        assertFails { store.close() }
        assertEquals("synthetic retained", Files.readString(unknown))
        Files.delete(unknown); store.close()
    }

    @Test fun `native commands use a single thread and retained alias on close error`() = runBlocking {
        val commands = CopyOnWriteArrayList<String>()
        val threads = mutableSetOf<Long>()
        var failClose = false
        val backend = DesktopWindowsAudioOutput { command ->
            commands += command; threads += Thread.currentThread().id
            if (command.startsWith("close") && failClose) error("synthetic close error")
            if (command.contains(" length ")) "1304" else if (command.contains(" mode ")) "playing" else ""
        }
        val file = Files.createTempFile("f1-command-", ".mp3")
        backend.play(file)
        assertTrue(backend.isPlaying()); failClose = true
        assertFails { backend.closeAndDrain() }
        failClose = false; backend.closeAndDrain()
        assertEquals(1, threads.size)
        assertFalse(commands.any { it.contains("close all") })
        assertEquals(2, commands.count { it.startsWith("close") })
        Files.delete(file)
    }
}
