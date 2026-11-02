package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage

enum class CharacterResearchAiStage { PLAN, SUMMARIZE }

data class CharacterResearchAiRequestPolicy(val thinkingBudget: Int? = null, val maxThinkingTokens: Int? = null,
    val enableThinking: Boolean? = null, val reasoningEffort: String? = null, val readTimeoutSeconds: Long = 600L) {
    companion object {
        fun forStage(stage: CharacterResearchAiStage) = when (stage) {
            CharacterResearchAiStage.PLAN, CharacterResearchAiStage.SUMMARIZE -> CharacterResearchAiRequestPolicy()
        }
    }
}

fun interface CharacterResearchAiGateway {
    suspend fun complete(stage: CharacterResearchAiStage, messages: List<ChatApiMessage>, modelConfig: ModelConfig,
        onDelta: (String) -> Unit, onReasoningDelta: (String) -> Unit): String
}
