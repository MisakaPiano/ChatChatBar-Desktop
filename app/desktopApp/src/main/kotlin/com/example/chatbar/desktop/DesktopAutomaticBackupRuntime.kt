package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AppDataSnapshotService
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DesktopAutomaticBackupRuntimeState(
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
    SAVE,
    START,
}

sealed interface DesktopSettingsApplyResult {
    data class Applied(val settings: DesktopSettings) : DesktopSettingsApplyResult

    data class Failed(val failure: DesktopAutomaticBackupRuntimeFailure) : DesktopSettingsApplyResult
}

/**
 * Owns Desktop automatic-backup settings application and scheduler lifecycle.
 *
 * This runtime serializes only initialize/apply/close and its scheduler's own runs. It is not a
 * global coordinator for future manual snapshot, restore, prune, or business-persistence writes.
 * Construction performs no filesystem work and does not start scheduling.
 */
class DesktopAutomaticBackupRuntime internal constructor(
    private val settingsStore: DesktopSettingsStore,
    schedulerFactory: ((DesktopAutomaticBackupEvent) -> Unit) -> DesktopAutomaticBackupScheduler,
) {
    constructor(
        settingsStore: DesktopSettingsStore,
        snapshotService: AppDataSnapshotService,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        settingsStore = settingsStore,
        schedulerFactory = { eventSink ->
            DesktopAutomaticBackupScheduler(
                snapshotService = snapshotService,
                dispatcher = dispatcher,
                eventSink = eventSink,
            )
        },
    )

    private val lifecycleMutex = Mutex()
    private val _state = MutableStateFlow(DesktopAutomaticBackupRuntimeState())
    val state: StateFlow<DesktopAutomaticBackupRuntimeState> = _state.asStateFlow()
    internal val scheduler = schedulerFactory(::recordEvent)

    private var currentDocument: DesktopSettingsDocument? = null
    private var initialized = false
    private var closed = false

    suspend fun initialize(): DesktopAutomaticBackupRuntimeState = lifecycleMutex.withLock {
        if (initialized) return@withLock _state.value
        check(!closed) { "Automatic backup runtime is closed" }

        val loadResult = settingsStore.load()
        val document = when (loadResult) {
            is DesktopSettingsLoadResult.Missing -> loadResult.document
            is DesktopSettingsLoadResult.Loaded -> loadResult.document
            is DesktopSettingsLoadResult.Failure -> null
        }
        currentDocument = document
        initialized = true
        _state.value = DesktopAutomaticBackupRuntimeState(
            settingsLoadResult = loadResult,
            effectiveSettings = document?.settings,
        )

        val settings = document?.settings
        if (settings?.automaticBackup?.enabled == true) {
            val failure = startScheduler(settings)
            _state.update {
                it.copy(
                    schedulerRunning = scheduler.isRunning,
                    operationFailure = failure,
                )
            }
        }
        _state.value
    }

    suspend fun applySettings(settings: DesktopSettings): DesktopSettingsApplyResult =
        lifecycleMutex.withLock {
            check(!closed) { "Automatic backup runtime is closed" }
            check(initialized) { "Automatic backup runtime is not initialized" }
            val previousDocument = currentDocument
                ?: return@withLock invalidStateFailure(
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
                    it.copy(
                        schedulerRunning = scheduler.isRunning,
                        operationFailure = failure,
                    )
                }
                return@withLock DesktopSettingsApplyResult.Failed(failure)
            }

            currentDocument = savedDocument
            val loaded = DesktopSettingsLoadResult.Loaded(savedDocument)
            _state.update {
                it.copy(
                    settingsLoadResult = loaded,
                    effectiveSettings = settings,
                    schedulerRunning = false,
                    operationFailure = null,
                )
            }

            if (settings.automaticBackup.enabled) {
                val failure = startScheduler(settings)
                _state.update {
                    it.copy(
                        schedulerRunning = scheduler.isRunning,
                        operationFailure = failure,
                    )
                }
                if (failure != null) {
                    return@withLock DesktopSettingsApplyResult.Failed(failure)
                }
            }
            DesktopSettingsApplyResult.Applied(settings)
        }

    suspend fun close() = lifecycleMutex.withLock {
        if (closed) return@withLock
        closed = true
        scheduler.close()
        _state.update { it.copy(schedulerRunning = false) }
    }

    private fun startScheduler(settings: DesktopSettings): DesktopAutomaticBackupRuntimeFailure? =
        try {
            if (!scheduler.start(settings.automaticBackup.schedule())) {
                DesktopAutomaticBackupRuntimeFailure(
                    stage = DesktopAutomaticBackupRuntimeFailureStage.START,
                    error = IllegalStateException("Automatic backup scheduler is already running"),
                )
            } else {
                null
            }
        } catch (error: Exception) {
            DesktopAutomaticBackupRuntimeFailure(
                stage = DesktopAutomaticBackupRuntimeFailureStage.START,
                error = error,
            )
        }

    private fun invalidStateFailure(message: String): DesktopSettingsApplyResult.Failed {
        val failure = DesktopAutomaticBackupRuntimeFailure(
            stage = DesktopAutomaticBackupRuntimeFailureStage.INVALID_STATE,
            error = IllegalStateException(message),
        )
        _state.update { it.copy(operationFailure = failure) }
        return DesktopSettingsApplyResult.Failed(failure)
    }

    private fun recordEvent(event: DesktopAutomaticBackupEvent) {
        _state.update { current ->
            current.copy(
                schedulerRunning = scheduler.isRunning,
                latestEvent = event,
                latestExecutionFailure = (event as? DesktopAutomaticBackupEvent.Failed)?.error,
                latestCleanupWarnings = (event as? DesktopAutomaticBackupEvent.Created)
                    ?.result
                    ?.pruneResult
                    ?.cleanupWarnings
                    .orEmpty(),
            )
        }
    }
}
