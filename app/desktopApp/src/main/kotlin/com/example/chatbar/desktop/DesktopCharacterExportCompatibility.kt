package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.card.CharacterCardExportCompatibility
import kotlinx.coroutines.launch

@Composable
internal fun DesktopCharacterExportDialogs(controller: DesktopTypedTransferController) {
    val state by controller.state.collectAsState()
    state.characterJsonExport?.let { DesktopCharacterJsonExportDialog(it, controller) }
    state.coverExport?.let { DesktopCoverExportDialog(it, controller) }
}

internal fun CharacterCardExportCompatibility.desktopLabel() = when (this) {
    CharacterCardExportCompatibility.OFFICIAL_V9 -> "旧版兼容格式9"
    CharacterCardExportCompatibility.COMPLETE_V11 -> "完整格式11（推荐）"
}

@Composable
internal fun DesktopCharacterExportCompatibilityChoice(selected: CharacterCardExportCompatibility,
    enabled: Boolean, onSelect: (CharacterCardExportCompatibility) -> Unit) {
    CompactChoice("文件格式", CharacterCardExportCompatibility.entries, selected, { it.desktopLabel() }) {
        if (enabled) onSelect(it)
    }
    if (selected == CharacterCardExportCompatibility.COMPLETE_V11)
        StatusText("保留独立封面、全部背景及默认背景和人物启用设置。用于支持完整格式11的版本；旧版 CCB 请选择兼容格式9。", DesktopBootstrapColors.warning)
    else StatusText("兼容旧版 CCB；不携带独立封面、背景图库及人物启用开关，仅保留默认背景。本地原件保持。")
}

@Composable
internal fun DesktopCharacterJsonExportDialog(draft: DesktopCharacterJsonExportDraft, controller: DesktopTypedTransferController) {
    val state by controller.state.collectAsState()
    var compatibility by remember(draft) { mutableStateOf(CharacterCardExportCompatibility.COMPLETE_V11) }
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
