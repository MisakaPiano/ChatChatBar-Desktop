package com.example.chatbar.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DesktopAutomaticBackupRuntimeMode {
    NEW,
    ACTIVE,
    MAINTENANCE_PAUSED,
    RESTART_REQUIRED,
    CLOSED,
}

data class DesktopAutomaticBackupRuntimeState(
    val mode: DesktopAutomaticBackupRuntimeMode = DesktopAutomaticBackupRuntimeMode.NEW,
    val settingsLoadResult: DesktopSettingsLoadResult? = null,
    val effectiveSettings: DesktopSettings? = null,
    val schedulerRunning: Boolean = false,
    val latestEvent: DesktopAutomaticBackupEvent? = null,
    val latestExecutionFailure: Exception? = null,
    val latestCleanupWarnings: List<String> = emptyList(),
    val operationFailure: DesktopAutomaticBackupRuntimeFailure? = null,
)

data class DesktopAutomaticBackupRuntimeFailure(
    val stage: DesktopAutomaticBackupRuntimeFailureStage,
    val error: Exception,
    val schedulerRestartError: Exception? = null,
)

enum class DesktopAutomaticBackupRuntimeFailureStage {
    INVALID_STATE,
    MAINTENANCE_PAUSED,
    RESTART_REQUIRED,
    SAVE,
    START,
    STOP,
}

internal class DesktopAutomaticBackupRuntimeStateException(
    val mode: DesktopAutomaticBackupRuntimeMode,
    message: String,
) : Exception(message)

sealed interface DesktopSettingsApplyResult {
    data class Applied(val settings: DesktopSettings) : DesktopSettingsApplyResult
    data class Failed(val failure: DesktopAutomaticBackupRuntimeFailure) : DesktopSettingsApplyResult
}

sealed interface DesktopMaintenancePauseResult {
    data class Paused(val schedulerWasRunning: Boolean) : DesktopMaintenancePauseResult
    data class Failed(val failure: DesktopAutomaticBackupRuntimeFailure) : DesktopMaintenancePauseResult
}

sealed interface DesktopMaintenanceResumeResult {
    data object Resumed : DesktopMaintenanceResumeResult
    data object RestartRequired : DesktopMaintenanceResumeResult
    data class Failed(val failure: DesktopAutomaticBackupRuntimeFailure) : DesktopMaintenanceResumeResult
}

/**
 * 持有 Desktop automatic-backup settings application 与 scheduler lifecycle。
 *
 * Maintenance ordering 固定为：先 pause 并等待 scheduler stop/join，返回后 caller 才能申请
 * coordinator exclusive maintenance。这样避免
 * `exclusive -> scheduler.stop -> scheduled run waiting for exclusive` deadlock。Lifecycle mutex
 * 不替代 app-data operation coordination，也不提供 cross-process ownership。
 */
