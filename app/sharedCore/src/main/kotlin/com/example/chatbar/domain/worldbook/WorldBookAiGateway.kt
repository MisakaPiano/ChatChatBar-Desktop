package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage

enum class WorldBookAiOperation { CREATE, FILL, RESEARCH, BRIEF }
enum class WorldBookAiStage { GENERATE, REPAIR, PLAN, SUMMARIZE }

/** Complete-stream boundary: failures, refusal, cancellation and truncation must throw. */
fun interface WorldBookAiGateway {
    suspend fun complete(operation: WorldBookAiOperation, stage: WorldBookAiStage,
        messages: List<ChatApiMessage>, modelConfig: ModelConfig,
        onDelta: (String) -> Unit, onReasoningDelta: (String) -> Unit): String
}
