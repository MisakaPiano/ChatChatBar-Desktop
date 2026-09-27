package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiStreamingTransportTest {
    @Test
    fun `parser supports reasoning aliases array content refusal and usage`() {
        val aliases = listOf("reasoning_content", "reasoning", "thinking")
        aliases.forEach { alias ->
            val chunk = OpenAiSseParser.parse("""{"choices":[{"delta":{"$alias":"thought"}}]}""")
            assertEquals("thought", chunk.reasoningContent)
        }
        val array = OpenAiSseParser.parse(
            """{"choices":[{"delta":{"content":[{"type":"text","text":"a"},{"content":"b"}]},"finish_reason":"content_filter"}]}""",
        )
        val usage = OpenAiSseParser.parseUsage(
            """{"choices":[],"usage":{"prompt_tokens":10,"prompt_tokens_details":{"cached_tokens":4,"cache_write_tokens":2},"completion_tokens":3}}""",
        )
        assertEquals("ab", array.content)
        assertTrue(array.refused)
        assertEquals(PromptCacheUsage(promptTokens = 10, cachedTokens = 4, cacheWriteTokens = 2, completionTokens = 3), usage)
    }

    @Test
    fun `transport emits content reasoning usage and one done terminal`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(
                """{"choices":[{"delta":{"reasoning_content":"think"}}]}""",
                """{"choices":[{"delta":{"content":"answer"}}],"usage":{"prompt_tokens":8,"prompt_cache_hit_tokens":3}}""",
                "[DONE]",
            ))

            val events = collect(server)

            assertEquals("think", events.filterIsInstance<ProviderStreamEvent.ReasoningDelta>().single().text)
            assertEquals("answer", events.filterIsInstance<ProviderStreamEvent.ContentDelta>().single().text)
            assertEquals(3, events.filterIsInstance<ProviderStreamEvent.Usage>().single().usage.cachedTokens)
            assertEquals(1, events.count { it is ProviderStreamEvent.Completed })
            assertFalse(events.any { it is ProviderStreamEvent.Error })
        }
    }

    @Test
    fun `finish reason grace retains trailing usage before exactly one completion`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(
                """{"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":2}}""",
            ))

            val events = collect(server)
            val completed = events.filterIsInstance<ProviderStreamEvent.Completed>().single()

            assertEquals("stop", completed.metadata.finishReason)
            assertEquals(11, events.filterIsInstance<ProviderStreamEvent.Usage>().single().usage.promptTokens)
            assertTrue(events.indexOfFirst { it is ProviderStreamEvent.Usage } < events.indexOfFirst { it is ProviderStreamEvent.Completed })
        }
    }

    @Test
    fun `early eof and malformed data are explicit single terminal errors`() = runBlocking {
        listOf(
            """{"choices":[{"delta":{"content":"partial"}}]}""" to "未收到 finish_reason",
            "not-json{{" to "解析 SSE 数据失败",
        ).forEach { (payload, expected) ->
            MockWebServer().use { server ->
                server.enqueue(sse(payload))
                val events = collect(server)
                assertEquals(1, events.count { it is ProviderStreamEvent.Error })
                assertTrue(events.filterIsInstance<ProviderStreamEvent.Error>().single().message.contains(expected))
                assertFalse(events.any { it is ProviderStreamEvent.Completed })
            }
        }
    }

    @Test
    fun `http 400 code 20015 retries at most twice then succeeds`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(400).setBody("provider 20015")) }
            server.enqueue(sse("[DONE]"))

            val events = withTimeout(8_000) { collect(server) }

            assertEquals(3, server.requestCount)
            assertEquals(1, events.count { it is ProviderStreamEvent.Completed })
        }

        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setResponseCode(400).setBody("provider 20015")) }
            val events = withTimeout(8_000) { collect(server) }
            assertEquals(3, server.requestCount)
            assertEquals(1, events.count { it is ProviderStreamEvent.Error })
        }
    }

    @Test
    fun `cancellation during retry delay prevents another request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("20015"))
            server.enqueue(sse("[DONE]"))
            val job = launch { transport().streamMainChat(messages(), model(server)).toList() }
            withTimeout(2_000) {
                while (server.requestCount == 0) delay(10)
            }
            delay(100)
            job.cancelAndJoin()
            delay(1_100)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `failure after finish reason completes with transport failed evidence`() = runBlocking {
        TruncatedSseServer(
            """{"choices":[{"delta":{"content":"partial"},"finish_reason":"stop"}]}""",
        ).use { server ->
            val events = withTimeout(5_000) {
                transport().streamMainChat(messages(), model(server.baseUrl)).toList()
            }
            val completed = events.filterIsInstance<ProviderStreamEvent.Completed>().single()
            assertEquals("stop", completed.metadata.finishReason)
            assertTrue(completed.metadata.transportFailed)
            assertEquals("partial", events.filterIsInstance<ProviderStreamEvent.ContentDelta>().single().text)
            assertFalse(events.any { it is ProviderStreamEvent.Error })
        }
    }

    @Test
    fun `diagnostics receives final adapted request and transport chunks without authorization`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse("[DONE]"))
            var requestEvidence: ProviderRequestEvidence? = null
            val chunks = mutableListOf<String>()
            val diagnostics = object : ProviderTransportDiagnostics {
                override fun onRequest(request: ProviderRequestEvidence) {
                    requestEvidence = request
                }

                override fun onResponseChunk(data: String, parsed: OpenAiSseChunk?) {
                    chunks += data
                }
            }
            transport().streamMainChat(
                messages = listOf(
                    ChatApiMessage.text("system", "root"),
                    ChatApiMessage.text("system", "tail"),
                ),
                modelConfig = model(server).copy(apiKey = "fake-secret"),
                diagnostics = diagnostics,
            ).toList()

            val evidence = assertIs<ProviderRequestEvidence>(requestEvidence)
            assertTrue(evidence.body.contains("\"role\":\"user\""))
            assertFalse(evidence.body.contains("fake-secret"))
            assertEquals(listOf("[DONE]"), chunks)
            assertEquals("Bearer fake-secret", server.takeRequest().getHeader("Authorization"))
        }
    }

    private suspend fun collect(server: MockWebServer): List<ProviderStreamEvent> =
        withTimeout(8_000) { transport().streamMainChat(messages(), model(server)).toList() }

    private fun transport() = OpenAiStreamingTransport(allowCleartextHttp = { true })

    private fun messages() = listOf(ChatApiMessage.text("user", "hello"))

    private fun model(server: MockWebServer) = model(server.url("/v1").toString())

    private fun model(baseUrl: String) = ModelConfig(
        id = "test",
        displayName = "Test",
        baseUrl = baseUrl,
        apiKey = "",
        modelName = "test-model",
        createdAt = 0,
    )

    private fun sse(vararg payloads: String) = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setBody(payloads.joinToString("") { "data: $it\n\n" })
}

private class TruncatedSseServer(payload: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    private val worker = thread(name = "truncated-sse-server", isDaemon = true) {
        server.accept().use { socket ->
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            while (!reader.readLine().isNullOrEmpty()) Unit
            val body = "data: $payload\n\n".toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/event-stream\r\n")
                append("Content-Length: ${body.size + 100}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            socket.getOutputStream().apply {
                write(headers)
                write(body)
                flush()
            }
        }
    }

    override fun close() {
        server.close()
        worker.join(1_000)
    }
}
