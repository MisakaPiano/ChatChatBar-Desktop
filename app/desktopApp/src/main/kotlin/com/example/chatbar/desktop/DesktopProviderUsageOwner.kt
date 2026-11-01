package com.example.chatbar.desktop

import com.example.chatbar.domain.chat.PromptCacheUsage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class DesktopUsageParent(val value: DesktopUsageContext) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopUsageParent>
}

internal class DesktopUsageAttemptRecorder(
    private val context: DesktopUsageContext,
    private val modelId: String, private val modelName: String,
    private val provider: String?, private val accountId: String?,
    private val publish: (DesktopUsageRecord) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
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
            context.taskId, context.sessionId, context.cardId, modelId, modelName, provider, accountId, now, now)
        publish(requireNotNull(current))
    }
    fun usage(value: PromptCacheUsage) = synchronized(lock) {
        if (closed) return@synchronized
        val previous = current ?: return@synchronized
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
}

internal data class DesktopProviderUsageState(
    val pending: Int = 0, val saveError: String? = null, val readError: String? = null, val loading: Boolean = false,
    val totals: DesktopUsageTotals? = null, val recent: List<DesktopUsageRecord> = emptyList(),
    val groups: List<DesktopUsageGroup> = emptyList(),
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
    suspend fun refresh(query: DesktopUsageQuery = DesktopUsageQuery()) = withContext(Dispatchers.IO) {
        val sequence = synchronized(lock) { mutableState.value = mutableState.value.copy(loading = true); ++readSequence }
        try {
            val records = store.records().filter(query::accepts)
            val totals = summarizeDesktopUsage(records)
            val groups = records.groupBy { listOf(it.modelId, it.modelName, it.provider, it.accountId) }.values.map { group ->
                val first = group.first()
                DesktopUsageGroup(first.modelId, first.modelName, first.provider, first.accountId, summarizeDesktopUsage(group))
            }.sortedByDescending { it.totals.attempts }
            synchronized(lock) { if (sequence == readSequence) mutableState.value = mutableState.value.copy(
                totals = totals, recent = records.take(100), groups = groups, readError = null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            synchronized(lock) { if (sequence == readSequence) mutableState.value = mutableState.value.copy(totals = null, recent = emptyList(), groups = emptyList(),
                readError = "用量历史未完整读取；原统计文件保留") }
        } finally { synchronized(lock) { if (sequence == readSequence) mutableState.value = mutableState.value.copy(loading = false) } }
    }
    suspend fun closeAndDrain() {
        synchronized(lock) { accepting = false }
        signal.close(); writer.join()
        store.finishLocalDiagnostics { flush() }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        check(synchronized(lock) { pending.isEmpty() }) { "用量统计仍有未保存记录" }
    }
}
