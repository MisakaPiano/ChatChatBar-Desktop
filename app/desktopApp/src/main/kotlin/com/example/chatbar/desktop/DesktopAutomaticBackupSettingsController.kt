package com.example.chatbar.desktop

import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

internal data class DesktopBackupSettingsDraft(
    val enabled: Boolean,
    val minimumInterval: String,
    val maximumCount: String,
    val checkInterval: String,
) {
    fun validationErrors(): List<DesktopUiText> = buildList {
        if (desktopBackupDuration(minimumInterval)?.let { !it.isNegative } != true)
            add(DesktopUiText.BACKUP_INVALID_MINIMUM)
        if (maximumCount.trim().toIntOrNull()?.let { it >= 1 } != true)
            add(DesktopUiText.BACKUP_INVALID_COUNT)
        if (desktopBackupDuration(checkInterval)?.let { !it.isNegative && !it.isZero } != true)
            add(DesktopUiText.BACKUP_INVALID_CHECK)
    }

    fun settings(): DesktopAutomaticBackupSettings? = if (validationErrors().isNotEmpty()) null else
        DesktopAutomaticBackupSettings(enabled, desktopBackupDuration(minimumInterval)!!,
            maximumCount.trim().toInt(), desktopBackupDuration(checkInterval)!!)

    companion object {
        fun from(settings: DesktopAutomaticBackupSettings) = DesktopBackupSettingsDraft(
            settings.enabled, settings.minimumBackupInterval.toString(),
            settings.maximumSnapshotCount.toString(), settings.checkInterval.toString(),
        )
    }
}

/** ISO Duration remains the lossless path, including fractional seconds and large existing values. */
internal fun desktopBackupDuration(input: String): Duration? {
    val text = input.trim()
    return try {
        val short = Regex("([+-]?\\d+)([mhd])", RegexOption.IGNORE_CASE).matchEntire(text)
        if (short == null) Duration.parse(text) else {
            val amount = short.groupValues[1].toLong()
            when (short.groupValues[2].lowercase()) {
                "m" -> Duration.ofMinutes(amount)
                "h" -> Duration.ofHours(amount)
                else -> Duration.ofDays(amount)
            }
        }
    } catch (_: IllegalArgumentException) { null }
      catch (_: java.time.format.DateTimeParseException) { null }
      catch (_: ArithmeticException) { null }
}

internal data class DesktopBackupSettingsUiState(
    val runtime: DesktopAutomaticBackupRuntimeState,
    val draft: DesktopBackupSettingsDraft? = runtime.effectiveSettings?.automaticBackup?.let(DesktopBackupSettingsDraft::from),
    val baseline: DesktopBackupSettingsDraft? = draft,
    val busy: Boolean = false,
    val notice: DesktopUiText? = null,
) {
    val dirty get() = draft != baseline
    val editable get() = runtime.mode == DesktopAutomaticBackupRuntimeMode.ACTIVE &&
        runtime.effectiveSettings != null && (runtime.settingsLoadResult is DesktopSettingsLoadResult.Missing ||
        runtime.settingsLoadResult is DesktopSettingsLoadResult.Loaded)
    val canApply get() = editable && !busy && dirty && draft?.settings() != null
}

/** In-memory UI draft only. Runtime remains the sole lifecycle, storage and execution authority. */
internal class DesktopAutomaticBackupSettingsController(
    private val runtime: DesktopAutomaticBackupRuntime,
    private val applicationScope: CoroutineScope,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(DesktopBackupSettingsUiState(runtime.state.value))
    val state = mutableState.asStateFlow()

    // Collected at application composition lifetime, not the Settings section lifetime.
    suspend fun observe(): Unit = runtime.state.collect { current ->
        synchronized(lock) {
            val previous = mutableState.value
            val fresh = current.effectiveSettings?.automaticBackup?.let(DesktopBackupSettingsDraft::from)
            mutableState.value = if (!previous.dirty && !previous.busy)
                previous.copy(runtime = current, draft = fresh, baseline = fresh)
            else previous.copy(runtime = current)
        }
    }

    fun edit(change: (DesktopBackupSettingsDraft) -> DesktopBackupSettingsDraft) = synchronized(lock) {
        val current = mutableState.value.copy(runtime = runtime.state.value)
        if (current.editable && !current.busy && current.draft != null)
            mutableState.value = current.copy(draft = change(current.draft), notice = null)
    }

    fun discard() = synchronized(lock) {
        if (!mutableState.value.busy) mutableState.value = DesktopBackupSettingsUiState(runtime.state.value)
    }

    // Leaving Manage/Settings must not cancel a runtime Apply already requested by the user.
    fun requestApply() { applicationScope.launch { apply() } }

    suspend fun apply() {
        val settings = synchronized(lock) {
            val current = mutableState.value.copy(runtime = runtime.state.value)
            if (!current.canApply) return
            mutableState.value = current.copy(busy = true, notice = null)
            current.runtime.effectiveSettings!!.copy(automaticBackup = current.draft!!.settings()!!)
        }
        try {
            when (runtime.applySettings(settings)) {
                is DesktopSettingsApplyResult.Applied -> synchronized(lock) {
                    mutableState.value = DesktopBackupSettingsUiState(runtime.state.value,
                        busy = true, notice = DesktopUiText.BACKUP_APPLIED)
                }
                is DesktopSettingsApplyResult.Failed -> failed()
            }
        } catch (cancelled: CancellationException) {
            failed()
            throw cancelled
        } catch (_: Exception) {
            failed()
        } finally {
            synchronized(lock) {
                mutableState.value = mutableState.value.copy(runtime = runtime.state.value, busy = false)
            }
        }
    }

    private fun failed() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(runtime = runtime.state.value, notice = DesktopUiText.BACKUP_APPLY_FAILED)
    }
}

