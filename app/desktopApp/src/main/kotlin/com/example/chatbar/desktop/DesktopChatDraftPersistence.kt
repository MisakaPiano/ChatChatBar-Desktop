package com.example.chatbar.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class DesktopDraftPersistenceException : IllegalStateException("Unable to save chat draft")

internal class DesktopDraftDrainTimeoutException : IllegalStateException("Chat drafts did not drain before storage shutdown")

internal data class DesktopDraftRevision(val sessionId: String, val sequence: Long)

/** One tracked writer, with at most one pending latest value per session. No UI scope owns it. */
internal class DesktopChatDraftPersistence(
    private val write: suspend (String, String) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onResult: (DesktopDraftRevision, Boolean) -> Unit = { _, _ -> },
) {
    private data class Pending(val revision: DesktopDraftRevision, val text: String)
    private data class Outcome(val sequence: Long, val successful: Boolean)

    private val lock = Any()
    private var accepting = true
    private var sequence = 0L
    private val latest = mutableMapOf<String, DesktopDraftRevision>()
    private val pending = linkedMapOf<String, Pending>()
    private val outcomes = MutableStateFlow<Map<String, Outcome>>(emptyMap())
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val supervisor = SupervisorJob()
    private val worker = CoroutineScope(supervisor + dispatcher).launch {
        for (ignored in signal) {
            while (true) {
                val next = synchronized(lock) {
                    pending.entries.firstOrNull()?.let { pending.remove(it.key) }
                } ?: break
                // A failed write must not kill the worker or expose content/exception details.
                val successful = try {
                    write(next.revision.sessionId, next.text)
                    true
                } catch (_: Throwable) {
                    false
                }
                onResult(next.revision, successful)
                outcomes.value = outcomes.value + (next.revision.sessionId to Outcome(next.revision.sequence, successful))
            }
        }
    }

    fun submit(sessionId: String, text: String): DesktopDraftRevision = synchronized(lock) {
        submitLocked(sessionId, text)
    }

    fun latestRevision(sessionId: String): DesktopDraftRevision? = synchronized(lock) { latest[sessionId] }

    /** An admitted Send replaces queued old text, but never discards a newer user edit. */
    fun clearIfCurrent(sessionId: String, expected: DesktopDraftRevision?): DesktopDraftRevision? = synchronized(lock) {
        if (latest[sessionId] != expected) null else submitLocked(sessionId, "")
    }

    suspend fun await(revision: DesktopDraftRevision?) {
        if (revision == null) return
        val outcome = outcomes.first { (it[revision.sessionId]?.sequence ?: 0L) >= revision.sequence }
            .getValue(revision.sessionId)
        if (!outcome.successful) throw DesktopDraftPersistenceException()
    }

    suspend fun flush(sessionId: String) = await(latestRevision(sessionId))

    /** Timeout leaves storage live; callers may retry draining the same tracked worker. */
    suspend fun closeAndDrain(timeoutMillis: Long = 10_000L) {
        require(timeoutMillis > 0)
        synchronized(lock) {
            accepting = false
            signal.close()
        }
        val drained = withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMillis) {
                worker.join()
                supervisor.cancelAndJoin()
                true
            }
        }
        if (drained != true) throw DesktopDraftDrainTimeoutException()
        if (outcomes.value.values.any { !it.successful }) throw DesktopDraftPersistenceException()
    }

    private fun submitLocked(sessionId: String, text: String): DesktopDraftRevision {
        check(accepting) { "Chat draft persistence is closing" }
        val revision = DesktopDraftRevision(sessionId, ++sequence)
        latest[sessionId] = revision
        pending[sessionId] = Pending(revision, text)
        check(signal.trySend(Unit).isSuccess)
        return revision
    }
}
