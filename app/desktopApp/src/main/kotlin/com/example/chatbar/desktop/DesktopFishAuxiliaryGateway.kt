package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.ProviderTransportDiagnostics
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.voice.FishVoiceTextGateway
import com.example.chatbar.domain.voice.FishVoiceTextTask
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One logical translate/tag request retains its recorder across feature-owned retries. */
internal class DesktopFishAuxiliaryGateway(
    private val diagnostics: DesktopTransportDiagnosticsOwner?,
    private val stream: (List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>,
) : FishVoiceTextGateway {
    private class Run(val diagnostics: ProviderTransportDiagnostics) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Run>
    }

    override suspend fun <T> withLogicalRequest(task: FishVoiceTextTask, model: ModelConfig, block: suspend () -> T): T =
        withDesktopAuxiliaryUsage(diagnostics, model, "voice:${task.name}") { log ->
            var physicalAttempt = 0
            val attempts = object : ProviderTransportDiagnostics by log {
                override fun onAttemptStarted(attemptNumber: Int) { log.onAttemptStarted(++physicalAttempt) }
            }
            withContext(Run(attempts)) { block() }
        }

    override fun streamText(task: FishVoiceTextTask, messages: List<ChatApiMessage>, modelConfig: ModelConfig,
        disableThinking: Boolean): Flow<StreamEvent> = flow {
        val run = checkNotNull(currentCoroutineContext()[Run]) { "语音辅助请求缺少调用范围" }
        emitAll(stream(messages, modelConfig, run.diagnostics))
    }
}
