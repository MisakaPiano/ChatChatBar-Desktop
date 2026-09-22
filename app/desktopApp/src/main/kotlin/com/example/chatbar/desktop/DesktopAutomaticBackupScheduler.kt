package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.data.snapshot.AutomaticBackupExecutionResult
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.toKotlinDuration

data class DesktopAutomaticBackupSchedule(
    val minimumBackupInterval: Duration,
    val maximumSnapshotCount: Int,
    val checkInterval: Duration,
) {
    init {
        require(!minimumBackupInterval.isNegative) {
            "Minimum backup interval must not be negative"
        }
        require(maximumSnapshotCount >= 1) {
            "Maximum snapshot count must be at least one"
        }
        require(!checkInterval.isNegative && !checkInterval.isZero) {
            "Check interval must be positive"
        }
    }
}

sealed interface DesktopAutomaticBackupEvent {
    data object SkippedNotDue : DesktopAutomaticBackupEvent

    data class Created(
        val result: AutomaticBackupExecutionResult.Created,
    ) : DesktopAutomaticBackupEvent

    data class Failed(
        val error: Exception,
    ) : DesktopAutomaticBackupEvent
}

/**
 * Application-owned Desktop scheduling adapter for explicit, single-run automatic backup checks.
 *
 * This scheduler serializes only its own executions. Future manual snapshot, restore, and prune
 * entry points must coordinate with this scheduler so every snapshot repository mutation remains
 * mutually exclusive. Event reporting is best-effort, and observer exceptions do not stop backup
 * scheduling. Construction does not start scheduling or touch the app-data filesystem.
 */
class DesktopAutomaticBackupScheduler internal constructor(
    private val executeAutomaticBackup: (Duration, Int) -> AutomaticBackupExecutionResult,
    dispatcher: CoroutineDispatcher,
    private val eventSink: (DesktopAutomaticBackupEvent) -> Unit,
) {
    constructor(
        snapshotService: AppDataSnapshotService,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        eventSink: (DesktopAutomaticBackupEvent) -> Unit = {},
    ) : this(
        executeAutomaticBackup = snapshotService::executeAutomaticBackup,
        dispatcher = dispatcher,
        eventSink = eventSink,
    )

    private val stateLock = Any()
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + dispatcher)
    private var activeRun: ActiveRun? = null
    private var closed = false

    val isRunning: Boolean
        get() = synchronized(stateLock) { activeRun != null }

    fun start(schedule: DesktopAutomaticBackupSchedule): Boolean = synchronized(stateLock) {
        check(!closed) { "Automatic backup scheduler is closed" }
        if (activeRun != null) return false

        val run = ActiveRun()
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runLoop(schedule, run.stopSignal)
            } finally {
                synchronized(stateLock) {
                    if (activeRun === run) activeRun = null
                }
            }
        }
        activeRun = run
        run.job.start()
        true
    }

    /**
     * Stops future checks and waits for an in-flight synchronous filesystem execution to finish.
     * The execution thread is never interrupted or forcibly cancelled.
     */
    suspend fun stop(): Boolean {
        val run = synchronized(stateLock) { activeRun } ?: return false
        run.stopSignal.complete(Unit)
        run.job.join()
        return true
    }

    /** Permanently closes this scheduler after any in-flight execution finishes safely. */
    suspend fun close() {
        val run = synchronized(stateLock) {
            if (closed) return
            closed = true
            activeRun?.also { it.stopSignal.complete(Unit) }
        }
        run?.job?.join()
        supervisorJob.cancel()
    }

    private suspend fun runLoop(
        schedule: DesktopAutomaticBackupSchedule,
        stopSignal: CompletableDeferred<Unit>,
    ) {
        while (!stopSignal.isCompleted) {
            deliverEventSafely(executeOnce(schedule))
            if (stopSignal.isCompleted) break
            withTimeoutOrNull(schedule.checkInterval.toKotlinDuration()) {
                stopSignal.await()
            }
        }
    }

    private fun deliverEventSafely(event: DesktopAutomaticBackupEvent) {
        try {
            eventSink(event)
        } catch (_: Exception) {
            // Observer failures must not stop the backup scheduling loop.
        }
    }

    private fun executeOnce(schedule: DesktopAutomaticBackupSchedule): DesktopAutomaticBackupEvent =
        try {
            when (
                val result = executeAutomaticBackup(
                    schedule.minimumBackupInterval,
                    schedule.maximumSnapshotCount,
                )
            ) {
                AutomaticBackupExecutionResult.SkippedNotDue ->
                    DesktopAutomaticBackupEvent.SkippedNotDue

                is AutomaticBackupExecutionResult.Created ->
                    DesktopAutomaticBackupEvent.Created(result)
            }
        } catch (error: Exception) {
            DesktopAutomaticBackupEvent.Failed(error)
        }

    private class ActiveRun {
        val stopSignal = CompletableDeferred<Unit>()
        lateinit var job: Job
    }
}
