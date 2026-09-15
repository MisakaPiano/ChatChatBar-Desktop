package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig

/** Resolve task overrides from the original model, before isolated parameters are stripped. */
internal object ThinkingRequestPolicy {
    val legacyKeys = setOf("enable_thinking", "thinking_budget", "max_thinking_tokens")

    fun usesLegacyControls(model: ModelConfig): Boolean {
        if (model.enableThinking != null || model.customParams.keys.any { it in legacyKeys }) return true
        if (!model.reasoningEffort.isNullOrBlank() || "reasoning_effort" in model.customParams) return false
        return model.supportsDisableThinking
    }

    fun taskEffort(
        disableThinking: Boolean,
        enableThinking: Boolean?,
        thinkingBudget: Int?,
        maxThinkingTokens: Int?,
        reasoningEffort: String?
    ): String? = when {
        disableThinking || enableThinking == false -> "none"
        !reasoningEffort.isNullOrBlank() -> reasoningEffort
        thinkingBudget != null || maxThinkingTokens != null || enableThinking == true -> "low"
        else -> null
    }
}
