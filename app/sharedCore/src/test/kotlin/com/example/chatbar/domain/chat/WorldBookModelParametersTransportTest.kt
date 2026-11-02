package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.worldbook.WorldBookAiOperation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorldBookModelParametersTransportTest {
    private fun verify(operation: WorldBookAiOperation) = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                val common = mapOf("temperature" to ParamValue.NumberValue(0.65), "seed" to ParamValue.NumberValue(317.0),
                    "top_p" to ParamValue.NumberValue(0.85))
                val legacy = ModelConfig("fixture", "Synthetic", server.url("/v1").toString(), "fixture-key", "fixture-model",
                    enableThinking = true, reasoningEffort = "high", maxOutputTokens = 953, createdAt = 0,
                    customParams = common + ("thinking_budget" to ParamValue.NumberValue(611.0)))
                val reasoning = legacy.copy(enableThinking = null, reasoningEffort = "medium",
                    outputTokenParameter = OutputTokenParameter.MAX_COMPLETION_TOKENS, customParams = common)
                val messages = listOf(ChatApiMessage.text("system", "Synthetic task"), ChatApiMessage.text("user", "Synthetic input"))
                for (model in listOf(legacy, reasoning)) {
                    server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"reasoning\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"result\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"))
                    val events = withTimeout(10_000) { transport.streamWorldBookAi(operation, messages, model).toList() }
                    assertIs<StreamEvent.Done>(events.last())
                    assertEquals("reasoning", events.filterIsInstance<StreamEvent.ReasoningDelta>().single().text)
                    assertEquals("result", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
                    val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals("/v1/chat/completions", request.path)
                    assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
                    val actual = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val expected = Json.parseToJsonElement(OpenAiChatRequestSerializer.serialize(
                        AuxiliaryMessageAssembler.assemble(messages, preservesInputRoles = false), model, stream = true,
                        allowCleartextHttp = true, isolatedTaskParameters = false, disableThinking = false,
                        includeStreamUsage = model.supportsOpenAiPromptCacheInstrumentation()))
                    assertEquals(expected, actual)
                    assertEquals(953, actual.getValue(if (model === legacy) "max_tokens" else "max_completion_tokens").jsonPrimitive.int)
                    assertEquals(if (model === legacy) "high" else "medium", actual.getValue("reasoning_effort").jsonPrimitive.content)
                    assertEquals(0.65, actual.getValue("temperature").jsonPrimitive.double)
                    assertEquals(317, actual.getValue("seed").jsonPrimitive.int)
                    assertEquals(0.85, actual.getValue("top_p").jsonPrimitive.double)
                    if (model === legacy) {
                        assertTrue(actual.getValue("enable_thinking").jsonPrimitive.boolean)
                        assertEquals(611, actual.getValue("thinking_budget").jsonPrimitive.int)
                    } else assertFalse("enable_thinking" in actual)
                    assertEquals(listOf("system", "assistant", "user", "assistant", "user", "assistant", "assistant", "user"),
                        actual.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
                    assertEquals(messages.last().content, actual.getValue("messages").jsonArray[4].jsonObject.getValue("content"))
                }
                assertEquals(2, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `create retains configured model fields on the actual HTTP request`() = verify(WorldBookAiOperation.CREATE)
    @Test fun `fill retains configured model fields on the actual HTTP request`() = verify(WorldBookAiOperation.FILL)
    @Test fun `research inherits thinking and effort instead of forcing false and low`() = verify(WorldBookAiOperation.RESEARCH)
    @Test fun `brief inherits thinking and effort instead of forcing false and low`() = verify(WorldBookAiOperation.BRIEF)
}
