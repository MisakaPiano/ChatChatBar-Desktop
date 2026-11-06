package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.ChatNarrativeMode

@Composable
internal fun DesktopChatNarrativeModeDialog(mode: ChatNarrativeMode,
    onSelect: (ChatNarrativeMode) -> Unit, onClose: () -> Unit) {
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 640.dp)
                .background(DesktopBootstrapColors.card).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopDataSectionHeading("回复节奏") }
                    DesktopChatIconAction("关闭回复节奏说明", DesktopAppIcons.Close, onClick = onClose)
                }
                StatusText("切换用于下一次回复；正在生成的回复保持原来的节奏。")
                for (choice in ChatNarrativeMode.entries) {
                    BootstrapButton(choice.label, secondary = choice != mode) { onSelect(choice) }
                    StatusText(when (choice) {
                        ChatNarrativeMode.FOCUS -> "细写眼前的反应和动作，让时间慢下来，留出下一次互动的空间。"
                        ChatNarrativeMode.ADVANCE -> "概括行动的过程，把当前事件推进到结果；遇到需要你决定的新选择时停下。"
                    })
                }
                StatusText("模式按会话保存；切换不会改写已有回复，也不会改变已有录音的来源。")
            }
        }
    }
}
