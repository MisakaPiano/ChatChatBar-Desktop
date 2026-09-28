package com.example.chatbar.desktop

import androidx.compose.ui.input.key.Key

internal enum class DesktopModelEditorCommand { SAVE, CLOSE }

internal fun desktopModelEditorCommand(key: Key, ctrlPressed: Boolean): DesktopModelEditorCommand? = when {
    key == Key.S && ctrlPressed -> DesktopModelEditorCommand.SAVE
    key == Key.Escape -> DesktopModelEditorCommand.CLOSE
    else -> null
}
