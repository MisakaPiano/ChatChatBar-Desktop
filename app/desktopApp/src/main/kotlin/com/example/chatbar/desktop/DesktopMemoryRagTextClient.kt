package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Shared task prompts and parser stay upstream-owned; this boundary owns Desktop transport lifetime. */
internal class DesktopMemoryRagTextClient(
    private val transport: OpenAiStreamingTransport,
    private val beforeRequest: suspend () -> Unit = {},
    private val diagnostics: () -> DesktopTransportDiagnosticsOwner? = { null },
) : MemoryRagTextClient {
    private class Run(val owner: DesktopMemoryRagTextClient, val parent: DesktopUsageContext) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Run>
        private var model: ModelConfig? = null
        private var kind: MemoryRagTaskKind? = null
        private var recorder: DesktopTransportDiagnosticRecorder? = null
        private var physicalAttempt = 0
        private var lastTransportStatus: DesktopTaskStatus? = null

        fun transportStatus(status: DesktopTaskStatus) { lastTransportStatus = status }

        fun diagnostics(task: MemoryRagTask, selected: ModelConfig): ProviderTransportDiagnostics {
            // Different explicit purposes/models are different requests. A repair of the same
            // purpose/model keeps its request identity and only advances the physical attempt.
            if (model != selected || kind != task.kind) {
                finish(DesktopTaskStatus.COMPLETED)
                model = selected; kind = task.kind; physicalAttempt = 0
                recorder = owner.diagnostics()?.begin(UUID.randomUUID().toString(),
                    "memory-rag:${task.kind.name}", selected, parent)
            }
            val log = recorder ?: return ProviderTransportDiagnostics.NONE
            log.recordPromptStage(DesktopPromptDiagnosticContext(task.kind.name, task.stage.name))
            return object : ProviderTransportDiagnostics by log {
                override fun onAttemptStarted(attemptNumber: Int) { log.onAttemptStarted(++physicalAttempt) }
            }
        }
        fun finish(status: DesktopTaskStatus) {
            // RetrievalPlanner can return a fallback after swallowing a transport failure or
            // timeout. A normal caller return must not turn that failed physical stream green.
            val terminal = if (status == DesktopTaskStatus.COMPLETED &&
                lastTransportStatus in setOf(DesktopTaskStatus.FAILED, DesktopTaskStatus.USER_STOPPED))
                requireNotNull(lastTransportStatus) else status
            if (terminal == DesktopTaskStatus.USER_STOPPED) recorder?.onCancelled()
            recorder?.finish(terminal, if (terminal == DesktopTaskStatus.FAILED) "记忆或检索请求未完成" else null, null)
            recorder = null; model = null; kind = null; lastTransportStatus = null
        }
    }

    override suspend fun <T> withTaskRun(block: suspend () -> T): T {
        // Compression planning may precede a nested summary run. Entering that explicit child
        // ends the preceding purpose; a later summary failure must not relabel its plan failed.
        currentCoroutineContext()[Run]?.takeIf { it.owner === this }?.finish(DesktopTaskStatus.COMPLETED)
        val run = Run(this, currentCoroutineContext()[DesktopUsageParent]?.value ?: DesktopUsageContext(DesktopUsageSource.OTHER))
        return try { withContext(run) { block() }.also { run.finish(DesktopTaskStatus.COMPLETED) } }
        catch (cancelled: CancellationException) { run.finish(DesktopTaskStatus.USER_STOPPED); throw cancelled }
        catch (failure: Exception) { run.finish(DesktopTaskStatus.FAILED); throw failure }
    }

    override suspend fun completeTextStreaming(taskContext: MemoryRagTask, messages: List<ChatApiMessage>,
        modelConfig: ModelConfig, disableThinking: Boolean, isolatedTaskParameters: Boolean,
        responseFormatJson: Boolean, onDelta: (String) -> Unit): String {
        val run = currentCoroutineContext()[Run]?.takeIf { it.owner === this }
            ?: return withTaskRun { completeTextStreaming(taskContext, messages, modelConfig, disableThinking,
                isolatedTaskParameters, responseFormatJson, onDelta) }
        beforeRequest()
        val text = StringBuilder()
        var complete = false
        val log = run.diagnostics(taskContext, modelConfig)
        run.transportStatus(DesktopTaskStatus.RUNNING)
        return try {
            transport.streamMemoryRag(messages, modelConfig, disableThinking, responseFormatJson, log,
                isolatedTaskParameters = isolatedTaskParameters).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> { text.append(event.text); onDelta(event.text) }
                    is StreamEvent.Error -> throw event.cause ?: ModelRequestException("记忆或检索请求未完成")
                    StreamEvent.Done -> complete = true
                    else -> Unit
                }
            }
            check(complete) { "记忆或检索请求缺少完成确认" }
            run.transportStatus(DesktopTaskStatus.COMPLETED)
            text.toString()
        }
        catch (cancelled: CancellationException) { run.transportStatus(DesktopTaskStatus.USER_STOPPED); throw cancelled }
        catch (failure: Exception) { run.transportStatus(DesktopTaskStatus.FAILED); throw failure }
    }
}
