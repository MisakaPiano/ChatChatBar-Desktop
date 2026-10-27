package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.flow.collect

/** Shared task prompts and parser stay upstream-owned; this boundary owns Desktop transport lifetime. */
internal class DesktopMemoryRagTextClient(
    private val transport: OpenAiStreamingTransport,
    private val beforeRequest: suspend () -> Unit = {},
) : MemoryRagTextClient {
    override suspend fun completeTextStreaming(taskContext: MemoryRagTask, messages: List<ChatApiMessage>,
        modelConfig: ModelConfig, disableThinking: Boolean, isolatedTaskParameters: Boolean,
        responseFormatJson: Boolean, onDelta: (String) -> Unit): String {
        check(isolatedTaskParameters)
        beforeRequest()
        val text = StringBuilder()
        var complete = false
        transport.streamMemoryRag(messages, modelConfig, disableThinking, responseFormatJson).collect { event ->
            when (event) {
                is StreamEvent.Delta -> { text.append(event.text); onDelta(event.text) }
                is StreamEvent.Error -> throw event.cause ?: ModelRequestException("记忆或检索请求未完成")
                StreamEvent.Done -> complete = true
                else -> Unit
            }
        }
        check(complete) { "记忆或检索请求缺少完成确认" }
        return text.toString()
    }
}
