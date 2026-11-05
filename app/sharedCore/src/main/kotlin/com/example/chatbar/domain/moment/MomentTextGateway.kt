package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import kotlinx.coroutines.flow.Flow

enum class MomentTextOperation { JUDGE, GENERATE }

/** Both complete and streaming paths retain their original request mode and operation. */
interface MomentTextGateway {
    suspend fun completeText(messages: List<ChatApiMessage>, modelConfig: ModelConfig, operation: MomentTextOperation): String
    fun streamText(messages: List<ChatApiMessage>, modelConfig: ModelConfig, operation: MomentTextOperation): Flow<StreamEvent>
}
