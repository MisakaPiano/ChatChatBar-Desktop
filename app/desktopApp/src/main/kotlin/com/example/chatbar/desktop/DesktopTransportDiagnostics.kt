package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.OpenAiSseChunk
import com.example.chatbar.domain.chat.ProviderCompletionMetadata
import com.example.chatbar.domain.chat.ProviderRequestEvidence
import com.example.chatbar.domain.chat.ProviderTransportDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DesktopTransportChunkEvidence(
    val timestamp: Long,
    val rawPreview: String,
    val contentPreview: String?,
    val reasoningPreview: String?,
    val finishReason: String?,
    val refused: Boolean,
)

data class DesktopTransportDiagnosticEntry(
    val taskId: String,
    val sessionId: String,
    val modelDisplayName: String,
    val modelName: String,
    val startedAt: Long,
    val requestAt: Long? = null,
    val completedAt: Long? = null,
    val requestUrl: String? = null,
    val serializedRequestBody: String? = null,
    val retryEvents: List<String> = emptyList(),
    val chunks: List<DesktopTransportChunkEvidence> = emptyList(),
    val droppedChunkCount: Int = 0,
    val cancellationObserved: Boolean = false,
    val status: DesktopTaskStatus = DesktopTaskStatus.RUNNING,
    val error: String? = null,
    val finishReason: String? = null,
    val transportFailed: Boolean? = null,
)

/** Bounded, process-local final-provider-request evidence. Never participates in request construction. */
internal class DesktopTransportDiagnosticsOwner(
    private val maxEntries: Int = 40,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { require(maxEntries > 0) }

    private val lock = Any()
    private val mutableEntries = MutableStateFlow<List<DesktopTransportDiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DesktopTransportDiagnosticEntry>> = mutableEntries.asStateFlow()

    fun begin(taskId: String, sessionId: String, model: ModelConfig): DesktopTransportDiagnosticRecorder {
        val scrubber = DesktopDiagnosticScrubber(model.apiKey)
        synchronized(lock) {
            mutableEntries.value = (
                listOf(
                    DesktopTransportDiagnosticEntry(
                        taskId = taskId,
                        sessionId = sessionId,
                        modelDisplayName = scrubber.text(model.displayName, SHORT_LIMIT),
                        modelName = scrubber.text(model.modelName, SHORT_LIMIT),
                        startedAt = clock(),
                    ),
                ) + mutableEntries.value
            ).take(maxEntries)
        }
        return DesktopTransportDiagnosticRecorder(this, taskId, scrubber, clock)
    }

    internal fun update(taskId: String, change: (DesktopTransportDiagnosticEntry) -> DesktopTransportDiagnosticEntry) {
        synchronized(lock) {
            mutableEntries.value = mutableEntries.value.map { entry ->
                if (entry.taskId == taskId) change(entry) else entry
            }
        }
    }

    internal companion object {
        const val REQUEST_LIMIT = 65_536
        const val CHUNK_LIMIT = 4_096
        const val SHORT_LIMIT = 512
        const val MAX_CHUNKS = 32
        const val MAX_RETRIES = 8
    }
}

internal class DesktopTransportDiagnosticRecorder(
    private val owner: DesktopTransportDiagnosticsOwner,
    private val taskId: String,
    private val scrubber: DesktopDiagnosticScrubber,
    private val clock: () -> Long,
) : ProviderTransportDiagnostics {
    fun safeText(value: String): String = scrubber.text(value, DesktopTransportDiagnosticsOwner.SHORT_LIMIT)

    override fun onRequest(request: ProviderRequestEvidence) {
        owner.update(taskId) { entry ->
            entry.copy(
                requestAt = clock(),
                requestUrl = scrubber.url(request.url),
                serializedRequestBody = scrubber.json(request.body, DesktopTransportDiagnosticsOwner.REQUEST_LIMIT),
            )
        }
    }

    override fun onResponseChunk(data: String, parsed: OpenAiSseChunk?) {
        val chunk = DesktopTransportChunkEvidence(
            timestamp = clock(),
            rawPreview = scrubber.json(data, DesktopTransportDiagnosticsOwner.CHUNK_LIMIT),
            contentPreview = parsed?.content?.let { scrubber.text(it, DesktopTransportDiagnosticsOwner.SHORT_LIMIT) },
            reasoningPreview = parsed?.reasoningContent?.let {
                scrubber.text(it, DesktopTransportDiagnosticsOwner.SHORT_LIMIT)
            },
            finishReason = parsed?.finishReason?.let { scrubber.text(it, DesktopTransportDiagnosticsOwner.SHORT_LIMIT) },
            refused = parsed?.refused == true,
        )
        owner.update(taskId) { entry ->
            entry.copy(
                chunks = (entry.chunks + chunk).takeLast(DesktopTransportDiagnosticsOwner.MAX_CHUNKS),
                droppedChunkCount = entry.droppedChunkCount +
                    if (entry.chunks.size >= DesktopTransportDiagnosticsOwner.MAX_CHUNKS) 1 else 0,
            )
        }
    }

    override fun onRetry(retryNumber: Int, message: String) {
        owner.update(taskId) { entry ->
            entry.copy(retryEvents = (entry.retryEvents +
                "#$retryNumber ${scrubber.text(message, DesktopTransportDiagnosticsOwner.SHORT_LIMIT)}")
                .takeLast(DesktopTransportDiagnosticsOwner.MAX_RETRIES))
        }
    }

    override fun onCancelled() {
        owner.update(taskId) { it.copy(cancellationObserved = true) }
    }

    fun finish(status: DesktopTaskStatus, error: String?, completion: ProviderCompletionMetadata?) {
        owner.update(taskId) { entry ->
            entry.copy(
                status = status,
                completedAt = clock(),
                error = error?.let { scrubber.text(it, DesktopTransportDiagnosticsOwner.SHORT_LIMIT) },
                finishReason = completion?.finishReason?.let {
                    scrubber.text(it, DesktopTransportDiagnosticsOwner.SHORT_LIMIT)
                },
                transportFailed = completion?.transportFailed,
            )
        }
    }
}

internal typealias DesktopDiagnosticScrubber = com.example.chatbar.domain.chat.ModelDiagnosticScrubber
