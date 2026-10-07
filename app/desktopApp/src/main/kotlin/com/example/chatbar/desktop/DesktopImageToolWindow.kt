package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*

/** Shared, resizable OS tool window. Content owns navigation, the OS owns the single title. */
@Composable
internal fun DesktopImageToolWindow(title: String, onClose: () -> Unit, width: Dp = 920.dp, height: Dp = 760.dp,
    onActivate: () -> Unit = {}, onKey: (KeyEvent) -> Boolean = { false }, content: @Composable ColumnScope.() -> Unit) {
    val activate by rememberUpdatedState(onActivate)
    Window(onCloseRequest = onClose, title = title, resizable = true,
        state = rememberWindowState(width = width, height = height), onPreviewKeyEvent = {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true } else onKey(it)
        }) {
        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowAdapter() { override fun windowGainedFocus(e: java.awt.event.WindowEvent) { activate() } }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
