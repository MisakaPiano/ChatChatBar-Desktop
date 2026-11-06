package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.card.CharacterCardAiStage
import com.example.chatbar.domain.search.CharacterResearchAiStage
import com.example.chatbar.domain.worldbook.WorldBookAiOperation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real loopback requests; no provider, fixtures, repository assets or user data. */
class ExperimentalToolTransportTest {
    private val messages = listOf(ChatApiMessage.text("system", "root"), ChatApiMessage.text("user", "input"))
    private fun model(server: MockWebServer) = ModelConfig("m", "Synthetic", server.url("/v1").toString(),
        "synthetic-key", "synthetic-model", createdAt = 1)
    private fun chunk(delta: JsonObject, finish: String? = null) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject { put("delta", delta); finish?.let { put("finish_reason", it) } }) })
    }.toString()
    private fun ordinary(text: String) = chunk(buildJsonObject { put("content", text) })
    private fun tool(arguments: String, name: String? = null, finish: String? = null) = chunk(buildJsonObject {
        put("tool_calls", buildJsonArray { add(buildJsonObject {
            put("index", 0); put("function", buildJsonObject {
                name?.let { put("name", it) }; put("arguments", arguments)
            })
        }) })
    }, finish)
    private fun sse(vararg parts: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(parts.joinToString("") { "data: $it\n\n" })
    private fun toolResponse(content: String = "story", finish: String = "tool_calls") = sse(
        tool(buildJsonObject { put("content", content) }.toString(), "game_content", finish), "[DONE]")
    private fun wire(server: MockWebServer) = Json.parseToJsonElement(
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)).body.readUtf8()).jsonObject
    private suspend fun <T> fixture(block: suspend (MockWebServer, OpenAiStreamingTransport) -> T): T {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport(experimentalApiModeEnabled = { true }, allowCleartextHttp = { true })
            try { return withTimeout(15_000) { block(server, transport) } }
            finally { transport.closeAndDrain() }
        }
    }

    @Test fun `main replaces ordinary preview with decoded tool text and preserves reasoning and usage`() = runBlocking {
        fixture { server, transport ->
            server.enqueue(sse(ordinary("preamble"),
                chunk(buildJsonObject { put("reasoning_content", "thought") }),
                tool("{\"content\":\"hel", "game_"), tool("lo\\nworld\"}", "content", "tool_calls"),
                """{"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3}}""", "[DONE]"))
            val events = transport.streamMainChat(messages, model(server)).toList()
            assertEquals(listOf("preamble", ""), events.filterIsInstance<ProviderStreamEvent.ContentPreview>().map { it.text })
            assertEquals("hello\nworld", events.filterIsInstance<ProviderStreamEvent.ContentDelta>().joinToString("") { it.text })
            assertEquals("thought", events.filterIsInstance<ProviderStreamEvent.ReasoningDelta>().single().text)
            assertEquals(7, events.filterIsInstance<ProviderStreamEvent.Usage>().single().usage.promptTokens)
            assertTrue(events.filterIsInstance<ProviderStreamEvent.Completed>().single().metadata.toolReplyCompleted)
            assertFalse(events.any { it is ProviderStreamEvent.Error })
            assertTrue("tools" in wire(server)); assertEquals(1, server.requestCount)
        }
    }

    @Test fun `one long lived owner rereads the flag for new requests and freezes it within each request`() = runBlocking {
        MockWebServer().use { server ->
            var enabled = true
            val transport = OpenAiStreamingTransport(experimentalApiModeEnabled = { enabled }, allowCleartextHttp = { true })
            try {
                server.enqueue(sse(ordinary("ordinary"), tool("{\"content\":\"tool story\"}", "game_content", "tool_calls"), "[DONE]"))
                val diagnostics = object : ProviderTransportDiagnostics {
                    override fun onResponseChunk(data: String, parsed: OpenAiSseChunk?) { enabled = false }
                }
                val first = transport.streamMainChat(messages, model(server), diagnostics = diagnostics).toList()
                assertEquals("tool story", first.filterIsInstance<ProviderStreamEvent.ContentDelta>().joinToString("") { it.text })
                assertTrue(first.filterIsInstance<ProviderStreamEvent.Completed>().single().metadata.toolReplyCompleted)
                assertTrue("tools" in wire(server))
                server.enqueue(sse(ordinary("legacy"), "[DONE]"))
                val second = transport.streamMainChat(messages, model(server)).toList()
                assertEquals("legacy", second.filterIsInstance<ProviderStreamEvent.ContentDelta>().single().text)
                assertFalse(second.any { it is ProviderStreamEvent.ContentPreview })
                assertFalse("tools" in wire(server))
                server.enqueue(sse(ordinary("override"), "[DONE]"))
                transport.streamMainChat(messages, model(server), experimentalApiMode = true).toList()
                assertTrue("tools" in wire(server))
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `ordinary experimental reply is previewed then committed once and default off body is unchanged`() = runBlocking {
        fixture { server, transport ->
            server.enqueue(sse(ordinary("one"), ordinary("two"), chunk(buildJsonObject {}, "stop"), "[DONE]"))
            val events = transport.streamMainChat(messages, model(server)).toList()
            assertEquals(listOf("one", "onetwo"), events.filterIsInstance<ProviderStreamEvent.ContentPreview>().map { it.text })
            assertEquals(listOf("onetwo"), events.filterIsInstance<ProviderStreamEvent.ContentDelta>().map { it.text })
            assertFalse(events.filterIsInstance<ProviderStreamEvent.Completed>().single().metadata.toolReplyCompleted)
            wire(server)
            server.enqueue(sse(ordinary("same"), "[DONE]"))
            transport.streamMainChat(messages, model(server), experimentalApiMode = false).toList()
            val request = wire(server)
            assertEquals(Json.parseToJsonElement(OpenAiChatRequestSerializer.serialize(messages, model(server),
                stream = true, allowCleartextHttp = true)), request)
        }
    }

    @Test fun `invalid tool fails while length refusal and cancellation never gain valid tool completion`() = runBlocking {
        fixture { server, transport ->
            for ((name, arguments) in listOf("unknown" to "{\"content\":\"x\"}",
                "game_content" to "{\"content\":\"incomplete")) {
                server.enqueue(sse(tool(arguments, name, "tool_calls"), "[DONE]"))
                val events = transport.streamMainChat(messages, model(server)).toList()
                assertEquals(1, events.count { it is ProviderStreamEvent.Error })
                assertFalse(events.any { it is ProviderStreamEvent.Completed })
                wire(server)
            }
            for (reason in listOf("length", "content_filter")) {
                server.enqueue(toolResponse("partial story", reason))
                val events = transport.streamMainChat(messages, model(server)).toList()
                val evidence = events.filterIsInstance<ProviderStreamEvent.Completed>().single().metadata
                assertFalse(evidence.toolReplyCompleted)
                assertNotNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion(evidence.finishReason,
                    evidence.refused, evidence.transportFailed, evidence.toolReplyCompleted), "partial story"))
                wire(server)
            }
            server.enqueue(sse(tool("{\"content\":\"partial", "game_content"), "[DONE]"))
            val events = mutableListOf<ProviderStreamEvent>()
            transport.streamMainChat(messages, model(server)).takeWhile {
                events += it; it !is ProviderStreamEvent.ContentDelta
            }.collect()
            assertEquals("partial", events.filterIsInstance<ProviderStreamEvent.ContentDelta>().single().text)
            assertFalse(events.any { it is ProviderStreamEvent.Completed }); wire(server)
        }
    }

    @Test fun `all isolated production helper producers use the same experimental wire and canonical output`() = runBlocking {
        fixture { server, transport ->
            val model = model(server)
            val producers = listOf<() -> Flow<StreamEvent>>(
                { transport.streamFormatRepair("rules", null, "source", model) },
                { transport.streamFishVoice(messages, model) },
                { transport.streamFormatCardAutoFill(messages, model) },
                { transport.streamCharacterCardCandidate(CharacterCardAiStage.entries.first(), messages, model) },
                { transport.streamCharacterResearch(CharacterResearchAiStage.entries.first(), messages, model) },
                { transport.streamWorldBookAi(WorldBookAiOperation.entries.first(), messages, model) },
                { transport.streamCharacterAppearance(messages, model) },
                { transport.streamMemoryRag(messages, model, false, false) },
                { transport.streamMoment(messages, model) },
            )
            for (producer in producers) {
                server.enqueue(sse(ordinary("ignore preamble"), tool("{\"content\":\"canonical\"}", "game_content", "tool_calls"), "[DONE]"))
                val events = producer().toList()
                assertEquals(StreamEvent.Done, events.last())
                assertEquals("canonical", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
                assertEquals(listOf("ignore preamble", ""), events.filterIsInstance<StreamEvent.ContentPreview>().map { it.text })
                val request = wire(server)
                assertEquals(listOf("system", "user"), request.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
                assertEquals(listOf("context", "game_content"), request.getValue("tools").jsonArray.map {
                    it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content })
            }
            assertEquals(producers.size, server.requestCount)
        }
    }

    @Test fun `auxiliary ordinary completion and invalid tool remain explicit`() = runBlocking {
        fixture { server, transport ->
            server.enqueue(sse(ordinary("plain"), ordinary(" text"), chunk(buildJsonObject {}, "stop"), "[DONE]"))
            val plain = transport.streamFishVoice(messages, model(server)).toList()
            assertEquals(listOf("plain text"), plain.filterIsInstance<StreamEvent.Delta>().map { it.text })
            assertEquals(StreamEvent.Done, plain.last()); wire(server)
            server.enqueue(sse(tool("{\"content\":\"x", "game_content", "tool_calls"), "[DONE]"))
            val bad = transport.streamFishVoice(messages, model(server)).toList()
            assertIs<StreamEvent.Error>(bad.last()); assertFalse(bad.any { it == StreamEvent.Done }); wire(server)
        }
    }

    @Test fun `decoded tool and ordinary previews retain the auxiliary response budget`() = runBlocking {
        MockWebServer().use { server ->
            val client = okhttp3.OkHttpClient()
            try {
                for (response in listOf(toolResponse("over budget"), sse(ordinary("over budget"), "[DONE]"))) {
                    server.enqueue(response)
                    val events = isolatedAuxiliaryTextStream(client, true, messages, model(server),
                        maximumTextChars = 3, experimentalApiMode = true).toList()
                    assertIs<com.example.chatbar.domain.prompt.AiTaskResponseBudgetException>(
                        events.filterIsInstance<StreamEvent.Error>().single().cause)
                    assertFalse(events.any { it == StreamEvent.Done }); wire(server)
                }
            } finally {
                client.dispatcher.cancelAll(); client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
                assertTrue(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `nonstream Moments and connection probe decode game content without another physical request`() = runBlocking {
        fixture { server, transport ->
            fun response() = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"content":"preamble","tool_calls":[{"function":{"name":"game_content","arguments":"{\"content\":\"answer\"}"}}]},"finish_reason":"tool_calls"}]}""")
            server.enqueue(response())
            assertEquals("answer", transport.completeMoment(messages, model(server)))
            assertTrue("tools" in wire(server))
            server.enqueue(response())
            assertEquals(ConnectionProbeStatus.SUCCESS, ModelConnectionProbe(true, true).run(model(server), null).chat.status)
            assertTrue("tools" in wire(server)); assertEquals(2, server.requestCount)
        }
    }

    @Test fun `automatic image eligibility requires actual complete tool evidence and normal finish`() {
        assertNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion("tool_calls", toolReplyCompleted = true), "story"))
        assertNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion("function_call", toolReplyCompleted = true), "story"))
        for (completion in listOf(ChatReplyCompletion("tool_calls"), ChatReplyCompletion("length", toolReplyCompleted = true),
            ChatReplyCompletion("tool_calls", refused = true, toolReplyCompleted = true),
            ChatReplyCompletion("tool_calls", transportFailed = true, toolReplyCompleted = true),
            ChatReplyCompletion(null, toolReplyCompleted = true)))
            assertNotNull(AutomaticChatImagePolicy.skipReason(completion, "story"))
    }
}
