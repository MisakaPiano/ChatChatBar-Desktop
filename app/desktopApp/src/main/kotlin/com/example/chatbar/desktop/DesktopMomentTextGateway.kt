package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.moment.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** Request evidence is bounded/process-local; task kind belongs to each actual request. */
internal class DesktopMomentTextGateway(
    private val transport: OpenAiStreamingTransport,
    private val diagnostics: DesktopTransportDiagnosticsOwner,
) : MomentTextGateway {
    private fun recorder(operation: MomentTextOperation, model: ModelConfig) = diagnostics.begin(
        UUID.randomUUID().toString(), "moments:${operation.name}", model)

    override suspend fun completeText(messages: List<ChatApiMessage>, modelConfig: ModelConfig,
        operation: MomentTextOperation): String {
        val log = recorder(operation, modelConfig)
        return try {
            transport.completeMoment(messages, modelConfig, log).also { log.finish(DesktopTaskStatus.COMPLETED, null, null) }
        } catch (cancelled: CancellationException) {
            log.onCancelled(); log.finish(DesktopTaskStatus.USER_STOPPED, null, null); throw cancelled
        } catch (error: Exception) {
            log.finish(DesktopTaskStatus.FAILED, "朋友圈文本请求未完成", null); throw error
        }
    }

    override fun streamText(messages: List<ChatApiMessage>, modelConfig: ModelConfig,
        operation: MomentTextOperation): Flow<StreamEvent> = flow {
        val log = recorder(operation, modelConfig)
        try {
            transport.streamMoment(messages, modelConfig, log).collect { event ->
                if (event is StreamEvent.Error) throw event.cause ?: ModelRequestException("朋友圈文本请求未完成")
                emit(event)
            }
            log.finish(DesktopTaskStatus.COMPLETED, null, null)
        } catch (cancelled: CancellationException) {
            log.onCancelled(); log.finish(DesktopTaskStatus.USER_STOPPED, null, null); throw cancelled
        } catch (error: Exception) {
            log.finish(DesktopTaskStatus.FAILED, "朋友圈文本请求未完成", null); throw error
        }
    }
}
