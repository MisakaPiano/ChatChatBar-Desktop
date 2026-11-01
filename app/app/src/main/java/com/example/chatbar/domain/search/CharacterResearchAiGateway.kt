package com.example.chatbar.domain.search

import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage

internal fun characterResearchGateway(chat: StreamingChatService) = CharacterResearchAiGateway {
    stage, messages, model, onDelta, onReasoning ->
    val policy = CharacterResearchAiRequestPolicy.forStage(stage)
    chat.completeTextStreaming(messages = messages, modelConfig = model,
        taskContext = AiTaskContext(
            if (stage == CharacterResearchAiStage.PLAN) AiTaskKind.CHARACTER_RESEARCH else AiTaskKind.CHARACTER_BRIEF,
            if (stage == CharacterResearchAiStage.PLAN) AiTaskStage.PLAN else AiTaskStage.SUMMARIZE),
        enableThinking = policy.enableThinking, maxThinkingTokens = policy.maxThinkingTokens,
        thinkingBudget = policy.thinkingBudget, reasoningEffort = policy.reasoningEffort,
        readTimeoutSeconds = policy.readTimeoutSeconds, onDelta = onDelta, onReasoningDelta = onReasoning)
}
