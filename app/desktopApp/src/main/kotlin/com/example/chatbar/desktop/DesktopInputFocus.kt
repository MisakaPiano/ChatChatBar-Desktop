package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

internal enum class DesktopModalKind { GENERAL, BACKUP, TASK_CENTER }
internal class DesktopModalActivity {
    var count by mutableStateOf(0)
        private set
    var backupCount by mutableStateOf(0)
        private set
    private var taskCount by mutableStateOf(0)
    val otherCount get() = count - backupCount - taskCount
    fun opened(kind: DesktopModalKind = DesktopModalKind.GENERAL) {
        count++
        when (kind) { DesktopModalKind.BACKUP -> backupCount++; DesktopModalKind.TASK_CENTER -> taskCount++; else -> Unit }
    }
    fun closed(kind: DesktopModalKind = DesktopModalKind.GENERAL) {
        check(count > 0)
        when (kind) {
            DesktopModalKind.BACKUP -> { check(backupCount > 0); backupCount-- }
            DesktopModalKind.TASK_CENTER -> { check(taskCount > 0); taskCount-- }
            DesktopModalKind.GENERAL -> check(otherCount > 0)
        }
        count--
    }
}
internal val LocalDesktopModalActivity = staticCompositionLocalOf<DesktopModalActivity?> { null }

/** Native Compose focus isolation, retaining in-app bounds and explicit Close/Cancel decisions. */
@Composable
internal fun DesktopModalSurface(reportActivity: Boolean = true, kind: DesktopModalKind = DesktopModalKind.GENERAL,
    content: @Composable () -> Unit) {
    val activity = LocalDesktopModalActivity.current
    DisposableEffect(activity, reportActivity, kind) {
        if (reportActivity) activity?.opened(kind)
        onDispose { if (reportActivity) activity?.closed(kind) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        Popup(alignment = Alignment.TopStart, properties = PopupProperties(
            focusable = true, dismissOnBackPress = false, dismissOnClickOutside = false,
        )) {
            Box(Modifier.size(width, height)) { content() }
        }
    }
}

internal fun desktopComposerSendKey(
    key: Key, type: KeyEventType, ctrl: Boolean, input: TextFieldValue, canLaunch: Boolean,
    mode: DesktopSendKey = DesktopSendKey.CTRL_ENTER, shift: Boolean = false, altOrMeta: Boolean = false,
    hasAttachments: Boolean = false, send: () -> Unit,
): Boolean {
    if (type != KeyEventType.KeyDown || key !in setOf(Key.Enter, Key.NumPadEnter) ||
        ctrl != (mode == DesktopSendKey.CTRL_ENTER) || shift || altOrMeta || input.composition != null ||
        !canLaunch || (input.text.isBlank() && !hasAttachments)) return false
    send()
    return true
}

internal fun desktopComposerDraftEcho(input: TextFieldValue, draft: String): TextFieldValue =
    if (input.text != draft && input.composition == null) TextFieldValue(draft) else input

/** Only the focused Model text field can veto an editor command while composing. */
internal class DesktopModelInputFocus {
    private var owner: Any? = null
    var composing by mutableStateOf(false)
        private set
    fun focused(token: Any, composition: Boolean) { owner = token; composing = composition }
    fun changed(token: Any, composition: Boolean) { if (owner === token) composing = composition }
    fun blurred(token: Any) { if (owner === token) { owner = null; composing = false } }
}

internal val LocalDesktopModelInputFocus = staticCompositionLocalOf<DesktopModelInputFocus?> { null }
