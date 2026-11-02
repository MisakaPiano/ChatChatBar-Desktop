package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage

internal class AndroidWorldBookAiGateway(private val chat: StreamingChatService) : WorldBookAiGateway {
    override suspend fun complete(operation: WorldBookAiOperation, stage: WorldBookAiStage,
        messages: List<ChatApiMessage>, modelConfig: ModelConfig,
        onDelta: (String) -> Unit, onReasoningDelta: (String) -> Unit): String {
        val context = AiTaskContext(when (operation) {
            WorldBookAiOperation.CREATE -> AiTaskKind.WORLD_BOOK_CREATE
            WorldBookAiOperation.FILL -> AiTaskKind.WORLD_BOOK_FILL
            WorldBookAiOperation.RESEARCH -> AiTaskKind.WORLD_BOOK_RESEARCH
            WorldBookAiOperation.BRIEF -> AiTaskKind.WORLD_BOOK_BRIEF
        }, when (stage) {
            WorldBookAiStage.GENERATE -> AiTaskStage.GENERATE
            WorldBookAiStage.REPAIR -> AiTaskStage.REPAIR
            WorldBookAiStage.PLAN -> AiTaskStage.PLAN
            WorldBookAiStage.SUMMARIZE -> AiTaskStage.SUMMARIZE
        })
        return chat.completeTextStreaming(taskContext = context, messages = messages, modelConfig = modelConfig,
            onDelta = onDelta, onReasoningDelta = onReasoningDelta)
    }
}
