package com.example.chatbar.desktop

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent

/** Watch the entire owned-window tree; root-to-dialog focus changes preserve the current lease. */
internal class DesktopLanSyncManagementFocus(root: Window, lock: () -> Unit) : AutoCloseable {
    private val toolkit = Toolkit.getDefaultToolkit()
    private val listener = AWTEventListener { event ->
        if (event is WindowEvent && event.id == WindowEvent.WINDOW_LOST_FOCUS &&
            desktopLanSyncManagementLeavesApp(root, event.window, event.oppositeWindow) { it.owner }) lock()
    }
    init { toolkit.addAWTEventListener(listener, AWTEvent.WINDOW_FOCUS_EVENT_MASK) }
    override fun close() = toolkit.removeAWTEventListener(listener)
}

/** Owner-graph policy is testable without constructing native windows or requesting desktop input. */
internal fun <T : Any> desktopLanSyncManagementLeavesApp(root: T, losing: T, gaining: T?, owner: (T) -> T?): Boolean {
    fun inside(value: T?): Boolean {
        var current = value
        while (current != null) { if (current === root) return true; current = owner(current) }
        return false
    }
    return inside(losing) && !inside(gaining)
}
