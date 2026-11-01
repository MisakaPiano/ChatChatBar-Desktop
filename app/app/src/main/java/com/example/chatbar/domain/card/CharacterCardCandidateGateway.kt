package com.example.chatbar.domain.card

import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage

internal fun characterCardCandidateGenerator(chat: StreamingChatService) = CharacterCardCandidateGenerator {
    operation, stage, messages, model ->
    chat.streamText(
        taskContext = AiTaskContext(
            if (operation == CharacterCardAiOperation.FILL) AiTaskKind.CHARACTER_FILL else AiTaskKind.CHARACTER_REWRITE,
            if (stage == CharacterCardAiStage.GENERATE) AiTaskStage.GENERATE else AiTaskStage.REPAIR),
        messages = messages, modelConfig = model,
        thinkingBudget = if (stage == CharacterCardAiStage.GENERATE) 512 else 256,
        readTimeoutSeconds = CHARACTER_CARD_AI_READ_TIMEOUT_SECONDS)
}
