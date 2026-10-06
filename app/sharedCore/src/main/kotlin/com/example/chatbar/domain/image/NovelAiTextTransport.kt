package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

enum class NovelAiTextStage { GENERATE, REPAIR, PLAN }

/** Platform transport boundary. Prompt construction and research remain shared. */
interface NovelAiTextTransport {
    suspend fun <T> withTaskRun(block: suspend () -> T): T = block()

    fun streamText(
        stage: NovelAiTextStage,
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
    ): Flow<StreamEvent>

    suspend fun completeTextStreaming(
        stage: NovelAiTextStage,
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        onDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit,
    ): String {
        val text = StringBuilder()
        var completed = false
        streamText(stage, messages, modelConfig).collect { event ->
            when (event) {
                is StreamEvent.Delta -> { text.append(event.text); onDelta(event.text) }
                is StreamEvent.ReasoningDelta -> onReasoningDelta(event.text)
                is StreamEvent.Error -> throw event.asException()
                is StreamEvent.Usage -> Unit
                StreamEvent.Done -> completed = true
            }
        }
        check(completed) { "Prompt 请求未正常完成" }
        return text.toString()
    }
}
