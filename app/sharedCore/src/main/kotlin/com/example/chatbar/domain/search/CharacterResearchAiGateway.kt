package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage

enum class CharacterResearchAiStage { PLAN, SUMMARIZE }

data class CharacterResearchAiRequestPolicy(val thinkingBudget: Int, val maxThinkingTokens: Int,
    val enableThinking: Boolean = false, val reasoningEffort: String = "low", val readTimeoutSeconds: Long = 600L) {
    companion object {
        fun forStage(stage: CharacterResearchAiStage) = when (stage) {
            CharacterResearchAiStage.PLAN -> CharacterResearchAiRequestPolicy(64, 64)
            CharacterResearchAiStage.SUMMARIZE -> CharacterResearchAiRequestPolicy(128, 128)
        }
    }
}

fun interface CharacterResearchAiGateway {
    suspend fun complete(stage: CharacterResearchAiStage, messages: List<ChatApiMessage>, modelConfig: ModelConfig,
        onDelta: (String) -> Unit, onReasoningDelta: (String) -> Unit): String
}
