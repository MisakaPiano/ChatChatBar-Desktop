package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Synthetic presentation seam; production always uses the ordinary resizable native tool window. */
internal val LocalDesktopEditorAuxiliaryFrame = staticCompositionLocalOf<(@Composable (@Composable () -> Unit) -> Unit)?> { null }

@Composable
internal fun DesktopEditorAuxiliaryWindow(title: String, onClose: () -> Unit,
    width: Dp = 1000.dp, height: Dp = 820.dp, content: @Composable () -> Unit) {
    val activity = LocalDesktopModalActivity.current
    DisposableEffect(activity) {
        activity?.opened()
        onDispose { activity?.closed() }
    }
    val frame = LocalDesktopEditorAuxiliaryFrame.current
    if (frame != null) frame(content)
    else DesktopImageToolWindow(title, onClose, width, height) {
        Column(Modifier.fillMaxSize()) { content() }
    }
}
