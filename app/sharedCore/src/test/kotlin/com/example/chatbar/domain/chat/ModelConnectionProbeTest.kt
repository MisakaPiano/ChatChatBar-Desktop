package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.model.resolveEffectiveModelApiKey
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ModelConnectionProbeTest {
    private fun model(server: MockWebServer, key: String = "fake-key") = ModelConfig(
        id = "model", displayName = "Fake", modelName = "fake-model", baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = key, maxOutputTokens = 64,
        customParams = mapOf("max_tokens" to ParamValue.NumberValue(45.0), "max_completion_tokens" to ParamValue.NumberValue(90.0)),
        createdAt = 1,
    )
    private fun embedding(server: MockWebServer) = EmbeddingConfig(
        id = "embedding", displayName = "Fake embedding", baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "fake-embedding-key", modelName = "fake-embedding", dimensions = 2,
    )
    private fun completion(content: String = "arbitrary valid completion") = MockResponse()
        .setBody("""{"choices":[{"message":{"content":"$content"},"finish_reason":"stop"}]}""")
    private fun vector() = MockResponse().setBody("""{"data":[{"index":0,"embedding":[0.1,0.2]}]}""")

    @Test fun `ordered probes use exact inputs output omission and shared thinking policy`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(completion())
            server.enqueue(vector())
            val result = ModelConnectionProbe(true).run(model(server), embedding(server))
            assertEquals(ConnectionProbeStatus.SUCCESS, result.chat.status)
            assertEquals(ConnectionProbeStatus.SUCCESS, result.embedding.status)
            val chat = server.takeRequest()
            assertEquals("/v1/chat/completions", chat.path)
            assertEquals("Bearer fake-key", chat.getHeader("Authorization"))
            val body = Json.parseToJsonElement(chat.body.readUtf8()).jsonObject
            assertEquals(false, body["stream"]!!.jsonPrimitive.boolean)
            assertEquals("none", body["reasoning_effort"]!!.jsonPrimitive.content)
            assertFalse("max_tokens" in body)
            assertFalse("max_completion_tokens" in body)
            val messages = body["messages"]!!.jsonArray
            assertEquals(1, messages.size)
            assertEquals("user", messages.single().jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("Reply with OK", messages.single().jsonObject["content"]!!.jsonPrimitive.content)
            val embedded = server.takeRequest()
            assertEquals("/v1/embeddings", embedded.path)
            assertEquals("Bearer fake-embedding-key", embedded.getHeader("Authorization"))
            assertEquals(listOf("test"), Json.parseToJsonElement(embedded.body.readUtf8()).jsonObject["input"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `chat failure still probes embedding and auth evidence is scrubbed`() = runBlocking {
        for (status in listOf(401, 403)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("fake-key Authorization: Bearer fake-key"))
            server.enqueue(vector())
            val result = ModelConnectionProbe(true).run(model(server), embedding(server))
            assertEquals(ConnectionProbeStatus.FAILED, result.chat.status)
            assertTrue(result.chat.error!!.contains(status.toString()))
            assertFalse(result.toString().contains("fake-key"))
            assertEquals(ConnectionProbeStatus.SUCCESS, result.embedding.status)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `invalid empty refusal and truncated completions fail once without embedding`() = runBlocking {
        for (body in listOf("not-json", "{}", """{"choices":[{"message":{"content":""}}]}""",
            """{"choices":[{"message":{"content":"partial"},"finish_reason":"length"}]}""",
            """{"choices":[{"message":{"refusal":"no","content":""}}]}""")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                val result = ModelConnectionProbe(true).run(model(server), null)
                assertEquals(ConnectionProbeStatus.FAILED, result.chat.status)
                assertEquals(ConnectionProbeStatus.NOT_CONFIGURED, result.embedding.status)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `effective key precedence and allowed HTTP absence are preserved`() = runBlocking {
        val app = AppSettings(siliconFlowApiKey = "fake-global", allowCleartextModelApi = true)
        assertEquals("fake-own", resolveEffectiveModelApiKey("fake-own", "https://example.invalid", app))
        assertEquals("fake-global", resolveEffectiveModelApiKey("", "https://example.invalid", app))
        assertEquals("", resolveEffectiveModelApiKey("", "http://127.0.0.1", app))
        MockWebServer().use { server ->
            server.enqueue(completion())
            ModelConnectionProbe(true).run(model(server, ""), null)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun `disabled cleartext sends no request`() = runBlocking {
        MockWebServer().use { server ->
            val result = ModelConnectionProbe(false).run(model(server), embedding(server))
            assertEquals(ConnectionProbeStatus.FAILED, result.chat.status)
            assertEquals(ConnectionProbeStatus.FAILED, result.embedding.status)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `cancellation between probes skips embedding`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(completion())
            val operation = async { ModelConnectionProbe(true).run(model(server), embedding(server),
                onChatResult = { throw CancellationException("test cancellation") }) }
            assertFailsWith<CancellationException> { operation.await() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `embedding call cancellation releases suspended probe`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(completion())
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val operation = async { ModelConnectionProbe(true).run(model(server), embedding(server)) }
            withContext(Dispatchers.IO) {
                assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            }
            operation.cancelAndJoin()
            assertTrue(operation.isCancelled)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `cancellation during chat cancels call and never starts configured embedding`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val operation = async { ModelConnectionProbe(true).run(model(server), embedding(server)) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
            withTimeout(5_000) { operation.cancelAndJoin() }
            assertTrue(operation.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `shared completion parser accepts supported content forms without requiring OK`() = runBlocking {
        for (body in listOf(
            """{"choices":[{"message":{"content":[{"text":"array completion"}]}}]}""",
            """{"choices":[{"text":"legacy completion"}]}""",
            """{"output_text":"output completion"}""",
        )) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            assertEquals(ConnectionProbeStatus.SUCCESS, ModelConnectionProbe(true).run(model(server), null).chat.status)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `malformed or missing embedding vector is an explicit single-request failure`() = runBlocking {
        for (body in listOf("not-json", "{}", """{"data":[]}""", """{"data":[{"index":0}]}""")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                val result = ModelConnectionProbe(true).run(null, embedding(server))
                assertEquals(ConnectionProbeStatus.NOT_CONFIGURED, result.chat.status)
                assertEquals(ConnectionProbeStatus.FAILED, result.embedding.status)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `provider echo of secret never enters result`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("fake-key"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("fake-embedding-key"))
            val result = ModelConnectionProbe(true).run(model(server), embedding(server))
            assertFalse(result.toString().contains("fake-key"))
            assertFalse(result.toString().contains("fake-embedding-key"))
            assertEquals(2, server.requestCount)
        }
    }
}
