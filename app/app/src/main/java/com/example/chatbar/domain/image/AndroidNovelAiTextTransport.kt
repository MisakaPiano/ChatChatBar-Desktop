package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage
import com.example.chatbar.domain.prompt.withAiTaskRun

/** Retains Android task identity, request logging, refusal and completion checks. */
class AndroidNovelAiTextTransport(private val service: StreamingChatService) : NovelAiTextTransport {
    override suspend fun <T> withTaskRun(block: suspend () -> T): T = withAiTaskRun { block() }

    private fun context(stage: NovelAiTextStage) = AiTaskContext(
        if (stage == NovelAiTextStage.PLAN) AiTaskKind.IMAGE_RESEARCH else AiTaskKind.IMAGE_DESIGN,
        AiTaskStage.valueOf(stage.name),
    )

    override fun streamText(stage: NovelAiTextStage, messages: List<ChatApiMessage>, modelConfig: ModelConfig) =
        service.streamText(taskContext = context(stage), messages = messages, modelConfig = modelConfig)

    override suspend fun completeTextStreaming(
        stage: NovelAiTextStage,
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        onDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit,
    ): String = service.completeTextStreaming(taskContext = context(stage), messages = messages, modelConfig = modelConfig,
        onDelta = onDelta, onReasoningDelta = onReasoningDelta)
}
