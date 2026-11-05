package com.example.chatbar.desktop

import com.example.chatbar.domain.chat.PromptCacheUsage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class DesktopUsageParent(val value: DesktopUsageContext) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopUsageParent>
}

internal class DesktopUsageDrainException(val pendingRecords: Int) :
    IllegalStateException("Local usage records are still pending: $pendingRecords")

internal class DesktopUsageAttemptRecorder(
    private val context: DesktopUsageContext,
    private val modelId: String, private val modelName: String,
    private val provider: String?, private val accountId: String?,
    private val publish: (DesktopUsageRecord) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val initialMedia: DesktopUsageMedia? = null,
) {
    private val lock = Any()
    private val requestId = UUID.randomUUID().toString()
    private var current: DesktopUsageRecord? = null
    private var closed = false

    fun attempt(number: Int) = synchronized(lock) {
        if (closed) return@synchronized
        require(number >= 1 && (current?.attempt ?: 0) < number)
        current?.let { publish(it.copy(revision = it.revision + 1, updatedAt = maxOf(clock(), it.startedAt),
            outcome = DesktopUsageOutcome.RETRIED)) }
        val now = clock()
        current = DesktopUsageRecord(UUID.randomUUID().toString(), requestId, number, 1, context.source,
            context.taskId, context.sessionId, context.cardId, modelId, modelName, provider, accountId, now, now,
            editorSessionId = context.editorSessionId, personId = context.personId, subjectId = context.subjectId,
            media = initialMedia, schemaVersion = if (initialMedia == null) 1 else 2)
        publish(requireNotNull(current))
    }
    fun usage(value: PromptCacheUsage) = synchronized(lock) {
        if (closed) return@synchronized
        val previous = current ?: return@synchronized
        check(previous.media == null) { "Media measurements are separate from token usage" }
        val counts = previous.counters.merge(value)
        if (counts != previous.counters) current = previous.copy(revision = previous.revision + 1,
            updatedAt = maxOf(clock(), previous.startedAt), counters = counts).also(publish)
    }
    fun finish(outcome: DesktopUsageOutcome) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        current?.let { publish(it.copy(revision = it.revision + 1,
            updatedAt = maxOf(clock(), it.startedAt), outcome = outcome)) }
    }
    fun localAudio(durationMs: Long, bytes: Long) = synchronized(lock) {
        if (closed) return@synchronized
        require(durationMs >= 0 && bytes >= 0)
        val previous = current ?: return@synchronized
        val media = requireNotNull(previous.media)
        require(media.kind == DesktopUsageMediaKind.FISH_TTS)
        val measured = media.copy(outputAudioDurationMs = durationMs, outputAudioBytes = bytes)
        if (measured != media) current = previous.copy(revision = previous.revision + 1,
            updatedAt = maxOf(clock(), previous.startedAt), media = measured).also(publish)
    }
    fun mediaResponse(code: Int) = synchronized(lock) {
        if (closed) return@synchronized
        require(code in 100..599)
        val previous = current ?: return@synchronized
        val media = requireNotNull(previous.media)
        if (media.httpStatus != code) current = previous.copy(revision = previous.revision + 1,
            updatedAt = maxOf(clock(), previous.startedAt), media = media.copy(httpStatus = code)).also(publish)
    }
    fun localImages(count: Long, bytes: Long) = synchronized(lock) {
        if (closed) return@synchronized
        require(count >= 0 && bytes >= 0)
        val previous = current ?: return@synchronized
        val media = requireNotNull(previous.media)
        require(media.kind in listOf(DesktopUsageMediaKind.NAI_IMAGE_GENERATION, DesktopUsageMediaKind.NAI_ENHANCE, DesktopUsageMediaKind.NAI_UPSCALE))
        val measured = media.copy(outputImages = count, outputImageBytes = bytes)
        if (measured != media) current = previous.copy(revision = previous.revision + 1,
            updatedAt = maxOf(clock(), previous.startedAt), media = measured).also(publish)
    }
    fun localEncoding(bytes: Long) = synchronized(lock) {
        if (closed) return@synchronized
        require(bytes > 0)
        val previous = current ?: return@synchronized
        val media = requireNotNull(previous.media)
        require(media.kind == DesktopUsageMediaKind.NAI_VIBE_ENCODING)
        val measured = media.copy(outputEncodingBytes = bytes)
        if (measured != media) current = previous.copy(revision = previous.revision + 1,
            updatedAt = maxOf(clock(), previous.startedAt), media = measured).also(publish)
    }
}

