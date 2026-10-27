@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** A Frame supplies native maximize/restore; the editor remains the logical modal owner.
 * This lease only changes the captured parent, never other app windows or global modality. */
internal class DesktopFishWindowOwnerLease(
    private val owner: java.awt.Window,
    onOwnerClosing: () -> Unit,
) : AutoCloseable {
    private val enabled = owner.isEnabled
    private var closed = false
    private val listener = object : WindowAdapter() {
        override fun windowClosing(event: WindowEvent) = onOwnerClosing()
        override fun windowClosed(event: WindowEvent) = onOwnerClosing()
    }
    init {
        check(EventQueue.isDispatchThread())
        owner.addWindowListener(listener)
        owner.isEnabled = false
    }
    override fun close() {
        check(EventQueue.isDispatchThread())
        if (closed) return
        closed = true
        owner.removeWindowListener(listener)
        owner.isEnabled = enabled
        if (enabled && owner.isShowing) { owner.toFront(); owner.requestFocus() }
    }
}

@Composable
internal fun DesktopFishPickerWindow(
    onClose: () -> Unit,
    state: WindowState = rememberWindowState(size = rememberDesktopImageWindowSize(1080.dp, 760.dp)),
    title: String = "Fish Audio 音色库",
    content: @Composable ColumnScope.() -> Unit,
) {
    val owner = LocalWindow.current
    val close by rememberUpdatedState(onClose)
    Window(onCloseRequest = { close() }, title = title, state = state,
        icon = desktopImageWindowIcon(), resizable = true,
        // Let a focused choice popup consume Escape first; otherwise it closes this picker.
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { close(); true } else false
        }) {
        DisposableEffect(window, owner) {
            window.minimumSize = Dimension(360, 360)
            val lease = owner?.takeIf { it !== window }?.let { DesktopFishWindowOwnerLease(it) { close() } }
            onDispose { lease?.close() }
        }
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
