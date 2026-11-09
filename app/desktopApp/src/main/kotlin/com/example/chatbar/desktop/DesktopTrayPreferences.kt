package com.example.chatbar.desktop

import androidx.compose.runtime.*

@Composable
internal fun DesktopCloseToTrayPreference(tray: DesktopTrayController? = LocalDesktopTray.current, enabled: Boolean = true) {
    tray ?: return
    val state by tray.state.collectAsState()
    StudioSwitch("关闭主窗口时最小化到托盘", state.closeWindowToTray,
        enabled = enabled && state.notificationsConfigurable) { tray.setCloseWindowToTray(!state.closeWindowToTray) }
    StatusText("启用后关闭主窗口仍继续任务；真正退出请使用托盘菜单“退出”。托盘不可用时正常退出。")
}
