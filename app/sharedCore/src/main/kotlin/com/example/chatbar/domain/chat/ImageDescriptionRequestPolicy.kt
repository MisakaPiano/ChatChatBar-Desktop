package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue

fun ModelConfig.forImageDescriptionRequest(): ModelConfig {
    val effortOnly = !ThinkingRequestPolicy.usesLegacyControls(this) &&
        (!reasoningEffort.isNullOrBlank() || PARAM_REASONING_EFFORT in customParams)
    val nextParams = customParams.toMutableMap().apply {
        if (containsKey(PARAM_ENABLE_THINKING)) {
            put(PARAM_ENABLE_THINKING, ParamValue.BooleanValue(false))
        }
        remove(PARAM_THINKING_BUDGET)
        remove(PARAM_MAX_THINKING_TOKENS)
        remove(PARAM_REASONING_EFFORT)
    }
    return copy(
        customParams = nextParams,
        enableThinking = enableThinking?.let { false },
        reasoningEffort = if (effortOnly) "none" else null
    )
}

private const val PARAM_ENABLE_THINKING = "enable_thinking"
private const val PARAM_THINKING_BUDGET = "thinking_budget"
private const val PARAM_MAX_THINKING_TOKENS = "max_thinking_tokens"
private const val PARAM_REASONING_EFFORT = "reasoning_effort"
