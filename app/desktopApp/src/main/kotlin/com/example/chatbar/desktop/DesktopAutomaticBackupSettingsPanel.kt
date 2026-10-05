package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration

@Composable
internal fun DesktopAutomaticBackupSettingsPanel(controller: DesktopAutomaticBackupSettingsController) {
    val state by controller.state.collectAsState()
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    var advanced by remember { mutableStateOf(false) }
    val status = desktopBackupStatus(state.runtime)
    Column(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(8.dp))
        .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusText(t(DesktopUiText.AUTOMATIC_BACKUP), colors.foreground)
        StatusText("${t(DesktopUiText.AUTOMATIC_BACKUP)}: ${t(status.enabled)}")
        StatusText("${t(DesktopUiText.BACKUP_SCHEDULER)}: ${t(status.scheduler)}")
        status.mode?.let { StatusText(t(it)) }
        status.loadFailure?.let { StatusText(t(it), colors.destructive) }
        status.operationFailure?.let { StatusText(t(it), colors.destructive) }
        StatusText(t(status.event), if (status.executionFailed) colors.destructive else colors.foreground)
        if (status.executionFailed && status.event != DesktopUiText.BACKUP_EXECUTION_FAILED)
            StatusText(t(DesktopUiText.BACKUP_EXECUTION_FAILED), colors.destructive)
        if (status.cleanupWarningCount > 0)
            StatusText("${t(DesktopUiText.BACKUP_CLEANUP_WARNING)}: ${status.cleanupWarningCount}", colors.warning)
        state.runtime.effectiveSettings?.automaticBackup?.let { effective ->
            StatusText(t(DesktopUiText.BACKUP_EFFECTIVE))
            StatusText("${t(DesktopUiText.BACKUP_MINIMUM)}: ${desktopBackupDurationSummary(effective.minimumBackupInterval, t)}")
            StatusText("${t(DesktopUiText.BACKUP_COUNT)}: ${effective.maximumSnapshotCount}")
            StatusText("${t(DesktopUiText.BACKUP_CHECK)}: ${desktopBackupDurationSummary(effective.checkInterval, t)}")
        }
        state.draft?.let { draft ->
            val editable = state.editable && !state.busy
            StatusText(t(DesktopUiText.BACKUP_DRAFT_HINT))
            BootstrapButton("${t(DesktopUiText.AUTOMATIC_BACKUP)}: ${t(if (draft.enabled) DesktopUiText.BACKUP_ENABLED else DesktopUiText.BACKUP_DISABLED)}",
                enabled = editable, secondary = !draft.enabled) { controller.edit { it.copy(enabled = !it.enabled) } }
            BackupSettingsField(t(DesktopUiText.BACKUP_MINIMUM), draft.minimumInterval, editable) { value ->
                controller.edit { it.copy(minimumInterval = value) }
            }
            StatusText(t(DesktopUiText.BACKUP_DURATION_HINT))
            BackupSettingsField(t(DesktopUiText.BACKUP_COUNT), draft.maximumCount, editable) { value ->
                controller.edit { it.copy(maximumCount = value) }
            }
            BootstrapButton(t(DesktopUiText.BACKUP_ADVANCED), secondary = true) { advanced = !advanced }
            if (advanced) {
                BackupSettingsField(t(DesktopUiText.BACKUP_CHECK), draft.checkInterval, editable) { value ->
                    controller.edit { it.copy(checkInterval = value) }
                }
                StatusText(t(DesktopUiText.BACKUP_CHECK_HINT))
            }
            draft.validationErrors().forEach { StatusText(t(it), colors.destructive) }
            if (state.dirty) StatusText(t(DesktopUiText.UNSAVED_CHANGES))
            if (state.busy) StatusText(t(DesktopUiText.WORKING))
            state.notice?.let { StatusText(t(it), if (it == DesktopUiText.BACKUP_APPLY_FAILED) colors.destructive else colors.foreground) }
            ActionRow {
                BootstrapButton(t(DesktopUiText.BACKUP_APPLY), enabled = state.canApply, onClick = controller::requestApply)
                BootstrapButton(t(DesktopUiText.DISCARD_CHANGES), enabled = state.dirty && !state.busy,
                    secondary = true, onClick = controller::discard)
            }
        }
    }
}

@Composable
private fun BackupSettingsField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        BasicTextField(value, onChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp)).padding(10.dp))
    }
}

internal fun desktopBackupDurationSummary(value: Duration, t: DesktopUiStrings): String {
    if (value.nano != 0) return value.toString()
    val seconds = value.seconds
    val (amount, unit) = when {
        seconds != 0L && seconds % 86400L == 0L -> seconds / 86400 to DesktopUiText.BACKUP_DAYS
        seconds != 0L && seconds % 3600L == 0L -> seconds / 3600 to DesktopUiText.BACKUP_HOURS
        seconds != 0L && seconds % 60L == 0L -> seconds / 60 to DesktopUiText.BACKUP_MINUTES
        else -> seconds to DesktopUiText.BACKUP_SECONDS
    }
    return "$amount ${t(unit)}"
}
