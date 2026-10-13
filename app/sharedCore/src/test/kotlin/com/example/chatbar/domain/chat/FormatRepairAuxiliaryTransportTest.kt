package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.prompt.*
import java.security.KeyStore
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlin.test.*

class FormatRepairAuxiliaryTransportTest {
    private fun model(server: MockWebServer) = ModelConfig(id="fixture", displayName="Fixture",
        baseUrl=server.url("/v1").toString(), apiKey="", modelName="fixture-model", createdAt=0)
    private fun payload(content: String = "fixed", finish: String? = null) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("content", content) }); finish?.let { put("finish_reason", it) }
        }) })
    }.toString()
    private fun wire(vararg data: String) = data.joinToString("") { "data: $it\n\n" }
    private fun response(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(wire(*data))
    private fun client() = ProxyAwareClient.modelApiBuilder { true }.build()
    private fun stream(server: MockWebServer, timing: AuxiliaryStreamTiming = AuxiliaryStreamTiming(),
        client: OkHttpClient = client(), diagnostics: ProviderTransportDiagnostics = ProviderTransportDiagnostics.NONE,
        model: ModelConfig = model(server)) = isolatedFormatRepairStream(client, true, " 合成格式 ", " 合成人物规则 ",
        "完整正文\n第二段 😀", model, diagnostics, timing)
    private suspend fun collect(server: MockWebServer) = withTimeout(10_000) { stream(server).toList() }
    private fun failure(events: List<StreamEvent>): StreamEvent.Error {
        assertFalse(events.any { it is StreamEvent.Done }); return events.filterIsInstance<StreamEvent.Error>().single()
    }
    private fun golden() = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream(
        "/format-repair/effective-eight-messages.json")).bufferedReader().use { it.readText() })

    private suspend fun temporaryTlsContext(): Pair<SSLContext, X509TrustManager> = withContext(Dispatchers.IO) {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val keytool = Path.of(System.getProperty("java.home"), "bin", executable)
        check(Files.isRegularFile(keytool)) { "Current test JDK must provide keytool" }
        val directory = Files.createTempDirectory("format-repair-tls-")
        val keystore = directory.resolve("localhost.p12")
        val random = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val password = random.joinToString("") { "%02x".format(it.toInt() and 0xff) }.toCharArray()
        random.fill(0)
        var process: Process? = null
        try {
            ensureActive()
            val builder = ProcessBuilder(
                keytool.toString(), "-genkeypair", "-alias", "localhost", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt",
                "-storepass:env", "CCB_TEST_TLS_PASSWORD", "-keypass:env", "CCB_TEST_TLS_PASSWORD",
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            // Keep the generated password out of command lines and test diagnostics.
            builder.environment()["CCB_TEST_TLS_PASSWORD"] = String(password)
            val child = try { builder.start().also { process = it } }
            finally { builder.environment().remove("CCB_TEST_TLS_PASSWORD") }
            check(runInterruptible { child.waitFor(30, TimeUnit.SECONDS) }) { "Test certificate generation timed out" }
            check(child.exitValue() == 0) { "Test certificate generation failed" }
            ensureActive()
            val store = KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(keystore).use { load(it, password) }
            }
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val context = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
            context to tm.trustManagers.filterIsInstance<X509TrustManager>().single()
        } finally {
            password.fill('\u0000')
            withContext(NonCancellable) {
                // Only this fixture's child is owned here; never touch other JDK/user processes.
                process?.takeIf { it.isAlive }?.let {
                    it.destroyForcibly()
                    check(it.waitFor(5, TimeUnit.SECONDS)) { "Test keytool did not terminate; temporary cleanup incomplete" }
                }
                // Exact owned paths only. Both are removed before the HTTPS test body starts.
                Files.deleteIfExists(keystore)
                Files.deleteIfExists(directory)
                check(!Files.exists(directory)) { "Temporary TLS directory cleanup failed" }
            }
        }
    }

    @Test fun `HTTP uses exact golden request with optional authentication and immutable isolated parameters`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (key in listOf("", "fixture-key")) {
                server.enqueue(response(payload(), "[DONE]"))
                val original = model(server).copy(apiKey=key, enableThinking=true, maxOutputTokens=77,
                    customParams=mapOf("temperature" to ParamValue.NumberValue(0.9), "stop" to ParamValue.StringValue("STOP"),
                        "max_completion_tokens" to ParamValue.NumberValue(88.0), "reasoning_effort" to ParamValue.StringValue("high"),
                        "thinking_budget" to ParamValue.NumberValue(99.0),
                        "messages" to ParamValue.StringValue("unrelated history"),
                        "model" to ParamValue.StringValue("wrong-model"), "stream" to ParamValue.BooleanValue(false)))
                assertIs<StreamEvent.Done>(withTimeout(10_000) { stream(server, model=original).toList() }.last())
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/v1/chat/completions", request.path)
                assertEquals(if(key.isEmpty()) null else "Bearer $key", request.getHeader("Authorization"))
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(golden(), body.getValue("messages"))
                assertEquals("fixture-model", body.getValue("model").jsonPrimitive.content)
                assertTrue(body.getValue("stream").jsonPrimitive.boolean)
                assertEquals(false, body.getValue("enable_thinking").jsonPrimitive.boolean)
                for (removed in listOf("temperature", "stop", "max_tokens", "max_completion_tokens", "reasoning_effort", "thinking_budget")) assertFalse(removed in body, removed)
                assertEquals(77, original.maxOutputTokens); assertEquals(true, original.enableThinking)
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `real HTTPS keeps authentication golden and effort thinking off`() = runBlocking<Unit> {
        val (context, trustManager) = temporaryTlsContext()
        MockWebServer().use { server ->
            server.useHttps(context.socketFactory, false)
            server.enqueue(response(payload(), "[DONE]"))
            val tls = client().newBuilder().sslSocketFactory(context.socketFactory, trustManager).build()
            assertIs<StreamEvent.Done>(withTimeout(10_000) { stream(server, client=tls, model=model(server).copy(apiKey="tls-fixture", reasoningEffort="high")).toList() }.last())
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNotNull(request.handshake)
            assertEquals("Bearer tls-fixture", request.getHeader("Authorization"))
            val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(golden(),body.getValue("messages"));assertEquals("none",body.getValue("reasoning_effort").jsonPrimitive.content)
            assertFalse("enable_thinking" in body)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `public repair entry assembles once and each collection makes exactly one request`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(response(payload(), "[DONE]")) }
            val flow=OpenAiStreamingTransport { true }.streamFormatRepair(" 合成格式 ", " 合成人物规则 ", "完整正文\n第二段 😀",model(server))
            repeat(2) {
                assertIs<StreamEvent.Done>(withTimeout(10_000) { flow.toList() }.last())
                assertEquals(golden(),Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("messages"))
            }
            assertEquals(2,server.requestCount)
        }
    }

    @Test fun `HTTP failures preserve status and never retry including 400 20015`() = runBlocking<Unit> {
        for(status in listOf(401,403,400,429,500,302)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("x-request-id","trace-fixture").setHeader("Retry-After","2")
                .setHeader("Location",server.url("/redirect")).setBody("20015"))
            val error=failure(collect(server));val cause=assertIs<ModelRequestException>(error.cause)
            assertEquals(status,cause.httpStatus);assertEquals("trace-fixture",cause.traceId);assertEquals(2000L,cause.retryAfterMillis)
            assertEquals(if(status==401 || status==403) AiTaskFailureKind.AUTHENTICATION else if(status==429 || status==500) AiTaskFailureKind.NETWORK else AiTaskFailureKind.REQUEST,error.failureKind)
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun `normal delta usage and finish preserve body but never expose reasoning`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response("""{"choices":[{"delta":{"reasoning_content":"private thought"}}]}""", payload("a"),payload(" b","stop"),
                """{"choices":[],"usage":{"completion_tokens":3}}""","[DONE]"))
            val events=collect(server)
            assertEquals("a b",events.filterIsInstance<StreamEvent.Delta>().joinToString(""){it.text})
            assertFalse(events.any { it is StreamEvent.ReasoningDelta })
            assertEquals(3,events.filterIsInstance<StreamEvent.Usage>().single().usage.completionTokens)
            assertEquals(1,events.count { it is StreamEvent.Done });assertIs<StreamEvent.Done>(events.last())
        }
    }

    @Test fun `503 with immediate Retry-After cannot trigger OkHttp follow-up`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(response(payload("must not be requested"), "[DONE]"))
            val error = failure(collect(server))
            val cause = assertIs<ModelRequestException>(error.cause)
            assertEquals(503, cause.httpStatus)
            assertEquals(0L, cause.retryAfterMillis)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `malformed usage is a protocol failure even after finish`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(payload("partial", "stop"), """{"usage":{"completion_tokens":{}}}""", "[DONE]"))
            val events = withTimeout(10_000) {
                stream(server, timing = AuxiliaryStreamTiming(finishGraceMillis = 5_000)).toList()
            }
            val error = failure(events)
            assertEquals(AiTaskFailureKind.FORMAT, error.failureKind)
            assertEquals(AuxiliaryProtocolFailure.MALFORMED_SSE, assertIs<AuxiliaryProtocolException>(error.cause).failure)
        }
    }

    @Test fun `reasoning only and empty body remain distinguishable empty failures`() = runBlocking<Unit> {
        for(reasoning in listOf(false,true)) MockWebServer().use { server ->
            server.enqueue(if(reasoning) response("""{"choices":[{"delta":{"reasoning":"thinking"}}]}""","[DONE]") else response("[DONE]"))
            val error=failure(collect(server));assertIs<AiTaskEmptyResponseException>(error.cause)
            assertEquals(AiTaskFailureKind.EMPTY,error.failureKind);assertEquals(reasoning,error.message.contains("思考"))
        }
    }

    @Test fun `refusal filtering truncation malformed SSE and early EOF never succeed`() = runBlocking<Unit> {
        val cases=listOf(
            response(payload("partial","length")) to AiTaskFailureKind.TRUNCATED,
            response(payload("partial","content_filter")) to AiTaskFailureKind.CONTENT_FILTER,
            response("""{"choices":[{"delta":{"refusal":"no"}}]}""") to AiTaskFailureKind.REFUSAL,
            response(payload("I cannot help with this request."),"[DONE]") to AiTaskFailureKind.REFUSAL,
            response("not-json") to AiTaskFailureKind.FORMAT,
            response(payload("partial")) to AiTaskFailureKind.FORMAT,
            response(payload("partial","tool_calls"),"[DONE]") to AiTaskFailureKind.FORMAT)
        for((reply,kind) in cases) MockWebServer().use { server ->
            server.enqueue(reply); assertEquals(kind,failure(collect(server)).failureKind)
        }
    }

    @Test fun `finish without DONE permits tail usage and clean close`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(payload("fixed","stop"),"""{"usage":{"completion_tokens":5}}"""))
            val events=collect(server);assertIs<StreamEvent.Done>(events.last())
            assertEquals(5,events.filterIsInstance<StreamEvent.Usage>().single().usage.completionTokens)
        }
    }

    @Test fun `observed wire failure after finish cannot become late success`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val body=wire(payload("fixed","stop"))+": padding\n\n".repeat(1000)
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(body)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val error=failure(withTimeout(10_000){stream(server,timing=AuxiliaryStreamTiming(finishGraceMillis=5_000)).toList()})
            assertIs<ModelRequestException>(error.cause)
        }
    }

    @Test fun `production timeouts are 600 seconds and not a total call timeout`() {
        val timing=AuxiliaryStreamTiming();val configured=client().forIsolatedRepair(timing)
        assertEquals(600_000L,timing.inactivityMillis);assertEquals(600_000,configured.readTimeoutMillis)
        assertEquals(0,configured.callTimeoutMillis);assertFalse(configured.retryOnConnectionFailure)
        assertFalse(configured.followRedirects);assertFalse(configured.followSslRedirects)
    }

    @Test fun `usage events do not refresh effective output deadline`() = runBlocking<Unit> {
        val clock=AtomicLong()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream")
                .setBody(wire("""{"usage":{"completion_tokens":1}}""")+": heartbeat\n\n".repeat(10000))
                .throttleBody(100,10,TimeUnit.MILLISECONDS))
            val diagnostics=object:ProviderTransportDiagnostics {override fun onResponseChunk(data:String,parsed:OpenAiSseChunk?) {clock.set(TimeUnit.SECONDS.toNanos(1))}}
            val events=withTimeout(10_000) {stream(server,AuxiliaryStreamTiming(inactivityMillis=100,pollMillis=5,nanoTime=clock::get),diagnostics=diagnostics).toList()}
            assertIs<java.net.SocketTimeoutException>(failure(events).cause)
        }
    }

    @Test fun `heartbeats cannot keep a request without effective output alive`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(": heartbeat\n\n".repeat(10000))
                .throttleBody(100,10,TimeUnit.MILLISECONDS))
            val events=withTimeout(10_000) {stream(server,AuxiliaryStreamTiming(inactivityMillis=150,pollMillis=10)).toList()}
            assertIs<java.net.SocketTimeoutException>(failure(events).cause)
        }
    }

    @Test fun `effective output resets inactivity rather than imposing absolute deadline`() = runBlocking<Unit> {
        val clock=AtomicLong()
        MockWebServer().use { server ->
            server.enqueue(response(payload("a"),payload("b"),payload("c"),"[DONE]"))
            val diagnostics=object:ProviderTransportDiagnostics {override fun onResponseChunk(data:String,parsed:OpenAiSseChunk?) {clock.addAndGet(TimeUnit.SECONDS.toNanos(1))}}
            val events=withTimeout(10_000) {stream(server,AuxiliaryStreamTiming(inactivityMillis=100,pollMillis=5,nanoTime=clock::get),diagnostics=diagnostics).toList()}
            assertIs<StreamEvent.Done>(events.last());assertEquals("abc",events.filterIsInstance<StreamEvent.Delta>().joinToString(""){it.text})
        }
    }

    @Test fun `short socket timeout seam reports failure without retry`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val events=withTimeout(10_000) {stream(server,AuxiliaryStreamTiming(readTimeoutMillis=150,inactivityMillis=5_000)).toList()}
            assertIs<ModelRequestException>(failure(events).cause);assertEquals(1,server.requestCount)
        }
    }

    @Test fun `cancellation cancels the actual call and delivers no late Done or user Stop`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val failed=CountDownLatch(1);val ready=CompletableDeferred<Call>();val events=mutableListOf<StreamEvent>()
            // OkHttp SSE deliberately replaces EventListener with NONE; observe the actual Call via interceptor.
            val observed=client().newBuilder().addInterceptor { chain ->
                ready.complete(chain.call())
                try { chain.proceed(chain.request()) } catch(error:java.io.IOException) { failed.countDown(); throw error }
            }.build()
            val job=launch {stream(server,client=observed).collect {events+=it}}
            val call=withTimeout(10_000){ready.await()}
            assertNotNull(withContext(Dispatchers.IO){server.takeRequest(5,TimeUnit.SECONDS)})
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
            assertTrue(withContext(Dispatchers.IO){failed.await(5,TimeUnit.SECONDS)})
            assertTrue(events.isEmpty());assertTrue(job.isCancelled)
        }
    }
    @Test fun `cleartext permission is enforced without making a request`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val events=withTimeout(10_000) {OpenAiStreamingTransport().streamFormatRepair(null,null,"body",model(server)).toList()}
            assertEquals(AiTaskFailureKind.NETWORK,failure(events).failureKind)
            assertEquals(0,server.requestCount)
        }
    }

    @Test fun `cancel after content never delivers buffered late terminal success`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream")
                .setBody(wire(payload("partial"))+": keepalive\n\n".repeat(10000)+wire(payload("late","stop"),"[DONE]"))
                .throttleBody(100,10,TimeUnit.MILLISECONDS))
            val received=CompletableDeferred<Unit>();val events=mutableListOf<StreamEvent>()
            val job=launch {stream(server).collect {events+=it;if(it is StreamEvent.Delta) received.complete(Unit)}}
            withTimeout(10_000){received.await()};job.cancelAndJoin()
            assertTrue(events.any {it is StreamEvent.Delta});assertFalse(events.any {it is StreamEvent.Done || it is StreamEvent.Error})
        }
    }

    @Test fun `finish grace completes once while retaining immediately trailing usage`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream")
                .setBody(wire(payload("fixed","stop"),"""{"usage":{"completion_tokens":2}}""")+": keepalive\n\n".repeat(10000))
                .throttleBody(200,10,TimeUnit.MILLISECONDS))
            val events=withTimeout(10_000){stream(server).toList()}
            assertEquals(1,events.count {it is StreamEvent.Done});assertEquals(2,events.filterIsInstance<StreamEvent.Usage>().single().usage.completionTokens)
        }
    }

}
