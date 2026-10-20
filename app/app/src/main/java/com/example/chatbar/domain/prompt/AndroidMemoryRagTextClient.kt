package com.example.chatbar.domain.prompt

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService

/** Keeps Android task diagnostics and transport parameters at their existing boundary. */
class AndroidMemoryRagTextClient(private val service: StreamingChatService) : MemoryRagTextClient {
    override suspend fun <T> withTaskRun(block: suspend () -> T): T = withAiTaskRun { block() }

    private fun MemoryRagTask.androidContext() = AiTaskContext(
        AiTaskKind.valueOf(kind.name), AiTaskStage.valueOf(stage.name))

    override suspend fun completeText(taskContext: MemoryRagTask, messages: List<ChatApiMessage>,
        modelConfig: ModelConfig, disableThinking: Boolean, isolatedTaskParameters: Boolean,
        responseFormatJson: Boolean): String = service.completeText(
        taskContext = taskContext.androidContext(), messages = messages, modelConfig = modelConfig,
        disableThinking = disableThinking, isolatedTaskParameters = isolatedTaskParameters,
        responseFormatJson = responseFormatJson)

    override suspend fun completeTextStreaming(taskContext: MemoryRagTask, messages: List<ChatApiMessage>,
        modelConfig: ModelConfig, disableThinking: Boolean, isolatedTaskParameters: Boolean,
        responseFormatJson: Boolean, onDelta: (String) -> Unit): String = service.completeTextStreaming(
        taskContext = taskContext.androidContext(), messages = messages, modelConfig = modelConfig,
        disableThinking = disableThinking, isolatedTaskParameters = isolatedTaskParameters,
        responseFormatJson = responseFormatJson, onDelta = onDelta)
}