internal data class DesktopProviderUsageState(
    val pending: Int = 0, val saveError: String? = null, val readError: String? = null, val loading: Boolean = false,
    val totals: DesktopUsageTotals? = null, val recent: List<DesktopUsageRecord> = emptyList(),
    val breakdown: DesktopUsageBreakdownPage = DesktopUsageBreakdownPage(),
) {
    val error: String? get() = listOfNotNull(saveError, readError).takeIf { it.isNotEmpty() }?.joinToString("；")
}

/** Coalesces cumulative snapshots by attempt; failed writes remain pending and visible, never fail a chat reply. */
internal class DesktopProviderUsageOwner(private val store: DesktopProviderUsageStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private val pending = linkedMapOf<String, DesktopUsageRecord>()
    private var accepting = true
    private val closeMutex = Mutex()
    private var closed = false
    private var readSequence = 0L
    private val mutableState = MutableStateFlow(DesktopProviderUsageState())
    val state = mutableState.asStateFlow()
    private val writer = scope.launch { for (ignored in signal) flush() }

    fun submit(record: DesktopUsageRecord) = synchronized(lock) {
        check(accepting)
        val old = pending[record.id]
        if (old == null || old.revision < record.revision) pending[record.id] = record
        mutableState.value = mutableState.value.copy(pending = pending.size)
        signal.trySend(Unit)
        Unit
    }
    private suspend fun flush() {
        while (true) {
            val record = synchronized(lock) { pending.values.firstOrNull() } ?: return
            try {
                store.save(record)
                synchronized(lock) {
                    if (pending[record.id]?.revision == record.revision) pending.remove(record.id)
                    mutableState.value = mutableState.value.copy(pending = pending.size, saveError = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                synchronized(lock) { mutableState.value = mutableState.value.copy(
                    saveError = "用量统计尚未写入；待保存记录保留在本次运行中，请重试保存后再退出") }
                return
            }
        }
    }
    fun retrySave() { signal.trySend(Unit) }
    suspend fun refresh(query: DesktopUsageQuery = DesktopUsageQuery(),
        dimension: DesktopUsageDimension = DesktopUsageDimension.MODEL_ACCOUNT, page: Int = 0) = withContext(Dispatchers.IO) {
        val sequence = synchronized(lock) { mutableState.value = mutableState.value.copy(loading = true); ++readSequence }
        try {
            val records = store.records().filter(query::accepts)
            val totals = summarizeDesktopUsage(records)
            val breakdown = desktopUsageBreakdownPage(records, dimension, page)
            synchronized(lock) { if (sequence == readSequence) mutableState.value = mutableState.value.copy(
                totals = totals, recent = records.take(100), breakdown = breakdown, readError = null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            synchronized(lock) { if (sequence == readSequence) mutableState.value = mutableState.value.copy(totals = null, recent = emptyList(), breakdown = DesktopUsageBreakdownPage(dimension),
                readError = "用量历史未完整读取；原统计文件保留") }
        } finally { synchronized(lock) { if (sequence == readSequence) mutableState.value = mutableState.value.copy(loading = false) } }
    }
    suspend fun closeAndDrain() = closeMutex.withLock {
        if (closed) return@withLock
        synchronized(lock) { accepting = false }
        signal.close(); writer.join()
        if (synchronized(lock) { pending.isNotEmpty() }) store.finishLocalDiagnostics { flush() }
        val remaining = synchronized(lock) { pending.size }
        if (remaining != 0) throw DesktopUsageDrainException(remaining)
        scope.coroutineContext[Job]!!.cancelAndJoin()
        closed = true
    }
}
