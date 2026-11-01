package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

internal fun DesktopWorkPhase.label() = when (this) {
    DesktopWorkPhase.RUNNING -> "进行中"
    DesktopWorkPhase.WAITING -> "等待确认"
    DesktopWorkPhase.COMPLETED -> "已完成"
    DesktopWorkPhase.FAILED -> "未完成"
    DesktopWorkPhase.CANCELLED -> "已停止"
    DesktopWorkPhase.UNCONFIRMED -> "需要核对"
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DesktopTaskCenterPanel(center: DesktopTaskCenter, onClose: () -> Unit,
    onView: (DesktopWorkTarget) -> Unit, onHideToTray: (() -> Unit)? = null, tray: DesktopTrayController? = null,
    settingsEnabled: Boolean = true) {
    val state by center.state.collectAsState()
    val trayState = tray?.state?.collectAsState()?.value
    var recent by remember { mutableStateOf(false) }
    val palette = LocalDesktopPalette.current
    DesktopModalSurface(kind = DesktopModalKind.TASK_CENTER) {
        Box(Modifier.fillMaxSize().background(palette.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 680.dp)
                .background(palette.card, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopDataSectionHeading("任务中心 · 进行中 ${state.rows.count { it.active }}") }
                    DesktopIconAction("关闭任务中心", DesktopAppIcons.Close, onClick = onClose)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BootstrapButton("进行中", secondary = recent) { recent = false }
                    BootstrapButton("最近任务", secondary = !recent) { recent = true }
                }
                state.error?.let { StatusText(it, palette.destructive) }
                val shown = state.rows.filter { recent || it.active }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.isEmpty()) StatusText(if (recent) "还没有可显示的任务。" else "当前没有进行中的任务。")
                    shown.forEach { row ->
                        Column(Modifier.fillMaxWidth().background(palette.secondary, RoundedCornerShape(8.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                BasicText(row.kind.label, style = TextStyle(color = palette.foreground))
                                BasicText(if (row.stopRequested) "正在停止，等待保存结束" else row.phase.label(), style = TextStyle(color = palette.mutedForeground))
                            }
                            row.contextLabel?.let { StatusText(it) }
                            StatusText("任务 ${row.id.substringAfterLast('/').take(12)}")
                            if (row.completed != null && row.total != null) StatusText("${row.completed} / ${row.total}")
                            if (row.kind == DesktopWorkKind.CARD_DESIGN && row.phase == DesktopWorkPhase.COMPLETED)
                                StatusText("候选已准备，请回到编辑器核对和应用。")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.target?.let { target -> BootstrapButton("前往页面", secondary = true) { onView(target) } }
                                if (row.active && row.canStop) BootstrapButton("停止", secondary = true, enabled = !row.stopRequested) { center.requestStop(row.id) }
                                if (!row.active && row.canRetry) BootstrapButton("重试", secondary = true) { center.retry(row.id) }
                            }
                        }
                    }
                }
                if (tray != null && trayState != null) {
                    StudioSwitch("系统任务提醒", trayState.notificationsEnabled,
                        enabled = trayState.notificationsConfigurable && settingsEnabled) { tray.setNotifications(!trayState.notificationsEnabled) }
                    trayState.error?.let { StatusText(it, palette.destructive) }
                    if (trayState.initialized && !trayState.available) StatusText("当前系统托盘不可用，窗口保持显示。")
                }
                onHideToTray?.let { BootstrapButton("隐藏到托盘", secondary = true,
                    enabled = trayState?.available == true) { onClose(); it() } }
            }
        }
    }
}
