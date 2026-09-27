package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.OutputTokenParameter
import com.example.chatbar.data.local.entity.ParamValue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object OpenAiChatRequestSerializer {
    fun serialize(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        stream: Boolean,
        allowCleartextHttp: Boolean = false,
        maxTokens: Int? = null,
        enableThinkingOverride: Boolean? = null,
        maxThinkingTokens: Int? = null,
        thinkingBudget: Int? = null,
        reasoningEffortOverride: String? = null,
        promptCacheKey: String? = null,
        includeStreamUsage: Boolean = false,
        disableThinking: Boolean = false,
        isolatedTaskParameters: Boolean = false,
        responseFormatJson: Boolean = false,
    ): String {
        val legacyThinking = ThinkingRequestPolicy.usesLegacyControls(modelConfig)
        val taskEffort = ThinkingRequestPolicy.taskEffort(
            disableThinking,
            enableThinkingOverride,
            thinkingBudget,
            maxThinkingTokens,
            reasoningEffortOverride,
        )
        val requestMessages = CleartextHttpChatTemplatePolicy.adaptMessages(
            messages = messages,
            allowCleartextHttp = allowCleartextHttp,
            baseUrl = modelConfig.baseUrl,
        )
        val messagesArray = buildJsonArray {
            requestMessages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("content", message.content)
                })
            }
        }

        return buildJsonObject {
            put("model", modelConfig.modelName)
            put("messages", messagesArray)
            put("stream", stream)
            promptCacheKey?.takeIf(String::isNotBlank)?.let { put("prompt_cache_key", it) }
            if (stream && includeStreamUsage) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }

            modelConfig.customParams.forEach { (key, value) ->
                if (taskEffort != null && !legacyThinking && key in THINKING_PARAMETER_KEYS) return@forEach
                if (taskEffort != null && legacyThinking && key == PARAM_REASONING_EFFORT) return@forEach
                if (disableThinking && key in THINKING_PARAMETER_KEYS) return@forEach
                if (isolatedTaskParameters && key in ISOLATED_TASK_PARAMETER_KEYS) return@forEach
                if (maxTokens != null && key in OUTPUT_TOKEN_PARAMETER_KEYS) return@forEach
                when (value) {
                    is ParamValue.NumberValue -> {
                        val number = value.value
                        if (number % 1.0 == 0.0) put(key, number.toLong()) else put(key, number)
                    }
                    is ParamValue.BooleanValue -> put(key, value.value)
                    is ParamValue.StringValue -> put(key, value.value)
                }
            }

            val outputTokenLimit = maxTokens ?: modelConfig.maxOutputTokens.takeUnless { isolatedTaskParameters }
            if (outputTokenLimit != null) {
                when (modelConfig.outputTokenParameter) {
                    OutputTokenParameter.MAX_TOKENS -> put("max_tokens", outputTokenLimit)
                    OutputTokenParameter.MAX_COMPLETION_TOKENS -> put("max_completion_tokens", outputTokenLimit)
                }
            }

            if (taskEffort != null && !legacyThinking) {
                put(PARAM_REASONING_EFFORT, taskEffort)
            } else if (disableThinking) {
                put(PARAM_ENABLE_THINKING, false)
            } else {
                (reasoningEffortOverride ?: modelConfig.reasoningEffort)
                    .takeIf { taskEffort == null }
                    ?.takeIf(String::isNotBlank)
                    ?.let { put(PARAM_REASONING_EFFORT, it) }
                (enableThinkingOverride ?: modelConfig.enableThinking)
                    ?.let { put(PARAM_ENABLE_THINKING, it) }
                maxThinkingTokens?.let { put(PARAM_MAX_THINKING_TOKENS, it) }
                thinkingBudget?.let { put(PARAM_THINKING_BUDGET, it) }
            }
            if (responseFormatJson) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }.toString()
    }

    private const val PARAM_ENABLE_THINKING = "enable_thinking"
    private const val PARAM_THINKING_BUDGET = "thinking_budget"
    private const val PARAM_MAX_THINKING_TOKENS = "max_thinking_tokens"
    private const val PARAM_REASONING_EFFORT = "reasoning_effort"

    private val THINKING_PARAMETER_KEYS = setOf(
        PARAM_ENABLE_THINKING,
        PARAM_THINKING_BUDGET,
        PARAM_MAX_THINKING_TOKENS,
        PARAM_REASONING_EFFORT,
    )
    private val OUTPUT_TOKEN_PARAMETER_KEYS = setOf("max_tokens", "max_completion_tokens")
    private val ISOLATED_TASK_PARAMETER_KEYS = THINKING_PARAMETER_KEYS + OUTPUT_TOKEN_PARAMETER_KEYS + setOf(
        "temperature",
        "top_p",
        "top_k",
        "min_p",
        "stop",
        "presence_penalty",
        "frequency_penalty",
        "repetition_penalty",
        "seed",
    )
}
