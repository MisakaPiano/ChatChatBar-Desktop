package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ConditionalMessageUpdateResult
import com.example.chatbar.domain.chat.MessageFormatRepairPolicy

internal fun desktopCanRestoreFormat(message: ChatMessage): Boolean = message.role == MessageRole.ASSISTANT &&
    MessageFormatRepairPolicy.recoverableNotice(message)?.kind in
        setOf(MessageFormatRepairNoticeKind.APPLIED, MessageFormatRepairNoticeKind.STOPPED)

internal fun desktopRepairStateFor(message: ChatMessage, states: Map<String, DesktopRepairState>): DesktopRepairState? {
    val latest = states.values.lastOrNull { it.identity?.sessionId == message.sessionId && it.identity.messageId == message.id }
        ?: return null
    // Preserve uncertainty/conflict warnings even when a reload reveals changed durable content.
    if (latest.status in setOf(DesktopRepairStatus.COMMIT_UNCERTAIN, DesktopRepairStatus.CONFLICT,
            DesktopRepairStatus.STORAGE_FAILURE, DesktopRepairStatus.CORRUPT, DesktopRepairStatus.MISSING)) return latest
    val basis = (latest.commit as? ConditionalMessageUpdateResult.Success)?.message ?: latest.source ?: return latest
    return latest.takeIf { basis.displayContent == message.displayContent &&
        basis.currentAlternativeIndex == message.currentAlternativeIndex &&
        basis.currentAlternativeVersionId == message.currentAlternativeVersionId && basis.alternativeVersionIds == message.alternativeVersionIds }
}

internal fun desktopRepairStatusText(state: DesktopRepairState): DesktopUiText = when (state.status) {
    DesktopRepairStatus.RUNNING -> DesktopUiText.FORMAT_CHECKING
    DesktopRepairStatus.APPLIED -> DesktopUiText.FORMAT_APPLIED
    DesktopRepairStatus.UNCHANGED -> DesktopUiText.FORMAT_UNCHANGED
    DesktopRepairStatus.STOPPED -> DesktopUiText.FORMAT_STOPPED
    DesktopRepairStatus.RESTORED -> DesktopUiText.FORMAT_RESTORED
    DesktopRepairStatus.CANCELLED -> DesktopUiText.FORMAT_CANCELLED
    DesktopRepairStatus.CONFLICT, DesktopRepairStatus.MISSING -> DesktopUiText.FORMAT_CHANGED
    DesktopRepairStatus.STORAGE_FAILURE, DesktopRepairStatus.CORRUPT -> DesktopUiText.FORMAT_STORAGE_ERROR
    DesktopRepairStatus.COMMIT_UNCERTAIN -> DesktopUiText.FORMAT_UNCERTAIN
    DesktopRepairStatus.ERROR, DesktopRepairStatus.REJECTED -> when (state.code) {
        "DERIVED_DATA_UNPROVEN", "DERIVED_DATA_CHANGED" -> DesktopUiText.FORMAT_UNPROVEN
        "NO_RULES" -> DesktopUiText.FORMAT_NO_RULES
        "INVALID_MODEL", "AUTHENTICATION_REQUIRED" -> DesktopUiText.FORMAT_INVALID_MODEL
        "INVALID_TARGET", "INVALID_SESSION", "NO_RECOVERY_POINT" -> DesktopUiText.FORMAT_UNAVAILABLE
        else -> DesktopUiText.FORMAT_FAILED
    }
}

@Composable
internal fun DesktopMessageFormatRepairStatus(message: ChatMessage, runtimeState: DesktopRepairState?,
    running: Boolean, onStop: () -> Unit, onRestore: () -> Unit, mutationEnabled: Boolean = true) {
    if (message.role != MessageRole.ASSISTANT) return
    val t = LocalDesktopUiStrings.current
    val notice = MessageFormatRepairPolicy.applicableNotice(message)
    val text = when {
        runtimeState != null -> desktopRepairStatusText(runtimeState)
        running -> DesktopUiText.FORMAT_CHECKING
        notice?.kind == MessageFormatRepairNoticeKind.ERROR -> DesktopUiText.FORMAT_FAILED
        notice?.kind == MessageFormatRepairNoticeKind.STOPPED -> DesktopUiText.FORMAT_STOPPED
        notice != null -> DesktopUiText.FORMAT_APPLIED
        else -> return
    }
    Column(Modifier.fillMaxWidth().testTag("format-repair-status-${message.id}"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        StatusText(t(text)) // Never render persisted/provider errorMessage verbatim.
        if (running && runtimeState?.preview != null) {
            StatusText(t(DesktopUiText.FORMAT_PREVIEW))
            Box(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).testTag("format-repair-preview-${message.id}")) {
                // Preview owns no source menus/selection. All message copy actions still read persisted source.
                BasicText(runtimeState.preview, style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 12.sp))
            }
        }
        if (running) BootstrapButton(t(DesktopUiText.FORMAT_STOP), secondary = true, onClick = onStop)
        else if (desktopCanRestoreFormat(message) && runtimeState?.status != DesktopRepairStatus.COMMIT_UNCERTAIN)
            BootstrapButton(t(DesktopUiText.FORMAT_RESTORE), enabled = mutationEnabled, secondary = true, onClick = onRestore)
    }
}

@Composable
internal fun DesktopFormatRepairModelSetting(state: DesktopModelSettingsState, onChoose: (String?) -> Unit) {
    val selected = state.settings?.formatRepairModelId
    val t = LocalDesktopUiStrings.current
    val options = state.availableAuxiliaryTextModels
    val ids = listOf<String?>(null) + options.map { it.id } + listOfNotNull(selected?.takeIf { id -> options.none { it.id == id } })
    SearchableChoice(t(DesktopUiText.FORMAT_REPAIR_MODEL), ids, selected, { id ->
        if (id == null) t(DesktopUiText.FOLLOW_CHAT_DEFAULT_MODEL)
        else options.firstOrNull { it.id == id }?.displayName ?: "${t(DesktopUiText.SELECTED_UNAVAILABLE)} · $id"
    }, onChoose)
    if (selected != null && options.none { it.id == selected }) StatusText(t(DesktopUiText.SPECIFIED_UNAVAILABLE), DesktopBootstrapColors.warning)
}
