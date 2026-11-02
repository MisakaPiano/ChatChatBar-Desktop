package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.OpenAiSseChunk
import com.example.chatbar.domain.chat.ProviderCompletionMetadata
import com.example.chatbar.domain.chat.ProviderRequestEvidence
import com.example.chatbar.domain.chat.ProviderTransportDiagnostics
import com.example.chatbar.domain.chat.OpenAiSseParser
import com.example.chatbar.domain.chat.PromptCacheUsage
import com.example.chatbar.domain.chat.MainChatLogicalMessageSource
import com.example.chatbar.domain.chat.MainChatLogicalMessageTrace
import com.example.chatbar.domain.budget.PromptBudgetEstimate
import com.example.chatbar.domain.budget.PromptEstimateTarget
import com.example.chatbar.domain.budget.WirePromptBudgetEstimator
import kotlinx.serialization.json.JsonPrimitive
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
    val usage: PromptCacheUsage? = null,
    val outputText: String = "",
    val reasoningText: String = "",
    val outputTruncated: Boolean = false,
    val reasoningTruncated: Boolean = false,
    val requestTruncated: Boolean = false,
    val worldBookEvidence: List<String> = emptyList(),
    val ragText: String = "",
    val archiveIncluded: Boolean = false,
    val headIncluded: Boolean = false,
    val planningNotice: String? = null,
    val requestTextBudget: PromptBudgetEstimate? = null,
    val requestTextBudgetUnavailable: Boolean = false,
    val media: DesktopMediaRequestEvidence? = null,
    val image: DesktopImageRequestEvidence? = null,
)

/** Bounded, process-local final-provider-request evidence. Never participates in request construction. */
internal class DesktopTransportDiagnosticsOwner(
    private val maxEntries: Int = 40,
    private val clock: () -> Long = System::currentTimeMillis,
    private val usageSink: ((DesktopUsageRecord) -> Unit)? = null,
) {
    init { require(maxEntries > 0) }

    private val lock = Any()
    private val mutableEntries = MutableStateFlow<List<DesktopTransportDiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DesktopTransportDiagnosticEntry>> = mutableEntries.asStateFlow()

    fun clear(sessionId: String) = synchronized(lock) {
        mutableEntries.value = mutableEntries.value.filterNot { it.sessionId == sessionId }
    }

    fun beginFishMedia(sessionId: String, modelId: String, credential: String, accountId: String?,
        context: DesktopUsageContext, sentCodePoints: Long): DesktopMediaDiagnosticRecorder {
        val scrubber = DesktopDiagnosticScrubber(credential)
        return DesktopMediaDiagnosticRecorder(this, sessionId, scrubber.text(modelId, SHORT_LIMIT), scrubber, clock,
            usageSink?.let { sink -> DesktopUsageAttemptRecorder(context, scrubber.text(modelId, SHORT_LIMIT),
                scrubber.text(modelId, SHORT_LIMIT), "Fish Audio", accountId?.let { scrubber.text(it, SHORT_LIMIT) }, sink,
                clock, DesktopUsageMedia(DesktopUsageMediaKind.FISH_TTS, inputTextCodePoints = sentCodePoints)) }, sentCodePoints)
    }

    internal fun addMedia(entry: DesktopTransportDiagnosticEntry) = synchronized(lock) {
        mutableEntries.value = (listOf(entry) + mutableEntries.value).take(maxEntries)
    }

    fun beginNovelAiImages(sessionId: String, modelId: String, credential: String, accountId: String?,
        context: DesktopUsageContext, count: Long): DesktopImageDiagnosticRecorder {
        val scrubber = DesktopDiagnosticScrubber(credential)
        val model = scrubber.text(modelId, SHORT_LIMIT)
        return DesktopImageDiagnosticRecorder(this, sessionId, model, scrubber, clock,
            usageSink?.let { sink -> DesktopUsageAttemptRecorder(context, model, model, "NovelAI",
                accountId?.let { scrubber.text(it, SHORT_LIMIT) }, sink, clock,
                DesktopUsageMedia(DesktopUsageMediaKind.NAI_IMAGE_GENERATION, requestedImages = count)) }, count)
    }

    fun begin(taskId: String, sessionId: String, model: ModelConfig,
        usageContext: DesktopUsageContext = DesktopUsageContext(DesktopUsageSource.OTHER)): DesktopTransportDiagnosticRecorder {
        val scrubber = DesktopDiagnosticScrubber(model.apiKey)
        synchronized(lock) {
            mutableEntries.value = (
                listOf(
                    DesktopTransportDiagnosticEntry(
                        taskId = taskId,
                        sessionId = sessionId,
                        modelDisplayName = scrubber.text(model.desktopAccountLabel(), SHORT_LIMIT),
                        modelName = scrubber.text(model.modelName, SHORT_LIMIT),
                        startedAt = clock(),
                    ),
                ) + mutableEntries.value
            ).take(maxEntries)
        }
        return DesktopTransportDiagnosticRecorder(this, taskId, scrubber, clock,
            PromptEstimateTarget(scrubber.text(model.desktopAccountLabel(), SHORT_LIMIT)), model.baseUrl,
            usageSink?.let { sink -> DesktopUsageAttemptRecorder(usageContext,
                scrubber.text(model.id, SHORT_LIMIT), scrubber.text(model.modelName, SHORT_LIMIT),
                model.credentialAccount?.provider?.let { scrubber.text(it, SHORT_LIMIT) },
                model.credentialAccount?.id?.let { scrubber.text(it, SHORT_LIMIT) }, sink, clock) })
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
        const val OUTPUT_LIMIT = 65_536
    }
}

