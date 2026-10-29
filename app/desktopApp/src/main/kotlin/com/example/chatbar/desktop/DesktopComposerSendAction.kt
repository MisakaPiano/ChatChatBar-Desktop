package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*

internal fun desktopComposerPlaceholder(mode: DesktopSendKey) = if (mode == DesktopSendKey.ENTER)
    "输入消息…… Enter 发送；Shift + Enter 换行" else "输入消息…… Ctrl + Enter 发送；Enter 换行"

@Composable
internal fun DesktopComposerSendAction(running: DesktopTaskEntry?, canSend: Boolean, compact: Boolean,
    mode: DesktopSendKey, onMode: (DesktopSendKey) -> Unit, onClick: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.testTag(if (compact) "composer-send-compact" else "composer-send-full")
            .then(if (!compact) Modifier.background(DesktopBootstrapColors.primary, RoundedCornerShape(8.dp)) else Modifier),
            verticalAlignment = Alignment.CenterVertically) {
            if (compact || running != null) DesktopChatIconAction(t(if (running == null) DesktopUiText.SEND else DesktopUiText.STOP),
                if (running == null) DesktopAppIcons.Send else DesktopAppIcons.Stop, enabled = running != null || canSend, targetDp = 36, onClick = onClick)
            else BootstrapButton(t(DesktopUiText.SEND), enabled = canSend, onClick = onClick)
            DesktopChatIconAction("发送按键设置", DesktopAppIcons.DetailsOpen, targetDp = if (compact) 36 else 28) { open = !open }
        }
        if (open) Popup(popupPositionProvider = StudioPopupPosition, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(240.dp).background(DesktopBootstrapColors.card, RoundedCornerShape(8.dp))
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(6.dp)) {
                listOf(DesktopSendKey.ENTER to "按 Enter 键发送消息", DesktopSendKey.CTRL_ENTER to "按 Ctrl + Enter 键发送消息").forEach { (key, label) ->
                    StudioAction((if (mode == key) "✓ " else "") + label, selected = key == mode) { onMode(key); open = false }
                }
            }
        }
    }
}
