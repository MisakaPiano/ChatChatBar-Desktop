package com.example.chatbar.domain.prompt

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage

/** Platform transport boundary. The caller retains prompts, validation and logical retry ownership. */
interface MemoryRagTextClient {
    suspend fun <T> withTaskRun(block: suspend () -> T): T = block()

    suspend fun completeText(
        taskContext: MemoryRagTask,
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        disableThinking: Boolean,
        isolatedTaskParameters: Boolean = true,
        responseFormatJson: Boolean = false,
    ): String = completeTextStreaming(taskContext, messages, modelConfig, disableThinking,
        isolatedTaskParameters, responseFormatJson) {}

    suspend fun completeTextStreaming(
        taskContext: MemoryRagTask,
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        disableThinking: Boolean,
        isolatedTaskParameters: Boolean = true,
        responseFormatJson: Boolean = false,
        onDelta: (String) -> Unit,
    ): String
}

data class MemoryRagTask(val kind: MemoryRagTaskKind, val stage: MemoryRagTaskStage)
enum class MemoryRagTaskKind { RETRIEVAL_PLAN, MEMORY_EPISODE, MEMORY_COMPRESSION_PLAN, MEMORY_COMPRESSION, MEMORY_HEAD }
enum class MemoryRagTaskStage { PLAN, SUMMARIZE, REPAIR }
