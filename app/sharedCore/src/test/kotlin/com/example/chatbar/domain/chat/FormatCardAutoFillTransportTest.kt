package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FormatCardAutoFillTransportTest {
    private fun response() = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"合成思考\"}}]}\n\n" +
        "data: {\"choices\":[{\"delta\":{\"content\":\"result\"},\"finish_reason\":\"stop\"}]}\n\n" +
        "data: [DONE]\n\n")

    @Test fun `actual format-card HTTP body matches official assembler serializer with owned thinking cap and custom fields`() = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                val character = CharacterCard("synthetic", "合成", basicSetting = "合成设定", editMode = CharacterEditMode.FREEFORM,
                    freeformCharacterText = "完整合成长文本\n".repeat(20_000), createdAt = 0, updatedAt = 0)
                val messages = listOf(ChatApiMessage.text("system", FormatCardAutoFillPromptAuthority.formatCardAutoFillSystemPrompt()),
                    ChatApiMessage.text("user", FormatCardAutoFillPromptAuthority.formatCardAutoFillUserPrompt(character, "合成要求", "合成名称")))
                val base = ModelConfig("synthetic-model", "Fixture", server.url("/v1").toString(), "fixture-key", "original-model",
                    enableThinking = true, maxOutputTokens = 913, outputTokenParameter = OutputTokenParameter.MAX_COMPLETION_TOKENS,
                    customParams = mapOf("temperature" to ParamValue.NumberValue(0.65), "seed" to ParamValue.NumberValue(731.0)), createdAt = 0)
                val collisions = mapOf("model" to ParamValue.StringValue("custom-model"),
                    "messages" to ParamValue.StringValue("synthetic-custom-message-field"),
                    "stream" to ParamValue.BooleanValue(false), "stream_options" to ParamValue.StringValue("synthetic-options"),
                    "prompt_cache_key" to ParamValue.StringValue("synthetic-cache-key"))
                for (params in listOf(base.customParams, base.customParams + collisions)) {
                    server.enqueue(response())
                    val model = base.copy(customParams = params)
                    val events = withTimeout(10_000) { transport.streamFormatCardAutoFill(messages, model).toList() }
                    assertIs<StreamEvent.Done>(events.last())
                    assertEquals("result", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
                    assertEquals("合成思考", events.filterIsInstance<StreamEvent.ReasoningDelta>().single().text)
                    val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
                    val actual = Json.parseToJsonElement(request.body.readUtf8())
                    val expected = Json.parseToJsonElement(OpenAiChatRequestSerializer.serialize(
                        AuxiliaryMessageAssembler.assemble(messages, preservesInputRoles = false), model,
                        stream = true, allowCleartextHttp = true, disableThinking = false,
                        isolatedTaskParameters = false, responseFormatJson = false,
                        includeStreamUsage = model.supportsOpenAiPromptCacheInstrumentation()))
                    assertEquals(expected, actual)
                    assertEquals(913, actual.jsonObject["max_completion_tokens"]!!.jsonPrimitive.int)
                    assertEquals(true, actual.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
                    if (params === base.customParams) {
                        assertEquals(listOf("system", "assistant", "user", "assistant", "user", "assistant", "assistant", "user"),
                            actual.jsonObject["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
                        assertEquals(messages.last().content, actual.jsonObject["messages"]!!.jsonArray[4].jsonObject["content"])
                    }
                }
                assertEquals(2, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }
}
