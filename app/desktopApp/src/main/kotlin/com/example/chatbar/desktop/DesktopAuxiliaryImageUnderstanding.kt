package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/** Desktop transport adapter; prompts, envelope, resolver and failure policy are shared. */
internal class DesktopAuxiliaryImageUnderstanding(private val transport: OpenAiStreamingTransport) {
    suspend fun describe(encoded: String, model: ModelConfig, onDelta: (String) -> Unit): String {
        val messages = AuxiliaryMessageAssembler.assemble(listOf(
            ChatApiMessage.text("system", AuxiliaryPromptAuthority.IMAGE_DESCRIPTION_PROMPT),
            ChatApiMessage.withImage("user", "", encoded),
        ), preservesInputRoles = false)
        val text = StringBuilder()
        var complete = false
        var reasoning = false
        try {
            transport.streamMainChat(messages, model.forImageDescriptionRequest()).collect { event ->
                when (event) {
                    is ProviderStreamEvent.ContentDelta -> { text.append(event.text); onDelta(event.text) }
                    is ProviderStreamEvent.ReasoningDelta -> reasoning = true
                    is ProviderStreamEvent.Completed -> {
                        AiTaskRefusalPolicy.failure(text.toString(), event.metadata.finishReason, event.metadata.refused)?.let { throw it }
                        check(!event.metadata.transportFailed && event.metadata.finishReason != "length") { "图片描述未完整完成" }
                        if (text.isBlank()) throw AiTaskEmptyResponseException(reasoning)
                        complete = true
                    }
                    is ProviderStreamEvent.Error -> {
                        AiTaskRefusalPolicy.failure(text.toString(), event.metadata.finishReason, event.metadata.refused)?.let { throw it }
                        error("图片理解请求失败")
                    }
                    is ProviderStreamEvent.Usage -> Unit
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (refusal: AiTaskRefusalException) { throw refusal }
        catch (_: Exception) { error("图片理解请求失败") }
        check(complete) { "图片理解未正常完成" }
        return text.toString().replace(Regex("\\s+"), " ").trim()
    }
}
