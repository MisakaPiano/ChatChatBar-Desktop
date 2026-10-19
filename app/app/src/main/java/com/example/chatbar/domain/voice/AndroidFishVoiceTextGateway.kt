package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

/** Android keeps its existing task identity/logging and text transport. */
class AndroidFishVoiceTextGateway(private val service: StreamingChatService) : FishVoiceTextGateway {
    override fun streamText(task: FishVoiceTextTask, messages: List<ChatApiMessage>,
        modelConfig: ModelConfig, disableThinking: Boolean): Flow<StreamEvent> = flow {
        withAiTaskRun {
            val context = when (task) {
                FishVoiceTextTask.TRANSLATE -> AiTaskContext(AiTaskKind.VOICE_TRANSLATION, AiTaskStage.TRANSLATE)
                FishVoiceTextTask.TAG -> AiTaskContext(AiTaskKind.VOICE_TAGS, AiTaskStage.TAG)
            }
            emitAll(service.streamText(messages = messages, modelConfig = modelConfig,
                taskContext = context, disableThinking = disableThinking))
        }
    }
}
