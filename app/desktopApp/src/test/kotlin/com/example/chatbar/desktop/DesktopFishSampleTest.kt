package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.net.InetAddress
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlin.test.*

internal const val syntheticOfficialSample = "https://platform.r2.fish.audio/task/11111111111111111111111111111111.mp3"

class DesktopFishSampleTest {
    private suspend fun await(check: () -> Boolean) = withTimeout(8_000) { while (!check()) delay(10) }
    private fun audio(bytes: ByteArray = fishPreviewMp3()) = MockResponse()
        .setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(bytes))
    private fun sampleTransport(server: MockWebServer, client: OkHttpClient = OkHttpClient()) =
        DesktopFishSampleTransport(client) { url -> server.url(url.encodedPath) }
    private fun ttsTransport(server: MockWebServer) = DesktopFishPreviewTransport(
        DesktopFishAudioCredentials(InMemoryDesktopSecretStore()).also { it.save("synthetic-only") },
        OkHttpClient(), server.url("/").toString().trimEnd('/'))

    /** Check observable wrapper/cause/suppressed data without printing rejected exception content. */
    private fun assertSanitizedNetworkChain(failure: Throwable?): Int {
        val safeMessage = "官方样本网络失败；未自动重试"
        assertTrue(failure?.javaClass == IllegalStateException::class.java, "Expected fixed non-HTTP network failure type")
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val pending = java.util.ArrayDeque<Pair<Throwable, Int>>()
        pending.addLast(requireNotNull(failure) to 0)
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeFirst()
            if (!seen.add(current)) continue
            assertTrue(seen.size <= 16 && depth <= 8, "Exception-chain inspection budget exceeded")
            assertTrue(current.javaClass == IllegalStateException::class.java && current !is DesktopFishPreviewHttpFailure,
                "Exception chain contains an unapproved type")
            assertTrue(current.message == safeMessage && current.localizedMessage == safeMessage,
                "Exception chain contains unapproved observable information")
            val observed = current.toString().lowercase(java.util.Locale.ROOT)
            assertTrue(listOf("synthetic hidden detail", "authorization", "cookie", "bearer", "http://", "https://",
                "synthetic-private-response", "api_key", "token=").none { it in observed },
                "Exception chain contains forbidden information")
            val suppressed = current.suppressed
            assertTrue(suppressed.size <= 8, "Suppressed-exception budget exceeded")
            val children = listOfNotNull(current.cause) + suppressed
            for (child in children) if (child !in seen) {
                assertTrue(pending.size < 16, "Pending exception-chain budget exceeded")
                pending.addLast(child to depth + 1)
            }
        }
        return seen.size
    }

    @Test fun `closed anonymous origin profile rejects unsafe or authenticated targets before any request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val t = sampleTransport(server)
            val dir = Files.createTempDirectory("f2-origin-")
            val store = DesktopFishPreviewStore(dir)
            try {
                val rejected = listOf("", "http://platform.r2.fish.audio/task/11111111111111111111111111111111.mp3",
                    syntheticOfficialSample.replace("platform.r2.fish.audio", "platform.r2.fish.audio.evil.example"),
                    syntheticOfficialSample.replace("platform.r2.fish.audio", "evil.example@platform.r2.fish.audio"),
                    syntheticOfficialSample.replace("platform.r2.fish.audio", "platform.r2.fish.audio:8443"),
                    syntheticOfficialSample.replace("platform.r2.fish.audio", "127.0.0.1"),
                    syntheticOfficialSample.replace("platform.r2.fish.audio", "[::1]"),
                    syntheticOfficialSample.replace("platform.r2.fish.audio", "api.fish.audio"),
                    syntheticOfficialSample + "?signature=synthetic", syntheticOfficialSample + "#fragment",
                    syntheticOfficialSample.replace("/task/", "/task/../task/"),
                    syntheticOfficialSample.replace(".mp3", ".wav"))
                for (url in rejected) {
                    val failure = assertFails { t.download(url, store) }
                    assertFalse(failure.toString().contains("https://"))
                }
                assertEquals(0, server.requestCount)
                assertEquals(0L, Files.list(dir).use { it.count() })
            } finally { t.closeAndDrain(); store.close() }
        }
    }

    @Test fun `public DNS policy rejects local reserved mapped and mixed targets`() {
        for (address in listOf("0.0.0.0", "127.0.0.1", "10.0.0.1", "100.64.0.1", "172.16.0.1",
            "169.254.1.1", "192.168.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
            "224.0.0.1", "::", "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1"))
            assertFalse(DesktopFishSamplePolicy.publicAddress(InetAddress.getByName(address)), address)
        assertTrue(DesktopFishSamplePolicy.publicAddress(InetAddress.getByName("104.21.1.1")))
        assertTrue(DesktopFishSamplePolicy.publicAddress(InetAddress.getByName("2606:4700::1111")))
        assertEquals(syntheticOfficialSample, DesktopFishSamplePolicy.approve(syntheticOfficialSample).toString())
    }

    @Test fun `anonymous GET strips credentials cookies and referrer even from injected client`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(audio()); server.start()
            val injected = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .header("Authorization", "Bearer synthetic-must-not-leave")
                .header("Cookie", "synthetic-only").header("Referer", "https://synthetic.example/").build()) }.build()
            val t = sampleTransport(server, injected)
            val dir = Files.createTempDirectory("f2-anonymous-"); val store = DesktopFishPreviewStore(dir)
            try {
                val clip = t.download(syntheticOfficialSample, store)
                assertContentEquals(fishPreviewMp3(), Files.readAllBytes(clip))
                val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("GET", request.method); assertEquals(0L, request.bodySize)
                for (name in listOf("Authorization", "Cookie", "Proxy-Authorization", "Referer", "model"))
                    assertNull(request.getHeader(name), name)
                assertEquals(1, server.requestCount)
            } finally { t.closeAndDrain(); store.close() }
            assertFalse(Files.exists(dir))
        }
    }

    @Test fun `status failures redirects and retry after never resend or leak response content`() = runBlocking {
        for (code in listOf(301, 302, 307, 401, 403, 429, 503)) MockWebServer().use { server ->
            val entered = java.util.concurrent.atomic.AtomicInteger()
            val sent = java.util.concurrent.atomic.AtomicInteger()
            val guarded = java.util.concurrent.atomic.AtomicInteger()
            val received = CopyOnWriteArrayList<Int>()
            val networkResponses = CopyOnWriteArrayList<Int>()
            val wireSecretHeaders = java.util.concurrent.atomic.AtomicInteger()
            val failedCallbacks = java.util.concurrent.atomic.AtomicInteger()
            val diagnosticClient = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun requestHeadersEnd(call: okhttp3.Call, request: okhttp3.Request) {
                    sent.incrementAndGet()
                    if (listOf("Authorization", "Cookie", "Proxy-Authorization").any { request.header(it) != null })
                        wireSecretHeaders.incrementAndGet()
                }
                override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { received += response.code }
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { failedCallbacks.incrementAndGet() }
            }).addNetworkInterceptor { chain ->
                entered.incrementAndGet()
                try { chain.proceed(chain.request()).also { networkResponses += it.code } }
                catch (failure: java.io.IOException) {
                    if (failure.message == "禁止重复样本请求") guarded.incrementAndGet()
                    throw failure
                }
            }.build()
            server.start()
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "0")
                .setHeader("Location", "http://127.0.0.1/private").setBody("synthetic-private-response"))
            server.enqueue(audio())
            val t = sampleTransport(server, diagnosticClient)
            val dir = Files.createTempDirectory("f2-status-"); val store = DesktopFishPreviewStore(dir)
            try {
                val observed = runCatching { t.download(syntheticOfficialSample, store) }.exceptionOrNull()
                val callback = if (failedCallbacks.get() > 0) "onFailure" else
                    if (observed is DesktopFishPreviewHttpFailure) "onResponse" else "other"
                val record = "{\"responseCode\":$code,\"serverRequests\":${server.requestCount},\"physicalHeadersSent\":${sent.get()}," +
                    "\"networkEntries\":${entered.get()},\"guardHits\":${guarded.get()},\"responseHeaders\":$received," +
                    "\"networkResponses\":$networkResponses,\"wireSecretHeaders\":${wireSecretHeaders.get()}," +
                    "\"callFailed\":${failedCallbacks.get()},\"callback\":\"$callback\",\"failureType\":\"${observed?.javaClass?.simpleName}\"}\n"
                System.getenv("CCB_G2_INSPECTOR_EVIDENCE")?.let {
                    Files.writeString(Path.of(it).resolve("http-followup-diagnostics.jsonl"), record,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                }
                println("F2_HTTP_DIAGNOSTIC " + record.trim())
                assertEquals(1, server.requestCount, "Only one physical request is authorized")
                assertEquals(1, sent.get(), "Only one headers transmission is authorized")
                assertEquals(0, wireSecretHeaders.get())
                assertEquals(listOf(code), received.toList())
                assertEquals(listOf(code), networkResponses.toList())
                assertEquals(if (code == 503) 2 else 1, entered.get())
                assertEquals(if (code == 503) 1 else 0, guarded.get())
                assertEquals(if (code == 503) 1 else 0, failedCallbacks.get())
                val failure = assertIs<DesktopFishPreviewHttpFailure>(observed)
                assertEquals(code, failure.status)
                assertFalse(failure.toString().contains("synthetic-private")); assertFalse(failure.toString().contains("127.0.0.1"))
                assertEquals(1, server.requestCount)
            } finally { t.closeAndDrain(); store.close() }
            assertFalse(Files.exists(dir))
        }
        // Same bounded method checks unrelated failures never acquire a stale status from another call.
        for (kind in listOf("DNS", "TLS", "IO")) MockWebServer().use { server ->
            server.start()
            val injected = OkHttpClient.Builder().addInterceptor {
                throw when (kind) {
                    "DNS" -> java.net.UnknownHostException("synthetic hidden detail")
                    "TLS" -> javax.net.ssl.SSLHandshakeException("synthetic hidden detail")
                    else -> java.io.IOException("synthetic hidden detail")
                }
            }.build()
            val t = sampleTransport(server, injected)
            val dir = Files.createTempDirectory("f2-network-"); val store = DesktopFishPreviewStore(dir)
            try {
                val failure = runCatching { t.download(syntheticOfficialSample, store) }.exceptionOrNull()
                val chainNodes = assertSanitizedNetworkChain(failure)
                assertEquals(0, server.requestCount)
                assertEquals(0L, Files.list(dir).use { it.count() })
                val record = "{\"injectedNetworkFault\":\"$kind\",\"serverRequests\":0,\"httpStatusPreserved\":false," +
                    "\"sanitized\":true,\"chainNodes\":$chainNodes,\"causePresent\":${failure?.cause != null}}\n"
                System.getenv("CCB_G2_INSPECTOR_EVIDENCE")?.let {
                    Files.writeString(Path.of(it).resolve("http-followup-diagnostics.jsonl"), record,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                }
                println("F2_NETWORK_DIAGNOSTIC " + record.trim())
            } finally { t.closeAndDrain(); store.close() }
            assertFalse(Files.exists(dir))
        }
    }

    @Test fun `incomplete oversized empty or wrong format response never reaches playback`() = runBlocking {
        val cases = listOf(audio(byteArrayOf()), audio("not-mp3".toByteArray()),
            audio(fishPreviewMp3().dropLast(1).toByteArray()),
            audio().setHeader("Content-Type", "audio/wav"),
            audio().setHeader("Content-Length", 17L * 1024 * 1024),
            audio().setHeader("Content-Length", fishPreviewMp3().size + 100).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        for (response in cases) MockWebServer().use { server ->
            server.enqueue(response); server.start()
            val t = sampleTransport(server)
            val dir = Files.createTempDirectory("f2-invalid-"); val store = DesktopFishPreviewStore(dir)
            try { assertFails { t.download(syntheticOfficialSample, store) } }
            finally { t.closeAndDrain(); store.close() }
            assertFalse(Files.exists(dir))
        }
    }

    @Test fun `sample and generated caches stay distinct and changed sample identity downloads anew`() = runBlocking {
        MockWebServer().use { server ->
            val generated = fishPreviewMp3()
            val sample = generated.copyOf().also { it[100] = 1 }
            server.enqueue(audio(generated)); server.enqueue(audio(sample)); server.enqueue(audio(sample)); server.start()
            val hashes = CopyOnWriteArrayList<String>()
            val output = object : DesktopAudioOutput {
                private var playing = false
                override suspend fun play(path: Path) { hashes += java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); playing = true }
                override suspend fun isPlaying() = playing
                override suspend fun stopAndDrain() { playing = false }
                override suspend fun closeAndDrain() { playing = false }
            }
            val dir = Files.createTempDirectory("f2-cache-")
            val runtime = DesktopFishPreviewRuntime(ttsTransport(server), output, stores = { DesktopFishPreviewStore(dir) }, samples = sampleTransport(server))
            val session = runtime.openSession()
            suspend fun stop() { session.stop(); await { !session.state.value.playing && !session.state.value.busy } }
            try {
                session.generate("same-voice", "first"); await { session.state.value.playing }; stop()
                session.officialSample("same-voice", syntheticOfficialSample); await { session.state.value.playing }; stop()
                session.generate("same-voice", "changed input"); await { session.state.value.playing && session.state.value.cached }; stop()
                session.officialSample("same-voice", syntheticOfficialSample); await { session.state.value.playing && session.state.value.cached }; stop()
                session.officialSample("same-voice", syntheticOfficialSample.replace("111111", "222222")); await { session.state.value.playing }; stop()
                assertEquals(3, server.requestCount)
                assertEquals(hashes[0], hashes[2]); assertEquals(hashes[1], hashes[3]); assertNotEquals(hashes[0], hashes[1])
                assertEquals(DesktopFishPreviewKind.OFFICIAL_SAMPLE, session.state.value.kind)
            } finally { runtime.closeAndDrain() }
            assertFalse(Files.exists(dir))
        }
    }

    @Test fun `switching sample cancels and joins old body before new play then picker close cleans both`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(audio().setBodyDelay(2, TimeUnit.SECONDS)); server.enqueue(audio()); server.start()
            val dir = Files.createTempDirectory("f2-switch-")
            val store = DesktopFishPreviewStore(dir); val output = PreviewOutputFake()
            val runtime = DesktopFishPreviewRuntime(ttsTransport(server), output, stores = { store }, samples = sampleTransport(server))
            val session = runtime.openSession()
            try {
                session.officialSample("first", syntheticOfficialSample)
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                await { Files.list(dir).use { it.anyMatch { p -> p.toString().endsWith(".part") } } }
                session.officialSample("second", syntheticOfficialSample.replace("111111", "222222"))
                await { session.state.value.playing }
                assertEquals(1, output.plays); assertEquals(2, server.requestCount)
            } finally { session.requestClose(); withTimeout(8_000) { runtime.closeAndDrain() } }
            assertFalse(Files.exists(dir)); assertFalse(output.playing)
        }
    }

    @Test fun `picker close during sample body physically drains before deleting owned cache`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(audio().setBodyDelay(2, TimeUnit.SECONDS)); server.start()
            val dir = Files.createTempDirectory("f2-close-"); val store = DesktopFishPreviewStore(dir)
            val output = PreviewOutputFake()
            val runtime = DesktopFishPreviewRuntime(ttsTransport(server), output, stores = { store }, samples = sampleTransport(server))
            try {
                runtime.openSession().officialSample("voice", syntheticOfficialSample)
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                await { Files.list(dir).use { it.anyMatch { p -> p.toString().endsWith(".part") } } }
            } finally { withTimeout(8_000) { runtime.closeAndDrain() } }
            assertFalse(Files.exists(dir)); assertEquals(0, output.plays)
        }
    }
}
