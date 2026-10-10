package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** No job/state ownership in Compose: collapse and route disposal never cancel work. */
@Composable
internal fun DesktopChatImageProcessCard(
    task: DesktopTaskEntry, progress: DesktopImageProcessProgress?, retryEnabled: Boolean,
    onStop: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit,
) {
    var expanded by remember(task.taskId) { mutableStateOf(true) }
    val scroll = rememberScrollState()
    val phase = progress?.stage ?: "Prompt 设计"
    Row(Modifier.fillMaxWidth().testTag("image-process-row-${task.taskId}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f).testTag("image-process-card-${task.taskId}")
            .background(DesktopBootstrapColors.card, RoundedCornerShape(6.dp))
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(6.dp)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusText(phase) }
                BootstrapButton(if (expanded) "折叠生图过程" else "展开生图过程") { expanded = !expanded }
            }
            StatusText(when (task.status) {
                DesktopTaskStatus.RUNNING -> progress?.generationStatus?.takeIf { it.isNotBlank() } ?: task.message
                DesktopTaskStatus.COMPLETED -> "已完成 · ${task.message}"
                DesktopTaskStatus.FAILED -> "失败 · ${task.message}"
                DesktopTaskStatus.USER_STOPPED -> "用户已停止 · ${task.message}"
                DesktopTaskStatus.CANCELLED -> "已取消 · ${task.message}"
            })
            if (expanded && !progress?.designText.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("image-process-scroll-${task.taskId}")) {
                    Column(Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scroll)) {
                        if (progress!!.truncated) StatusText("过程内容已截断（仅保留有限文本）")
                        StatusText(progress!!.designText)
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            if (task.status != DesktopTaskStatus.RUNNING) StudioActions {
                if (task.canRetry) BootstrapButton("重试此图片任务", enabled = retryEnabled, onClick = onRetry)
                BootstrapButton("关闭图片任务", onClick = onDismiss)
            }
        }
        if (task.status == DesktopTaskStatus.RUNNING) {
            Box(Modifier.testTag("image-process-stop-${task.taskId}")) {
                DesktopChatIconAction("停止此图片任务", DesktopAppIcons.Stop, destructive = true, onClick = onStop)
            }
        }
    }
}
