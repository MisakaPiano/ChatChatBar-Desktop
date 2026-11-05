package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun desktopShowStartupUpdateNotice(state: DesktopUpdateNoticeState, blocked: Boolean): Boolean =
    state.showStartupNotice && state.pendingVersion != null && !blocked

@Composable
internal fun DesktopStartupUpdateNotice(state: DesktopUpdateNoticeState, blocked: Boolean,
    onView: () -> Unit, onLater: () -> Unit) {
    if (!desktopShowStartupUpdateNotice(state, blocked)) return
    DesktopModalSurface(reportActivity = false) {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 460.dp).padding(12.dp).background(DesktopBootstrapColors.card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopDataSectionHeading("发现新版本") }
                    DesktopChatIconAction("关闭更新提醒", DesktopAppIcons.Close, targetDp = 28, onClick = onLater)
                }
                StatusText("${state.pendingVersion} 已可用。可以现在查看更新，或稍后从带圆点的“数据 → 版本与更新”入口处理。")
                ActionRow {
                    BootstrapButton("查看更新", onClick = onView)
                    BootstrapButton("稍后", secondary = true, onClick = onLater)
                }
            }
        }
    }
}