internal class DesktopTransportDiagnosticRecorder(
    private val owner: DesktopTransportDiagnosticsOwner,
    private val taskId: String,
    private val scrubber: DesktopDiagnosticScrubber,
    private val clock: () -> Long,
    private val estimateTarget: PromptEstimateTarget,
    private val modelBaseUrl: String,
    private val usageRecorder: DesktopUsageAttemptRecorder? = null,
) : ProviderTransportDiagnostics, DesktopChatPlanDiagnostics {
    @Volatile private var requestTrace: List<MainChatLogicalMessageTrace> = emptyList()
    fun safeText(value: String): String = scrubber.text(value, DesktopTransportDiagnosticsOwner.SHORT_LIMIT)

    override fun onPlan(plan: DesktopChatRequestPlan) {
        val trace = plan.assembly.messageTrace
        requestTrace = trace
        owner.update(taskId) { entry -> entry.copy(
            worldBookEvidence = plan.worldBookDebugLog.take(80).map(::safeText),
            ragText = scrubber.text(trace.filter { it.source == MainChatLogicalMessageSource.MEMORY_RAG }
                .joinToString("\n\n") { (it.message.content as? JsonPrimitive)?.content ?: it.message.content.toString() },
                DesktopTransportDiagnosticsOwner.OUTPUT_LIMIT),
            archiveIncluded = trace.any { it.source == MainChatLogicalMessageSource.ARCHIVE },
            headIncluded = trace.any { it.source == MainChatLogicalMessageSource.HEAD_TIMELINE },
            planningNotice = plan.knowledgeNotice?.let(::safeText),
        ) }
    }

    override fun onRequest(request: ProviderRequestEvidence) {
        // Count complete in-memory wire before display scrubbing/truncation; retain only numeric sections.
        val budget = try { WirePromptBudgetEstimator.estimate(request.body, estimateTarget, requestTrace,
            allowCleartextHttp = true, baseUrl = modelBaseUrl).let { report ->
                report.copy(sections = report.sections.map { it.copy(label = safeText(it.label), note = safeText(it.note)) })
            } } catch (_: Exception) { null }
        requestTrace = emptyList()
        owner.update(taskId) { entry ->
            entry.copy(
                requestAt = clock(),
                requestUrl = scrubber.url(request.url),
                serializedRequestBody = scrubber.json(request.body, DesktopTransportDiagnosticsOwner.REQUEST_LIMIT),
                requestTruncated = request.body.length > DesktopTransportDiagnosticsOwner.REQUEST_LIMIT,
                requestTextBudget = budget,
                requestTextBudgetUnavailable = budget == null,
            )
        }
    }

    override fun onResponseChunk(data: String, parsed: OpenAiSseChunk?) {
        val reportedUsage = OpenAiSseParser.parseUsage(data)
        reportedUsage?.let { usageRecorder?.usage(it) }
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
            val output = entry.outputText + parsed?.content.orEmpty()
            val reasoning = entry.reasoningText + parsed?.reasoningContent.orEmpty()
            entry.copy(
                chunks = (entry.chunks + chunk).takeLast(DesktopTransportDiagnosticsOwner.MAX_CHUNKS),
                droppedChunkCount = entry.droppedChunkCount +
                    if (entry.chunks.size >= DesktopTransportDiagnosticsOwner.MAX_CHUNKS) 1 else 0,
                outputText = scrubber.text(output, DesktopTransportDiagnosticsOwner.OUTPUT_LIMIT),
                reasoningText = scrubber.text(reasoning, DesktopTransportDiagnosticsOwner.OUTPUT_LIMIT),
                outputTruncated = entry.outputTruncated || output.length > DesktopTransportDiagnosticsOwner.OUTPUT_LIMIT,
                reasoningTruncated = entry.reasoningTruncated || reasoning.length > DesktopTransportDiagnosticsOwner.OUTPUT_LIMIT,
                usage = reportedUsage ?: entry.usage,
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

    override fun onAttemptStarted(attemptNumber: Int) { usageRecorder?.attempt(attemptNumber) }

    override fun onCancelled() {
        owner.update(taskId) { it.copy(cancellationObserved = true) }
    }

    fun finish(status: DesktopTaskStatus, error: String?, completion: ProviderCompletionMetadata?) {
        usageRecorder?.finish(when (status) {
            DesktopTaskStatus.COMPLETED -> DesktopUsageOutcome.COMPLETED
            DesktopTaskStatus.FAILED -> DesktopUsageOutcome.FAILED
            DesktopTaskStatus.USER_STOPPED, DesktopTaskStatus.CANCELLED -> DesktopUsageOutcome.CANCELLED
            DesktopTaskStatus.RUNNING -> DesktopUsageOutcome.RUNNING
        })
        requestTrace = emptyList()
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

internal interface DesktopChatPlanDiagnostics {
    fun onPlan(plan: DesktopChatRequestPlan)
}
