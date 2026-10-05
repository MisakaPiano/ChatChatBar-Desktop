package com.example.chatbar.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

internal enum class DesktopModelEditorCommand { SAVE, CLOSE }

internal fun desktopModelEditorCommand(key: Key, ctrlPressed: Boolean, composing: Boolean = false): DesktopModelEditorCommand? = when {
    composing -> null
    key == Key.S && ctrlPressed -> DesktopModelEditorCommand.SAVE
    key == Key.Escape -> DesktopModelEditorCommand.CLOSE
    else -> null
}

internal fun desktopModelEditorKey(
    key: Key, type: KeyEventType, ctrl: Boolean, composing: Boolean, dirty: Boolean, busy: Boolean,
    save: () -> Unit, close: () -> Unit,
): Boolean {
    if (type != KeyEventType.KeyDown) return false
    return when (desktopModelEditorCommand(key, ctrl, composing)) {
        DesktopModelEditorCommand.SAVE -> { if (dirty && !busy) save(); true }
        DesktopModelEditorCommand.CLOSE -> { close(); true }
        null -> false
    }
}
