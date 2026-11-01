package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class DesktopRecoveryDestination { EXPORT, CHECK, IMPORT, LOCATION, AUTO, UPDATE }
internal enum class DesktopRecoveryArea { DIRECTORY, BACKUP, MERGE }
internal enum class DesktopRecoveryPhase { IDLE, UNSUPPORTED, CONFIRMATION, RUNNING, RETRYABLE, RESTART, ATTENTION, CHECKED, EXPORTED, PREVIEW, UNAVAILABLE }
internal data class DesktopRecoveryStatus(val area: DesktopRecoveryArea, val phase: DesktopRecoveryPhase)

/** Projects only already-known typed owner state; never reads files, payloads or raw error text. */
internal fun desktopRecoveryStatuses(root: DesktopDataRootSwitchState, backup: DesktopFullBackupState,
    merge: DesktopMergeImportState?): List<DesktopRecoveryStatus> = listOf(
    DesktopRecoveryStatus(DesktopRecoveryArea.DIRECTORY, when (root) {
        is DesktopDataRootSwitchState.Idle -> if (root.supported) DesktopRecoveryPhase.IDLE else DesktopRecoveryPhase.UNSUPPORTED
        is DesktopDataRootSwitchState.CandidateSelected -> DesktopRecoveryPhase.CONFIRMATION
        is DesktopDataRootSwitchState.Migrating -> DesktopRecoveryPhase.RUNNING
        is DesktopDataRootSwitchState.RetryableFailure -> DesktopRecoveryPhase.RETRYABLE
        is DesktopDataRootSwitchState.RestartRequired -> DesktopRecoveryPhase.RESTART
    }),
    DesktopRecoveryStatus(DesktopRecoveryArea.BACKUP, when {
        backup.restartRequired -> DesktopRecoveryPhase.RESTART
        backup.busy -> DesktopRecoveryPhase.RUNNING
        backup.error != null -> DesktopRecoveryPhase.ATTENTION
        backup.summary != null -> DesktopRecoveryPhase.CHECKED
        backup.exportResult?.success == true -> DesktopRecoveryPhase.EXPORTED
        else -> DesktopRecoveryPhase.IDLE
    }),
    DesktopRecoveryStatus(DesktopRecoveryArea.MERGE, when {
        merge == null -> DesktopRecoveryPhase.UNAVAILABLE
        merge.restartRequired -> DesktopRecoveryPhase.RESTART
        merge.busy -> DesktopRecoveryPhase.RUNNING
        merge.error != null -> DesktopRecoveryPhase.ATTENTION
        merge.ready -> DesktopRecoveryPhase.PREVIEW
        else -> DesktopRecoveryPhase.IDLE
    }),
)

internal fun desktopRecoveryText(status: DesktopRecoveryStatus): DesktopUiText = when (status.area) {
    DesktopRecoveryArea.DIRECTORY -> when (status.phase) {
        DesktopRecoveryPhase.UNSUPPORTED -> DesktopUiText.RECOVERY_ROOT_UNSUPPORTED
        DesktopRecoveryPhase.CONFIRMATION -> DesktopUiText.RECOVERY_ROOT_CONFIRM
        DesktopRecoveryPhase.RUNNING -> DesktopUiText.RECOVERY_ROOT_RUNNING
        DesktopRecoveryPhase.RETRYABLE -> DesktopUiText.RECOVERY_ROOT_RETRY
        DesktopRecoveryPhase.RESTART -> DesktopUiText.RECOVERY_ROOT_RESTART
        else -> DesktopUiText.RECOVERY_ROOT_IDLE
    }
    DesktopRecoveryArea.BACKUP -> when (status.phase) {
        DesktopRecoveryPhase.RUNNING -> DesktopUiText.RECOVERY_BACKUP_RUNNING
        DesktopRecoveryPhase.RESTART -> DesktopUiText.RECOVERY_BACKUP_RESTART
        DesktopRecoveryPhase.ATTENTION -> DesktopUiText.RECOVERY_BACKUP_ATTENTION
        DesktopRecoveryPhase.CHECKED -> DesktopUiText.RECOVERY_BACKUP_CHECKED
        DesktopRecoveryPhase.EXPORTED -> DesktopUiText.RECOVERY_BACKUP_EXPORTED
        else -> DesktopUiText.RECOVERY_BACKUP_IDLE
    }
    DesktopRecoveryArea.MERGE -> when (status.phase) {
        DesktopRecoveryPhase.UNAVAILABLE -> DesktopUiText.RECOVERY_MERGE_UNAVAILABLE
        DesktopRecoveryPhase.RUNNING -> DesktopUiText.RECOVERY_MERGE_RUNNING
        DesktopRecoveryPhase.RESTART -> DesktopUiText.RECOVERY_MERGE_RESTART
        DesktopRecoveryPhase.ATTENTION -> DesktopUiText.RECOVERY_MERGE_ATTENTION
        DesktopRecoveryPhase.PREVIEW -> DesktopUiText.RECOVERY_MERGE_PREVIEW
        else -> DesktopUiText.RECOVERY_MERGE_IDLE
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DesktopRecoveryPanel(statuses: List<DesktopRecoveryStatus>, enabled: Boolean,
    onNavigate: (DesktopRecoveryDestination) -> Unit) {
    val t = LocalDesktopUiStrings.current
    DesktopDataSectionHeading(t(DesktopUiText.RECOVERY_SUPPORT))
    StatusText(t(DesktopUiText.RECOVERY_SCOPE))
    statuses.forEach { status ->
        DesktopDataSectionHeading(t(when (status.area) {
            DesktopRecoveryArea.DIRECTORY -> DesktopUiText.DATA_DIRECTORY
            DesktopRecoveryArea.BACKUP -> DesktopUiText.RECOVERY_BACKUP_TITLE
            DesktopRecoveryArea.MERGE -> DesktopUiText.RECOVERY_MERGE_TITLE
        }))
        StatusText(t(desktopRecoveryText(status)))
    }
    StatusText(t(DesktopUiText.RECOVERY_PRESERVE))
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DesktopRecoveryDestination.entries.forEach { destination ->
            val label = when (destination) {
                DesktopRecoveryDestination.EXPORT -> DesktopUiText.RECOVERY_OPEN_EXPORT
                DesktopRecoveryDestination.CHECK -> DesktopUiText.RECOVERY_OPEN_CHECK
                DesktopRecoveryDestination.IMPORT -> DesktopUiText.RECOVERY_OPEN_IMPORT
                DesktopRecoveryDestination.LOCATION -> DesktopUiText.RECOVERY_OPEN_LOCATION
                DesktopRecoveryDestination.AUTO -> DesktopUiText.RECOVERY_OPEN_AUTO
                DesktopRecoveryDestination.UPDATE -> DesktopUiText.RECOVERY_OPEN_SUPPORT
            }
            BootstrapButton(t(label), secondary = true,
                enabled = enabled && (destination != DesktopRecoveryDestination.IMPORT || statuses.none { it.area == DesktopRecoveryArea.MERGE && it.phase == DesktopRecoveryPhase.UNAVAILABLE })) {
                onNavigate(destination)
            }
        }
    }
}
