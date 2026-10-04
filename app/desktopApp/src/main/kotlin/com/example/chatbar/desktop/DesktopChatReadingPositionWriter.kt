package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatScrollPosition
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal class DesktopReadingPositionDrainTimeoutException : IllegalStateException("Reading positions did not drain before storage shutdown")
internal class DesktopReadingPositionPersistenceException : IllegalStateException("Unable to save reading position")

/** One controller-owned worker; submit accepts synchronously, coalescing pending values per session. */
internal class DesktopChatReadingPositionWriter(
    private val read: suspend (String) -> ChatScrollPosition?,
    private val write: suspend (ChatScrollPosition) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onResult: (ChatScrollPosition, Boolean) -> Unit = { _, _ -> },
) {
    private data class Outcome(val position: ChatScrollPosition, val successful: Boolean)
    private val lock = Any()
    private val sequence = AtomicLong()
    private var accepting = true
    private val latest = mutableMapOf<String, ChatScrollPosition>()
    private val pending = linkedMapOf<String, ChatScrollPosition>()
    private val errors = mutableMapOf<String, Boolean>()
    private val outcomes = MutableStateFlow<Map<String, Outcome>>(emptyMap())
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val supervisor = SupervisorJob()
    private val worker = CoroutineScope(supervisor + dispatcher).launch {
        for (ignored in signal) {
            while (true) {
                val next = synchronized(lock) { pending.entries.firstOrNull()?.let { pending.remove(it.key) } } ?: break
                val successful = try { write(next); true } catch (_: Throwable) { false }
                synchronized(lock) {
                    if (!successful) errors[next.sessionId] = true
                    else if (next.capturedAt >= (latest[next.sessionId]?.capturedAt ?: 0)) errors[next.sessionId] = false
                }
                onResult(next, successful)
                outcomes.value = outcomes.value + (next.sessionId to Outcome(next, successful))
            }
        }
    }

    suspend fun load(sessionId: String): ChatScrollPosition? {
        val durable = read(sessionId)
        durable?.let { sequence.updateAndGet { previous -> maxOf(previous, it.capturedAt) } }
        return durable
    }

    fun latestPosition(sessionId: String): ChatScrollPosition? = synchronized(lock) { latest[sessionId] }
    // Error state is process-only and per session; refresh is not a persistence success.
    fun hasError(sessionId: String): Boolean = synchronized(lock) { errors[sessionId] == true }

    fun submit(snapshot: ChatScrollPosition): ChatScrollPosition? = synchronized(lock) {
        if (!accepting) return null
        val position = snapshot.copy(capturedAt = sequence.updateAndGet { maxOf(it + 1, clock()) })
        latest[position.sessionId] = position
        pending[position.sessionId] = position
        check(signal.trySend(Unit).isSuccess)
        position
    }

    suspend fun await(position: ChatScrollPosition?): ChatScrollPosition? {
        if (position == null) return null
        val outcome = outcomes.first { (it[position.sessionId]?.position?.capturedAt ?: Long.MIN_VALUE) >= position.capturedAt }
            .getValue(position.sessionId)
        if (!outcome.successful) throw DesktopReadingPositionPersistenceException()
        return outcome.position
    }

    suspend fun save(snapshot: ChatScrollPosition): ChatScrollPosition? = await(submit(snapshot))
    suspend fun flush(sessionId: String) = await(latestPosition(sessionId))
    suspend fun drain() {
        synchronized(lock) { latest.values.toList() }.forEach { await(it) }
    }

    /** Timeout leaves the worker and storage ownership live, permitting a later drain retry. */
    suspend fun closeAndDrain(timeoutMillis: Long = 10_000L) {
        require(timeoutMillis > 0)
        synchronized(lock) { accepting = false; signal.close() }
        val drained = withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMillis) { worker.join(); supervisor.cancelAndJoin(); true }
        }
        if (drained != true) throw DesktopReadingPositionDrainTimeoutException()
        if (outcomes.value.values.any { !it.successful }) throw DesktopReadingPositionPersistenceException()
    }
    suspend fun close() = closeAndDrain()
}
