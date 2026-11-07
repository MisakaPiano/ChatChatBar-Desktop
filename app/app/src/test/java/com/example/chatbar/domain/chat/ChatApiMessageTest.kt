package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ImageDescriptionModelPolicyTest {
    @Test
    fun `image description request retains selected thinking params`() {
        val model = modelConfig(
            customParams = mapOf(
                "enable_thinking" to ParamValue.BooleanValue(true),
                "thinking_budget" to ParamValue.NumberValue(1024.0),
                "max_thinking_tokens" to ParamValue.NumberValue(128.0),
                "reasoning_effort" to ParamValue.StringValue("high"),
                "temperature" to ParamValue.NumberValue(0.7)
            ),
            enableThinking = true,
            reasoningEffort = "high"
        )

        val sanitized = model.forImageDescriptionRequest()

        assertEquals(ParamValue.BooleanValue(true), sanitized.customParams["enable_thinking"])
        assertEquals(ParamValue.NumberValue(1024.0), sanitized.customParams["thinking_budget"])
        assertEquals(ParamValue.NumberValue(128.0), sanitized.customParams["max_thinking_tokens"])
        assertEquals(ParamValue.StringValue("high"), sanitized.customParams["reasoning_effort"])
        assertEquals(ParamValue.NumberValue(0.7), sanitized.customParams["temperature"])
        assertEquals(true, sanitized.enableThinking)
        assertEquals("high", sanitized.reasoningEffort)
    }

    @Test
    fun `image description request does not add thinking flag`() {
        val sanitized = modelConfig().forImageDescriptionRequest()

        assertFalse(sanitized.customParams.containsKey("enable_thinking"))
        assertNull(sanitized.enableThinking)
        assertNull(sanitized.reasoningEffort)
    }

    private fun modelConfig(
        customParams: Map<String, ParamValue> = emptyMap(),
        enableThinking: Boolean? = null,
        reasoningEffort: String? = null
    ) = ModelConfig(
        id = "model-id",
        displayName = "Vision",
        baseUrl = "https://example.test/v1",
        apiKey = "key",
        modelName = "vision-model",
        isMultimodal = true,
        customParams = customParams,
        enableThinking = enableThinking,
        reasoningEffort = reasoningEffort,
        createdAt = 0L
    )
}
