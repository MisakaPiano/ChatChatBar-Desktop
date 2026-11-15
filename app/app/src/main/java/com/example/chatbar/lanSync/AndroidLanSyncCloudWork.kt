package com.example.chatbar.lanSync

import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.chatbar.ChatBarApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** One finite cloud batch per actual dataSync promotion; no LAN or AI foreground lease is reused. */
internal class AndroidLanSyncCloudWork(
    private val applicationScope: CoroutineScope,
    private val canStart: () -> Boolean, private val canContinue: () -> Boolean,
    private val cancelCloud: () -> Unit, private val startService: (Long) -> Unit) {
    constructor(app: ChatBarApp, canStart: () -> Boolean, canContinue: () -> Boolean,
        cancelCloud: () -> Unit) : this(
        CoroutineScope(app.applicationScope.coroutineContext + Dispatchers.IO), canStart, canContinue, cancelCloud,
        { generation ->
            ContextCompat.startForegroundService(app, Intent(app, LanSyncGroupCloudTransferService::class.java)
                .setAction(LanSyncGroupCloudTransferService.ACTION_START)
                .putExtra(LanSyncGroupCloudTransferService.EXTRA_GENERATION, generation))
        })

    private class Attempt(val generation: Long) {
        val ready = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var worker: Deferred<Boolean>? = null
        var startIssued = false
        var alive = false
        var releasing = false
        var stop: (() -> Unit)? = null
        var failure: String? = null
    }
    private val lock = Any()
    private val admission = Mutex()
    private val cancellationAdmission = Mutex()
    private var generation = 0L
    private var latestIssuedGeneration: Long? = null
    private var active: Attempt? = null
    private var closed = false
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    val allowed: Boolean get() {
        val promoted = synchronized(lock) { !closed && active?.let { it.alive && !it.releasing } == true }
        return promoted && canContinue()
    }

    suspend fun run(block: suspend () -> Boolean): Boolean = admission.withLock {
        val attempt = cancellationAdmission.withLock {
            if (!canStart()) fail("FOREGROUND_REQUIRED")
            synchronized(lock) {
                if (closed) fail("CLOSED")
                if (active != null) fail("STOP_PENDING")
                Attempt(++generation).also { active = it; mutableFailure.value = null }
            }
        }
        supervisorScope {
            val worker = async(start = CoroutineStart.LAZY) {
                if (!canStart()) fail("FOREGROUND_REQUIRED")
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (closed || active !== attempt || attempt.releasing) fail("PAUSED")
                    attempt.startIssued = true
                    latestIssuedGeneration = attempt.generation
                }
                try {
                    // No lifecycle/state lock spans Binder or notification work.
                    startService(attempt.generation)
                } catch (_: Exception) {
                    startFailed(attempt)
                    fail("START_FAILED")
                }
                try { withTimeout(15_000) { attempt.ready.await() } }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    recordFailure(attempt, "SYNC_GROUP_CLOUD_BACKGROUND_PROMOTION_TIMEOUT")
                    throw IllegalStateException("SYNC_GROUP_CLOUD_BACKGROUND_PROMOTION_TIMEOUT")
                }
                if (!allowed) fail("PROTECTION_LOST")
                block()
            }
            synchronized(lock) { attempt.worker = worker }
            worker.start()
            var completed = false
            try {
                val result = worker.await()
                completed = true
                result
            } catch (cancelled: CancellationException) {
                cancelTransport(attempt.generation)
                currentCoroutineContext().ensureActive()
                val reason = synchronized(lock) { attempt.failure }
                if (reason != null) throw IllegalStateException(reason)
                throw cancelled
            } finally {
                withContext(NonCancellable) {
                    release(attempt)
                    if (withTimeoutOrNull(5_000) { attempt.stopped.await(); true } != true) {
                        recordFailure(attempt, "SYNC_GROUP_CLOUD_BACKGROUND_STOP_PENDING")
                        if (completed) fail("STOP_PENDING")
                    }
                }
            }
        }
    }

    /** Cancellation interrupts only the current cloud child; its finally drains before normal stop. */
    fun cancel(reason: String = "SYNC_GROUP_CLOUD_BACKGROUND_PAUSED"): Job {
        val pending = synchronized(lock) {
            val worker = active?.let {
                if (it.failure == null) it.failure = reason
                mutableFailure.value = it.failure
                it.releasing = true
                it.ready.completeExceptionally(IllegalStateException(requireNotNull(it.failure)))
                it.worker
            }
            worker to generation
        }
        pending.first?.cancel(CancellationException(reason))
        return cancelTransport(pending.second)
    }

    fun promoted(requested: Long, stop: () -> Unit) {
        val permitted = canContinue()
        var cancel: Deferred<Boolean>? = null
        val stopNow = synchronized(lock) {
            val attempt = active?.takeIf { it.generation == requested }
            if (attempt == null) true else {
                attempt.alive = true
                attempt.stop = stop
                if (closed || !permitted || attempt.releasing) {
                    attempt.releasing = true
                    if (attempt.failure == null) attempt.failure = "SYNC_GROUP_CLOUD_BACKGROUND_PAUSED"
                    attempt.ready.completeExceptionally(IllegalStateException(requireNotNull(attempt.failure)))
                    cancel = attempt.worker
                    attempt.worker?.isCompleted != false
                } else { attempt.ready.complete(Unit); false }
            }
        }
        cancel?.cancel(CancellationException("SYNC_GROUP_CLOUD_BACKGROUND_PAUSED"))
        if (stopNow) stop()
    }

    fun promotionFailed(requested: Long) = lost(requested, "SYNC_GROUP_CLOUD_BACKGROUND_PROMOTION_FAILED")
    fun timedOut(requested: Long) = lost(requested, "SYNC_GROUP_CLOUD_BACKGROUND_TIME_LIMIT")
    fun owns(requested: Long): Boolean = synchronized(lock) { !closed && active?.let { it.generation == requested && it.alive && !it.releasing } == true }
    /** A visible latest notification still carries user intent while its batch is stopping or stopped. */
    fun acceptsStop(requested: Long): Boolean = synchronized(lock) { !closed && latestIssuedGeneration == requested }

    fun destroyed(requested: Long, expected: Boolean) {
        var cancel: Deferred<Boolean>? = null
        val unexpected = synchronized(lock) {
            val attempt = active?.takeIf { it.generation == requested } ?: return
            attempt.alive = false
            val lost = !expected && !attempt.releasing
            if (lost && attempt.failure == null) attempt.failure = "SYNC_GROUP_CLOUD_BACKGROUND_SERVICE_LOST"
            if (attempt.failure != null) {
                mutableFailure.value = attempt.failure
                attempt.ready.completeExceptionally(IllegalStateException(requireNotNull(attempt.failure)))
                cancel = attempt.worker
            }
            attempt.stopped.complete(Unit)
            active = null
            lost
        }
        cancel?.cancel(CancellationException("SYNC_GROUP_CLOUD_BACKGROUND_SERVICE_LOST"))
        if (unexpected) cancelTransport(requested)
    }

    private fun lost(requested: Long, reason: String) {
        val worker = synchronized(lock) {
            val attempt = active?.takeIf { it.generation == requested } ?: return
            attempt.alive = false; attempt.releasing = true
            if (attempt.failure == null) attempt.failure = reason
            mutableFailure.value = attempt.failure
            attempt.ready.completeExceptionally(IllegalStateException(requireNotNull(attempt.failure)))
            attempt.worker
        }
        worker?.cancel(CancellationException(reason))
        cancelTransport(requested)
    }
    private fun startFailed(attempt: Attempt) = synchronized(lock) {
        if (attempt.failure == null) attempt.failure = "SYNC_GROUP_CLOUD_BACKGROUND_START_FAILED"
        mutableFailure.value = attempt.failure
        attempt.ready.completeExceptionally(IllegalStateException(requireNotNull(attempt.failure)))
        attempt.stopped.complete(Unit)
        if (active === attempt) active = null
    }
    private fun recordFailure(attempt: Attempt, reason: String) = synchronized(lock) {
        if (attempt.failure == null) attempt.failure = reason
        mutableFailure.value = attempt.failure
    }
    private fun release(attempt: Attempt) {
        val stop = synchronized(lock) {
            attempt.releasing = true
            if (!attempt.startIssued) {
                attempt.stopped.complete(Unit)
                if (active === attempt) active = null
            }
            attempt.stop
        }
        stop?.invoke()
    }
    private fun cancelTransport(requested: Long): Job = applicationScope.launch {
        cancellationAdmission.withLock {
            // A delayed old cancellation cannot close a new batch's HTTP calls.
            if (synchronized(lock) { generation != requested }) return@withLock
            try { cancelCloud() }
            catch (_: Exception) { mutableFailure.value = "SYNC_GROUP_CLOUD_BACKGROUND_CANCEL_FAILED" }
        }
    }
    suspend fun closeAndDrain() = withContext(NonCancellable) {
        synchronized(lock) { closed = true }
        cancel("SYNC_GROUP_CLOUD_BACKGROUND_CLOSED").join()
        admission.withLock { }
        val remaining = synchronized(lock) { active }
        if (remaining != null) {
            release(remaining)
            withTimeoutOrNull(5_000) { remaining.stopped.await() }
        }
    }
    private fun fail(suffix: String): Nothing = throw IllegalStateException("SYNC_GROUP_CLOUD_BACKGROUND_$suffix")
}
