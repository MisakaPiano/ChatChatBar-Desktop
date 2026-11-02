package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.memory.*
import com.example.chatbar.domain.rag.RetrievalPlanner
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopMemorySelectedModelParametersTest {
    private fun models(server: MockWebServer): List<ModelConfig> {
        val plain = ModelConfig("fixture", "Synthetic", server.url("/v1").toString(), "fixture-key", "selected-model", createdAt = 1)
        val custom = mapOf("temperature" to ParamValue.NumberValue(0.37), "top_p" to ParamValue.NumberValue(0.81),
            "seed" to ParamValue.NumberValue(271.0), "model" to ParamValue.StringValue("custom-selected-model"))
        val legacy = plain.copy(supportsJsonMode = true, supportsDisableThinking = true, enableThinking = true,
            reasoningEffort = "high", maxOutputTokens = 317, customParams = custom + ("thinking_budget" to ParamValue.NumberValue(1536.0)))
        return listOf(plain, legacy, legacy.copy(enableThinking = null, supportsDisableThinking = false,
            outputTokenParameter = OutputTokenParameter.MAX_COMPLETION_TOKENS, customParams = custom))
    }
    private fun response(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: " + buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("content", text) }); put("finish_reason", "stop")
        }) }) } + "\n\ndata: [DONE]\n\n")

    private fun assertWire(server: MockWebServer, model: ModelConfig, json: Boolean) {
        val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(8, body.getValue("messages").jsonArray.size)
        if (model.maxOutputTokens == null) {
            listOf("max_tokens", "max_completion_tokens", "enable_thinking", "reasoning_effort", "thinking_budget").forEach { assertFalse(it in body, it) }
        } else {
            assertEquals(317, body.getValue(if (model.outputTokenParameter == OutputTokenParameter.MAX_TOKENS) "max_tokens" else "max_completion_tokens").jsonPrimitive.int)
            assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
            assertEquals(0.37, body.getValue("temperature").jsonPrimitive.double)
            assertEquals(0.81, body.getValue("top_p").jsonPrimitive.double); assertEquals(271, body.getValue("seed").jsonPrimitive.int)
            assertEquals("custom-selected-model", body.getValue("model").jsonPrimitive.content)
            if (model.enableThinking == true) {
                assertTrue(body.getValue("enable_thinking").jsonPrimitive.boolean)
                assertEquals(1536, body.getValue("thinking_budget").jsonPrimitive.int)
            } else assertFalse("enable_thinking" in body)
        }
        if (json) assertEquals("json_object", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        else assertFalse("response_format" in body)
    }

    @Test fun `actual memory planner summary repair and streaming episode inherit the selected model`() = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                val gateway = MemoryAiGateway(DesktopMemoryRagTextClient(transport))
                for (model in models(server)) {
                    server.enqueue(response("synthetic selection plan")); server.enqueue(response("not json"))
                    server.enqueue(response("""{"compressible":true,"consumedChildIds":["child"],"summary":"combined"}"""))
                    val result = withTimeout(10_000) {
                        gateway.compression(model, MemoryCompressionKind.EPISODE_TO_ARC, listOf("child"), "synthetic children", null) {
                            require(it.compressible && it.consumedChildIds == listOf("child"))
                        }
                    }
                    assertEquals("combined", result.summary)
                    assertWire(server, model, false)
                    repeat(2) { assertWire(server, model, model.supportsJsonMode) }
                    server.enqueue(response("""{"summary":"synthetic episode"}"""))
                    val deltas = mutableListOf<String>()
                    val episode = withTimeout(10_000) { gateway.episode(model, "synthetic source", 70, { deltas.add(it) }) { require(it.summary.isNotBlank()) } }
                    assertEquals("synthetic episode", episode.summary); assertTrue(deltas.any { it == episode.summary })
                    assertWire(server, model, model.supportsJsonMode)
                }
                assertEquals(12, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `actual retrieval planner inherits selected output thinking and custom fields`() = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                val planner = RetrievalPlanner(DesktopMemoryRagTextClient(transport))
                for (model in models(server)) {
                    server.enqueue(response("""{"t":["fixture topic"],"q":["fixture query"],"e":[]}"""))
                    val result = withTimeout(10_000) { planner.plan("synthetic input", emptyList(), "synthetic card", model) }
                    assertEquals(listOf("fixture query"), assertNotNull(result.plan).queries)
                    assertWire(server, model, false)
                }
                assertEquals(3, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }
}