internal data class DesktopBackupStatusPresentation(
    val enabled: DesktopUiText,
    val scheduler: DesktopUiText,
    val mode: DesktopUiText?,
    val loadFailure: DesktopUiText?,
    val event: DesktopUiText,
    val executionFailed: Boolean,
    val cleanupWarningCount: Int,
    val operationFailure: DesktopUiText?,
)

/** Deliberately uses safe categories; arbitrary exception/warning text can contain paths or secrets. */
internal fun desktopBackupStatus(state: DesktopAutomaticBackupRuntimeState) = DesktopBackupStatusPresentation(
    enabled = when (state.effectiveSettings?.automaticBackup?.enabled) {
        true -> DesktopUiText.BACKUP_ENABLED
        false -> DesktopUiText.BACKUP_DISABLED
        null -> DesktopUiText.BACKUP_UNAVAILABLE
    },
    scheduler = if (state.schedulerRunning) DesktopUiText.BACKUP_RUNNING else DesktopUiText.BACKUP_STOPPED,
    mode = when (state.mode) {
        DesktopAutomaticBackupRuntimeMode.NEW -> DesktopUiText.BACKUP_UNINITIALIZED
        DesktopAutomaticBackupRuntimeMode.ACTIVE -> null
        DesktopAutomaticBackupRuntimeMode.MAINTENANCE_PAUSED -> DesktopUiText.BACKUP_MAINTENANCE
        DesktopAutomaticBackupRuntimeMode.RESTART_REQUIRED -> DesktopUiText.BACKUP_RESTART
        DesktopAutomaticBackupRuntimeMode.CLOSED -> DesktopUiText.BACKUP_CLOSED
    },
    loadFailure = when (state.settingsLoadResult) {
        is DesktopSettingsLoadResult.Corrupt -> DesktopUiText.BACKUP_LOAD_CORRUPT
        is DesktopSettingsLoadResult.Invalid -> DesktopUiText.BACKUP_LOAD_INVALID
        is DesktopSettingsLoadResult.UnsupportedFormatVersion -> DesktopUiText.BACKUP_LOAD_UNSUPPORTED
        else -> null
    },
    event = when (state.latestEvent) {
        is DesktopAutomaticBackupEvent.Created -> DesktopUiText.BACKUP_CREATED
        DesktopAutomaticBackupEvent.SkippedNotDue -> DesktopUiText.BACKUP_NOT_DUE
        is DesktopAutomaticBackupEvent.Failed -> DesktopUiText.BACKUP_EXECUTION_FAILED
        null -> DesktopUiText.BACKUP_NO_EVENT
    },
    executionFailed = state.latestExecutionFailure != null || state.latestEvent is DesktopAutomaticBackupEvent.Failed,
    cleanupWarningCount = state.latestCleanupWarnings.size,
    operationFailure = state.operationFailure?.let { when (it.stage) {
        DesktopAutomaticBackupRuntimeFailureStage.SAVE -> DesktopUiText.BACKUP_SAVE_FAILED
        DesktopAutomaticBackupRuntimeFailureStage.START -> DesktopUiText.BACKUP_START_FAILED
        DesktopAutomaticBackupRuntimeFailureStage.STOP -> DesktopUiText.BACKUP_STOP_FAILED
        else -> DesktopUiText.BACKUP_OPERATION_FAILED
    } },
)
