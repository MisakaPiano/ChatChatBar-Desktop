package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AutomaticBackupExecutionResult
import java.time.Duration
import java.util.UUID
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * This scheduler serializes only its own executions. Snapshot filesystem access goes through the
 * Desktop coordinator; maintenance that must stop future ticks must still pause this runtime before
 * requesting exclusive work. Event reporting is best-effort, and observer exceptions do not stop
 * backup scheduling. Construction does not start scheduling or touch the app-data filesystem.
 */
class DesktopAutomaticBackupScheduler internal constructor(
    private val executeAutomaticBackup: suspend (Duration, Int) -> AutomaticBackupExecutionResult,
    dispatcher: CoroutineDispatcher,
    private val eventSink: (DesktopAutomaticBackupEvent) -> Unit,
) {
    internal constructor(
        snapshotService: DesktopCoordinatedSnapshotService,
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
    private val mutableWork = MutableStateFlow<List<DesktopWorkRow>>(emptyList())
    internal val workRows = mutableWork.asStateFlow()

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

    private suspend fun executeOnce(schedule: DesktopAutomaticBackupSchedule): DesktopAutomaticBackupEvent {
        val id = UUID.randomUUID().toString()
        mutableWork.update { rows -> (listOf(DesktopWorkRow(id, DesktopWorkKind.AUTOMATIC_BACKUP_CHECK,
            DesktopWorkPhase.RUNNING, System.currentTimeMillis(),
            target = DesktopWorkTarget(DesktopPrimaryRoute.DATA, detail = DesktopWorkDetail.AUTOMATIC_BACKUP),
            contextLabel = "自动备份检查")) + rows).take(41) }
        var event: DesktopAutomaticBackupEvent? = null
        try {
            return try {
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
            }.also { event = it }
        } finally {
            mutableWork.update { rows -> when (val result = event) {
                DesktopAutomaticBackupEvent.SkippedNotDue -> rows.filterNot { it.id == id }
                else -> rows.map { row -> if (row.id != id) row else when (result) {
                    is DesktopAutomaticBackupEvent.Created -> row.copy(kind = DesktopWorkKind.AUTOMATIC_BACKUP,
                        phase = if (result.result.pruneResult.cleanupWarnings.isEmpty()) DesktopWorkPhase.COMPLETED else DesktopWorkPhase.UNCONFIRMED,
                        contextLabel = if (result.result.pruneResult.cleanupWarnings.isEmpty()) "自动快照" else "自动快照已创建，清理待核对")
                    is DesktopAutomaticBackupEvent.Failed -> row.copy(phase = if (result.error is kotlinx.coroutines.CancellationException)
                        DesktopWorkPhase.UNCONFIRMED else DesktopWorkPhase.FAILED)
                    else -> row.copy(phase = DesktopWorkPhase.UNCONFIRMED)
                } }.take(40)
            } }
        }
    }

    private class ActiveRun {
        val stopSignal = CompletableDeferred<Unit>()
        lateinit var job: Job
    }
}
