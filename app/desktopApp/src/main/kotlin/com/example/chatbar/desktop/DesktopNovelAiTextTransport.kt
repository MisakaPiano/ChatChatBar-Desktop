package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.NovelAiTextStage
import com.example.chatbar.domain.image.NovelAiTextTransport
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch

/** Provider transport only; uses the shared official auxiliary envelope and task semantics. */
internal class DesktopNovelAiTextTransport(
    private val stream: (List<ChatApiMessage>, ModelConfig) -> kotlinx.coroutines.flow.Flow<ProviderStreamEvent>,
) : NovelAiTextTransport {
    constructor(transport: OpenAiStreamingTransport) : this({ messages, model -> transport.streamMainChat(messages, model) })
    override fun streamText(stage: NovelAiTextStage, messages: List<ChatApiMessage>, modelConfig: ModelConfig) = flow {
        val input = AuxiliaryMessageAssembler.assemble(messages, stage == NovelAiTextStage.GENERATE)
        val text = StringBuilder()
        var reasoning = false
        var completed = false
        stream(input, modelConfig).collect { event ->
            when (event) {
                is ProviderStreamEvent.ContentDelta -> { text.append(event.text); emit(StreamEvent.Delta(event.text)) }
                is ProviderStreamEvent.ReasoningDelta -> { reasoning = true; emit(StreamEvent.ReasoningDelta(event.text)) }
                is ProviderStreamEvent.Usage -> emit(StreamEvent.Usage(event.usage))
                is ProviderStreamEvent.Completed -> {
                    AiTaskRefusalPolicy.failure(text.toString(), event.metadata.finishReason, event.metadata.refused)?.let { throw it }
                    if (event.metadata.transportFailed || event.metadata.finishReason == "length") throw DesktopDesignException(DesktopDesignFailure.RESPONSE)
                    if (text.isBlank()) throw AiTaskEmptyResponseException(reasoning)
                    completed = true
                    emit(StreamEvent.Done)
                }
                is ProviderStreamEvent.Error -> {
                    AiTaskRefusalPolicy.failure(text.toString(), event.metadata.finishReason, event.metadata.refused)?.let { throw it }
                    throw DesktopDesignException(desktopDesignProviderFailure(event))
                }
            }
        }
        if (!completed) throw DesktopDesignException(DesktopDesignFailure.RESPONSE)
    }.catch { failure ->
        failure.rethrowIfAiTaskTerminalFailure()
        if (failure is AiTaskEmptyResponseException) throw failure
        throw DesktopDesignException(desktopDesignFailure(failure))
    }
}

/** Shared transport has a fixed HTTP prefix; never forward its trailing provider body. */
internal fun desktopDesignProviderFailure(event: ProviderStreamEvent.Error): DesktopDesignFailure {
    val status = Regex("""^流式请求失败 \((\d{3})\)""").find(event.message)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        status == 401 || status == 403 -> DesktopDesignFailure.AUTH
        status != null -> DesktopDesignFailure.PROVIDER
        event.message.startsWith("解析 SSE 数据失败") || event.message.startsWith("流式连接已关闭") -> DesktopDesignFailure.RESPONSE
        event.cause != null -> desktopDesignFailure(requireNotNull(event.cause))
        event.metadata.transportFailed -> DesktopDesignFailure.NETWORK
        else -> DesktopDesignFailure.PROVIDER
    }
}