class DesktopAutomaticBackupRuntime internal constructor(
    private val settingsStore: DesktopSettingsStore,
    private val coordinator: DesktopDataOperationCoordinator,
    schedulerFactory: ((DesktopAutomaticBackupEvent) -> Unit) -> DesktopAutomaticBackupScheduler,
) {
    internal constructor(
        settingsStore: DesktopSettingsStore,
        snapshotService: DesktopCoordinatedSnapshotService,
        coordinator: DesktopDataOperationCoordinator,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        settingsStore = settingsStore,
        coordinator = coordinator,
        schedulerFactory = { eventSink ->
            DesktopAutomaticBackupScheduler(snapshotService, dispatcher, eventSink)
        },
    )

    private val lifecycleMutex = Mutex()
    private val _state = MutableStateFlow(DesktopAutomaticBackupRuntimeState())
    val state: StateFlow<DesktopAutomaticBackupRuntimeState> = _state.asStateFlow()
    private val scheduler = schedulerFactory(::recordEvent)

    private var currentDocument: DesktopSettingsDocument? = null
    private var mode = DesktopAutomaticBackupRuntimeMode.NEW
    private var resumeSchedulerAfterMaintenance = false

    suspend fun initialize(): DesktopAutomaticBackupRuntimeState = lifecycleMutex.withLock {
        if (mode == DesktopAutomaticBackupRuntimeMode.ACTIVE) return@withLock _state.value
        check(mode == DesktopAutomaticBackupRuntimeMode.NEW) {
            "Automatic backup runtime cannot initialize from $mode"
        }
        val loadResult = settingsStore.load()
        val document = when (loadResult) {
            is DesktopSettingsLoadResult.Missing -> loadResult.document
            is DesktopSettingsLoadResult.Loaded -> loadResult.document
            is DesktopSettingsLoadResult.Failure -> null
        }
        currentDocument = document
        mode = DesktopAutomaticBackupRuntimeMode.ACTIVE
        _state.value = DesktopAutomaticBackupRuntimeState(
            mode = mode,
            settingsLoadResult = loadResult,
            effectiveSettings = document?.settings,
        )
        val settings = document?.settings
        if (settings?.automaticBackup?.enabled == true) {
            val failure = startScheduler(settings)
            _state.update {
                it.copy(schedulerRunning = scheduler.isRunning, operationFailure = failure)
            }
        }
        _state.value
    }

    suspend fun applySettings(settings: DesktopSettings): DesktopSettingsApplyResult =
        lifecycleMutex.withLock {
            if (mode != DesktopAutomaticBackupRuntimeMode.ACTIVE) {
                return@withLock invalidApplyStateFailure()
            }
            val previousDocument = currentDocument
                ?: return@withLock invalidApplyStateFailure(
                    "Cannot apply settings after a settings load failure",
                )
            val previousSettings = previousDocument.settings
            val previousRunning = scheduler.isRunning

            scheduler.stop()
            _state.update { it.copy(schedulerRunning = false) }
            val savedDocument = try {
                settingsStore.save(previousDocument, settings)
            } catch (error: Exception) {
                val restartError = if (previousRunning && previousSettings.automaticBackup.enabled) {
                    startScheduler(previousSettings)?.error
                } else {
                    null
                }
                val failure = DesktopAutomaticBackupRuntimeFailure(
                    stage = DesktopAutomaticBackupRuntimeFailureStage.SAVE,
                    error = error,
                    schedulerRestartError = restartError,
                )
                _state.update {
                    it.copy(schedulerRunning = scheduler.isRunning, operationFailure = failure)
                }
                return@withLock DesktopSettingsApplyResult.Failed(failure)
            }

            currentDocument = savedDocument
            _state.update {
                it.copy(
                    settingsLoadResult = DesktopSettingsLoadResult.Loaded(savedDocument),
                    effectiveSettings = settings,
                    schedulerRunning = false,
                    operationFailure = null,
                )
            }
            if (settings.automaticBackup.enabled) {
                val failure = startScheduler(settings)
                _state.update {
                    it.copy(schedulerRunning = scheduler.isRunning, operationFailure = failure)
                }
                if (failure != null) return@withLock DesktopSettingsApplyResult.Failed(failure)
            }
            DesktopSettingsApplyResult.Applied(settings)
        }

    suspend fun pauseForMaintenance(): DesktopMaintenancePauseResult = lifecycleMutex.withLock {
        if (mode != DesktopAutomaticBackupRuntimeMode.ACTIVE) {
            return@withLock DesktopMaintenancePauseResult.Failed(runtimeStateFailure())
        }
        val wasRunning = scheduler.isRunning
        resumeSchedulerAfterMaintenance = wasRunning
        mode = DesktopAutomaticBackupRuntimeMode.MAINTENANCE_PAUSED
        _state.update { it.copy(mode = mode, operationFailure = null) }
        try {
            scheduler.stop()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                stabilizeCancelledMaintenancePause(wasRunning, cancelled)
            }
            throw cancelled
        } catch (error: Exception) {
            val failure = DesktopAutomaticBackupRuntimeFailure(
                stage = DesktopAutomaticBackupRuntimeFailureStage.STOP,
                error = error,
            )
            _state.update {
                it.copy(schedulerRunning = scheduler.isRunning, operationFailure = failure)
            }
            return@withLock DesktopMaintenancePauseResult.Failed(failure)
        }
        _state.update { it.copy(schedulerRunning = false) }
        DesktopMaintenancePauseResult.Paused(wasRunning)
    }

    /**
     * A cancelled pause is not a completed maintenance boundary. Before cancellation escapes, finish
     * the non-interrupting scheduler stop and restore the pre-pause scheduling intent. The lifecycle
     * mutex is still held by the caller, so no settings mutation can race this stabilization.
     */
    private suspend fun stabilizeCancelledMaintenancePause(
        wasRunning: Boolean,
        cancelled: CancellationException,
    ) {
        var stopFailure: Exception? = null
        try {
            scheduler.stop()
        } catch (error: Exception) {
            stopFailure = error
        }

        mode = DesktopAutomaticBackupRuntimeMode.ACTIVE
        resumeSchedulerAfterMaintenance = false
        val settings = currentDocument?.settings
        val restartFailure = if (
            wasRunning &&
            settings?.automaticBackup?.enabled == true &&
            !scheduler.isRunning
        ) {
            startScheduler(settings)?.error
        } else {
            null
        }
        val failure = when {
            stopFailure != null -> DesktopAutomaticBackupRuntimeFailure(
                stage = DesktopAutomaticBackupRuntimeFailureStage.STOP,
                error = stopFailure,
                schedulerRestartError = restartFailure,
            )
            restartFailure != null -> DesktopAutomaticBackupRuntimeFailure(
                stage = DesktopAutomaticBackupRuntimeFailureStage.START,
                error = restartFailure,
            )
            else -> null
        }
        _state.update {
            it.copy(
                mode = mode,
                schedulerRunning = scheduler.isRunning,
                operationFailure = failure,
            )
        }
        stopFailure?.let(cancelled::addSuppressed)
        restartFailure?.takeIf { it !== stopFailure }?.let(cancelled::addSuppressed)
    }

    suspend fun resumeAfterMaintenance(): DesktopMaintenanceResumeResult = lifecycleMutex.withLock {
        if (mode != DesktopAutomaticBackupRuntimeMode.MAINTENANCE_PAUSED) {
            return@withLock DesktopMaintenanceResumeResult.Failed(runtimeStateFailure())
        }
        if (coordinator.isRestartRequired) {
            mode = DesktopAutomaticBackupRuntimeMode.RESTART_REQUIRED
            resumeSchedulerAfterMaintenance = false
            _state.update { it.copy(mode = mode, schedulerRunning = false, operationFailure = null) }
            return@withLock DesktopMaintenanceResumeResult.RestartRequired
        }
        if (coordinator.state != DesktopDataOperationCoordinatorState.OPEN) {
            val failure = DesktopAutomaticBackupRuntimeFailure(
                DesktopAutomaticBackupRuntimeFailureStage.INVALID_STATE,
                DesktopAutomaticBackupRuntimeStateException(
                    mode,
                    "Cannot resume while data-operation coordinator is ${coordinator.state}",
                ),
            )
            _state.update { it.copy(operationFailure = failure) }
            return@withLock DesktopMaintenanceResumeResult.Failed(failure)
        }

        mode = DesktopAutomaticBackupRuntimeMode.ACTIVE
        val shouldRestart = resumeSchedulerAfterMaintenance
        resumeSchedulerAfterMaintenance = false
        _state.update { it.copy(mode = mode, operationFailure = null) }
        val settings = currentDocument?.settings
        if (shouldRestart && settings?.automaticBackup?.enabled == true) {
            val failure = startScheduler(settings)
            _state.update {
                it.copy(schedulerRunning = scheduler.isRunning, operationFailure = failure)
            }
            if (failure != null) return@withLock DesktopMaintenanceResumeResult.Failed(failure)
        }
        DesktopMaintenanceResumeResult.Resumed
    }

    suspend fun close() = lifecycleMutex.withLock {
        if (mode == DesktopAutomaticBackupRuntimeMode.CLOSED) return@withLock
        mode = DesktopAutomaticBackupRuntimeMode.CLOSED
        resumeSchedulerAfterMaintenance = false
        try {
            scheduler.close()
        } finally {
            _state.update { it.copy(mode = mode, schedulerRunning = false) }
        }
    }

    private fun startScheduler(settings: DesktopSettings): DesktopAutomaticBackupRuntimeFailure? =
        try {
            if (!scheduler.start(settings.automaticBackup.schedule())) {
                DesktopAutomaticBackupRuntimeFailure(
                    DesktopAutomaticBackupRuntimeFailureStage.START,
                    IllegalStateException("Automatic backup scheduler is already running"),
                )
            } else {
                null
            }
        } catch (error: Exception) {
            DesktopAutomaticBackupRuntimeFailure(
                DesktopAutomaticBackupRuntimeFailureStage.START,
                error,
            )
        }

    private fun invalidApplyStateFailure(
        message: String = "Cannot apply settings while runtime mode is $mode",
    ): DesktopSettingsApplyResult.Failed {
        val failure = runtimeStateFailure(message)
        _state.update { it.copy(operationFailure = failure) }
        return DesktopSettingsApplyResult.Failed(failure)
    }

    private fun runtimeStateFailure(
        message: String = "Operation is not allowed while runtime mode is $mode",
    ): DesktopAutomaticBackupRuntimeFailure {
        val stage = when (mode) {
            DesktopAutomaticBackupRuntimeMode.MAINTENANCE_PAUSED ->
                DesktopAutomaticBackupRuntimeFailureStage.MAINTENANCE_PAUSED
            DesktopAutomaticBackupRuntimeMode.RESTART_REQUIRED ->
                DesktopAutomaticBackupRuntimeFailureStage.RESTART_REQUIRED
            else -> DesktopAutomaticBackupRuntimeFailureStage.INVALID_STATE
        }
        return DesktopAutomaticBackupRuntimeFailure(
            stage,
            DesktopAutomaticBackupRuntimeStateException(mode, message),
        )
    }

    private fun recordEvent(event: DesktopAutomaticBackupEvent) {
        _state.update { current ->
            current.copy(
                schedulerRunning = scheduler.isRunning,
                latestEvent = event,
                latestExecutionFailure = (event as? DesktopAutomaticBackupEvent.Failed)?.error,
                latestCleanupWarnings = (event as? DesktopAutomaticBackupEvent.Created)
                    ?.result?.pruneResult?.cleanupWarnings.orEmpty(),
            )
        }
    }
}
