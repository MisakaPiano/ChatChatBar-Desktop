package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.IdentityHashMap

/** Only Main's public WindowScope supplies this identity; no platform/internal window lookup. */
internal val LocalDesktopFishParentWindow = staticCompositionLocalOf<ComposeWindow?> { null }

/** A Frame supplies native maximize/restore; the editor remains the logical modal owner.
 * This lease only changes the captured parent, never other app windows or global modality. */
internal class DesktopFishWindowOwnerLease(
    private val owner: java.awt.Window,
    onOwnerClosing: () -> Unit,
) : AutoCloseable {
    private class Entry(val enabled: Boolean, var count: Int = 0)
    private companion object {
        // EDT-only, identity-keyed. Overlapping disposal must restore the original state once.
        val leases = IdentityHashMap<java.awt.Window, Entry>()
    }
    private var closed = false
    private val listener = object : WindowAdapter() {
        override fun windowClosing(event: WindowEvent) { if (!closed) onOwnerClosing() }
        override fun windowClosed(event: WindowEvent) { if (!closed) onOwnerClosing() }
    }
    init {
        check(EventQueue.isDispatchThread())
        val entry = leases.getOrPut(owner) { Entry(owner.isEnabled) }
        entry.count++
        owner.addWindowListener(listener)
        owner.isEnabled = false
    }
    override fun close() {
        check(EventQueue.isDispatchThread())
        if (closed) return
        closed = true
        owner.removeWindowListener(listener)
        val entry = checkNotNull(leases[owner])
        entry.count--
        if (entry.count == 0) {
            leases.remove(owner)
            owner.isEnabled = entry.enabled
            if (entry.enabled && owner.isShowing) { owner.toFront(); owner.requestFocus() }
        }
    }
}

@Composable
internal fun DesktopFishPickerWindow(
    onClose: () -> Unit,
    state: WindowState = rememberWindowState(size = rememberDesktopImageWindowSize(1080.dp, 760.dp)),
    title: String = "Fish Audio 音色库",
    content: @Composable ColumnScope.() -> Unit,
) {
    val owner = LocalDesktopFishParentWindow.current
    val close by rememberUpdatedState(onClose)
    if (owner == null) {
        StatusText("音色库未打开：主窗口身份不可用", DesktopBootstrapColors.destructive)
        StudioAction("关闭音色库", style = StudioActionStyle.GHOST) { close() }
        return
    }
    Window(onCloseRequest = { close() }, title = title, state = state,
        icon = desktopImageWindowIcon(), resizable = true,
        // Let a focused choice popup consume Escape first; otherwise it closes this picker.
        onKeyEvent = { event ->
            if (event.key == Key.Escape) println("FISH_R3_NATIVE_WINDOW ${event.type}")
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { close(); true } else false
        }) {
        DisposableEffect(window, owner) {
            window.minimumSize = Dimension(360, 360)
            val lease = DesktopFishWindowOwnerLease(owner) { close() }
            onDispose { lease.close() }
        }
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
