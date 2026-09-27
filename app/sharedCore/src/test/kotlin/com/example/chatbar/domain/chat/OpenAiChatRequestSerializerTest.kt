package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.OutputTokenParameter
import com.example.chatbar.data.local.entity.ParamValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiChatRequestSerializerTest {
    @Test
    fun `https preserves logical roles while opted in http applies transport role repair`() {
        val messages = listOf(
            ChatApiMessage.text("system", "root"),
            ChatApiMessage.text("user", "question"),
            ChatApiMessage.text("system", "tail"),
        )

        val httpsRoles = body(messages, model()).getValue("messages").jsonArray
            .map { it.jsonObject.getValue("role").jsonPrimitive.content }
        val httpMessages = body(
            messages,
            model().copy(baseUrl = "http://localhost/v1"),
            allowCleartextHttp = true,
        ).getValue("messages").jsonArray

        assertEquals(listOf("system", "user", "system"), httpsRoles)
        assertEquals(listOf("system", "user"), httpMessages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals("question\n\ntail", httpMessages.last().jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `selected output alias replaces conflicting configured aliases and keeps custom values`() {
        val request = body(
            listOf(ChatApiMessage.text("user", "hello")),
            model().copy(
                customParams = mapOf(
                    "max_tokens" to ParamValue.NumberValue(10.0),
                    "max_completion_tokens" to ParamValue.NumberValue(20.0),
                    "temperature" to ParamValue.NumberValue(0.25),
                    "seed" to ParamValue.NumberValue(7.0),
                    "flag" to ParamValue.BooleanValue(true),
                ),
                maxOutputTokens = 30,
                outputTokenParameter = OutputTokenParameter.MAX_COMPLETION_TOKENS,
            ),
            maxTokens = 40,
        )

        assertFalse("max_tokens" in request)
        assertEquals("40", request.getValue("max_completion_tokens").jsonPrimitive.content)
        assertEquals("0.25", request.getValue("temperature").jsonPrimitive.content)
        assertEquals("7", request.getValue("seed").jsonPrimitive.content)
        assertTrue(request.getValue("flag").jsonPrimitive.boolean)
    }

    @Test
    fun `legacy thinking and effort modes never emit conflicting controls`() {
        val legacy = body(
            listOf(ChatApiMessage.text("user", "task")),
            model().copy(
                enableThinking = true,
                reasoningEffort = "high",
                customParams = mapOf("reasoning_effort" to ParamValue.StringValue("high")),
            ),
            thinkingBudget = 256,
        )
        val effort = body(
            listOf(ChatApiMessage.text("user", "task")),
            model().copy(reasoningEffort = "high"),
            maxThinkingTokens = 128,
        )

        assertEquals("256", legacy.getValue("thinking_budget").jsonPrimitive.content)
        assertEquals(true, legacy.getValue("enable_thinking").jsonPrimitive.boolean)
        assertFalse("reasoning_effort" in legacy)
        assertEquals("low", effort.getValue("reasoning_effort").jsonPrimitive.content)
        assertFalse("enable_thinking" in effort)
        assertFalse("thinking_budget" in effort)
        assertFalse("max_thinking_tokens" in effort)
    }

    @Test
    fun `explicit disable wins and isolated json request strips inherited task controls`() {
        val request = body(
            listOf(ChatApiMessage.text("user", "task")),
            model().copy(
                customParams = mapOf(
                    "enable_thinking" to ParamValue.BooleanValue(true),
                    "thinking_budget" to ParamValue.NumberValue(512.0),
                    "temperature" to ParamValue.NumberValue(0.8),
                    "stop" to ParamValue.StringValue("END"),
                    "max_tokens" to ParamValue.NumberValue(999.0),
                ),
                enableThinking = true,
                maxOutputTokens = 777,
            ),
            disableThinking = true,
            isolatedTaskParameters = true,
            responseFormatJson = true,
        )

        assertEquals(false, request.getValue("enable_thinking").jsonPrimitive.boolean)
        listOf("thinking_budget", "max_thinking_tokens", "reasoning_effort", "temperature", "stop", "max_tokens", "max_completion_tokens")
            .forEach { assertFalse(it in request, it) }
        assertEquals("json_object", request.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `stream cache options are emitted only from explicit serializer inputs`() {
        val request = body(
            listOf(ChatApiMessage.text("user", "hello")),
            model(),
            promptCacheKey = "cache-key",
            includeStreamUsage = true,
        )

        assertEquals("cache-key", request.getValue("prompt_cache_key").jsonPrimitive.content)
        assertTrue(request.getValue("stream_options").jsonObject.getValue("include_usage").jsonPrimitive.boolean)
        assertTrue(request.getValue("stream").jsonPrimitive.boolean)
    }

    private fun body(
        messages: List<ChatApiMessage>,
        model: ModelConfig,
        allowCleartextHttp: Boolean = false,
        maxTokens: Int? = null,
        thinkingBudget: Int? = null,
        maxThinkingTokens: Int? = null,
        disableThinking: Boolean = false,
        isolatedTaskParameters: Boolean = false,
        responseFormatJson: Boolean = false,
        promptCacheKey: String? = null,
        includeStreamUsage: Boolean = false,
    ) = Json.parseToJsonElement(OpenAiChatRequestSerializer.serialize(
        messages = messages,
        modelConfig = model,
        stream = true,
        allowCleartextHttp = allowCleartextHttp,
        maxTokens = maxTokens,
        thinkingBudget = thinkingBudget,
        maxThinkingTokens = maxThinkingTokens,
        disableThinking = disableThinking,
        isolatedTaskParameters = isolatedTaskParameters,
        responseFormatJson = responseFormatJson,
        promptCacheKey = promptCacheKey,
        includeStreamUsage = includeStreamUsage,
    )).jsonObject

    private fun model() = ModelConfig(
        id = "model",
        displayName = "Model",
        baseUrl = "https://example.test/v1",
        apiKey = "secret",
        modelName = "model-name",
        createdAt = 0,
    )
}
