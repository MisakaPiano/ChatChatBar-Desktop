package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FormatRepairSelectedModelParametersTest {
    @Test fun `actual format repair inherits selected model controls while protecting its message envelope`() = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                for (reasoningOnly in listOf(false, true)) {
                    val custom = mapOf("temperature" to ParamValue.NumberValue(0.37),
                        "stop" to ParamValue.StringValue("END"), "model" to ParamValue.StringValue("unrelated-model"),
                        "messages" to ParamValue.StringValue("unrelated-history"), "stream" to ParamValue.BooleanValue(false)) +
                        if (reasoningOnly) emptyMap() else mapOf("thinking_budget" to ParamValue.NumberValue(1536.0),
                            "max_thinking_tokens" to ParamValue.NumberValue(1024.0))
                    val model = ModelConfig("fixture", "Synthetic", server.url("/v1").toString(), "fixture-key",
                        "selected-model", createdAt = 1, maxOutputTokens = 317,
                        outputTokenParameter = if (reasoningOnly) OutputTokenParameter.MAX_COMPLETION_TOKENS else OutputTokenParameter.MAX_TOKENS,
                        enableThinking = if (reasoningOnly) null else true, reasoningEffort = "high",
                        supportsDisableThinking = !reasoningOnly, customParams = custom)
                    server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"synthetic repaired text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                    val events = withTimeout(10_000) {
                        transport.streamFormatRepair("synthetic format", "synthetic people", "synthetic source", model).toList()
                    }
                    assertIs<StreamEvent.Done>(events.last())
                    val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val cap = if (reasoningOnly) "max_completion_tokens" else "max_tokens"
                    assertEquals(317, body.getValue(cap).jsonPrimitive.int)
                    assertFalse((if (reasoningOnly) "max_tokens" else "max_completion_tokens") in body)
                    assertEquals(0.37, body.getValue("temperature").jsonPrimitive.double)
                    assertEquals("END", body.getValue("stop").jsonPrimitive.content)
                    assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
                    if (reasoningOnly) assertFalse("enable_thinking" in body) else {
                        assertTrue(body.getValue("enable_thinking").jsonPrimitive.boolean)
                        assertEquals(1536, body.getValue("thinking_budget").jsonPrimitive.int)
                        assertEquals(1024, body.getValue("max_thinking_tokens").jsonPrimitive.int)
                    }
                    assertEquals("selected-model", body.getValue("model").jsonPrimitive.content)
                    assertTrue(body.getValue("stream").jsonPrimitive.boolean)
                    assertTrue(body.getValue("messages") is JsonArray)
                    assertFalse(body.getValue("messages").toString().contains("unrelated-history"))
                    assertEquals(custom, model.customParams)
                }
                assertEquals(2, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }
}
