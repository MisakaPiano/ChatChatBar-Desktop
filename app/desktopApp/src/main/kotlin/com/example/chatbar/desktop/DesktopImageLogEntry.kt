package com.example.chatbar.desktop

import androidx.compose.runtime.*

/** Same process log owner, scoped to an actual image task or editor draft; never starts work. */
@Composable
internal fun DesktopImageLogEntry(owner: DesktopTransportDiagnosticsOwner, group: String?) {
    var open by remember(owner, group) { mutableStateOf(false) }
    StudioAction("生图日志", enabled = group != null) { open = true }
    if (open && group != null) DesktopChatDebugOverlay(owner, group, { open = false }, imageGeneration = true)
}
