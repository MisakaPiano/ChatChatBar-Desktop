package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.NovelAiTextStage
import com.example.chatbar.domain.image.NovelAiTextTransport
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

/** Provider transport only; uses the shared official auxiliary envelope and task semantics. */
internal class DesktopNovelAiTextTransport(private val transport: OpenAiStreamingTransport) : NovelAiTextTransport {
    override fun streamText(stage: NovelAiTextStage, messages: List<ChatApiMessage>, modelConfig: ModelConfig) = flow {
        val input = AuxiliaryMessageAssembler.assemble(messages, stage == NovelAiTextStage.GENERATE)
        val text = StringBuilder()
        var reasoning = false
        var completed = false
        transport.streamMainChat(input, modelConfig).collect { event ->
            when (event) {
                is ProviderStreamEvent.ContentDelta -> { text.append(event.text); emit(StreamEvent.Delta(event.text)) }
                is ProviderStreamEvent.ReasoningDelta -> { reasoning = true; emit(StreamEvent.ReasoningDelta(event.text)) }
                is ProviderStreamEvent.Usage -> emit(StreamEvent.Usage(event.usage))
                is ProviderStreamEvent.Completed -> {
                    AiTaskRefusalPolicy.failure(text.toString(), event.metadata.finishReason, event.metadata.refused)?.let { throw it }
                    check(!event.metadata.transportFailed && event.metadata.finishReason != "length") { "Prompt 设计响应未完整完成" }
                    if (text.isBlank()) throw AiTaskEmptyResponseException(reasoning)
                    completed = true
                    emit(StreamEvent.Done)
                }
                is ProviderStreamEvent.Error -> {
                    AiTaskRefusalPolicy.failure(text.toString(), event.metadata.finishReason, event.metadata.refused)?.let { throw it }
                    error("Prompt 设计请求失败")
                }
            }
        }
        check(completed) { "Prompt 设计请求未正常结束" }
    }
}
