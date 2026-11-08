package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.card.CharacterCardExportCompatibility
import kotlinx.coroutines.launch

internal fun CharacterCardExportCompatibility.desktopLabel() = when (this) {
    CharacterCardExportCompatibility.OFFICIAL_V9 -> "官方 CCB 兼容（推荐）"
    CharacterCardExportCompatibility.DESKTOP_COVER_V10 -> "Desktop 完整格式"
}

@Composable
internal fun DesktopCharacterExportCompatibilityChoice(selected: CharacterCardExportCompatibility,
    enabled: Boolean, onSelect: (CharacterCardExportCompatibility) -> Unit) {
    CompactChoice("文件格式", CharacterCardExportCompatibility.entries, selected, { it.desktopLabel() }) {
        if (enabled) onSelect(it)
    }
    if (selected == CharacterCardExportCompatibility.DESKTOP_COVER_V10)
        StatusText("包含独立封面原图。当前原版 CCB 不支持此格式；仅用于支持独立封面的 Desktop 版本。Android 双端传输尚未验收。", DesktopBootstrapColors.warning)
    else StatusText("用于当前原版 CCB 分享。本地独立封面原图继续保留，PNG 的原图外观也可保留。")
}

@Composable
internal fun DesktopCharacterJsonExportDialog(draft: DesktopCharacterJsonExportDraft, controller: DesktopTypedTransferController) {
    val state by controller.state.collectAsState()
    var compatibility by remember(draft) { mutableStateOf(CharacterCardExportCompatibility.OFFICIAL_V9) }
    val scope = rememberCoroutineScope()
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().background(DesktopBootstrapColors.card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopDataSectionHeading("导出角色 JSON · ${draft.card.name}") }
                    DesktopChatIconAction("关闭角色导出", DesktopAppIcons.Close, enabled = !state.busy,
                        onClick = controller::cancelCharacterJsonExport)
                }
                DesktopCharacterExportCompatibilityChoice(compatibility, !state.busy) { compatibility = it }
                state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                BootstrapButton("选择保存位置并导出", enabled = !state.busy) {
                    scope.launch { controller.finishCharacterJsonExport(draft, compatibility) }
                }
            }
        }
    }
}
