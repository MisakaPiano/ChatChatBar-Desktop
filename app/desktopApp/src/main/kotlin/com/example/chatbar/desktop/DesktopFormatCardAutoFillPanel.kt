@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DesktopFormatCardAutoFillOverlay(controller: DesktopFormatCardAutoFillController) {
    val state by controller.state.collectAsState()
    if (!state.open) return
    var fullRequest by remember(controller) { mutableStateOf(false) }
    var showLogs by remember(controller) { mutableStateOf(false) }
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 1060.dp).fillMaxHeight().background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp))
                .padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        DesktopManagementHeading("AI 设计格式卡")
                        StatusText("从一张已保存角色卡设计新格式卡，预览后应用；应用后仍需保存。")
                    }
                    DesktopIconAction("关闭格式卡设计", DesktopAppIcons.Close, enabled = !state.busy, onClick = controller::close)
                }
                if (state.busy || state.loadingOptions) {
                    StatusText(if (state.busy) state.status else "正在读取角色卡与模型…")
                } else {
                    val characters = state.characters.map { it.id } + listOfNotNull(state.characterId?.takeUnless { id -> state.characters.any { it.id == id } })
                    val modelIds = listOf<String?>(null) + state.models.map { it.id } + listOfNotNull(state.modelId?.takeUnless { id -> state.models.any { it.id == id } })
                    SearchableChoice("参考角色卡", characters, state.characterId,
                        { id -> state.characters.firstOrNull { it.id == id }?.name ?: "角色卡不可用" }, controller::selectCharacter)
                    SearchableChoice("设计模型", modelIds, state.modelId,
                        { id -> if (id == null) "跟随默认对话模型${state.defaultModelName.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}"
                            else state.models.firstOrNull { it.id == id }?.name ?: "所选模型不可用" }, controller::selectModel)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BootstrapButton("刷新列表", secondary = true, onClick = controller::refreshOptions)
                        BootstrapButton("展开要求编辑", secondary = true) { fullRequest = true }
                    }
                }
                DesignRequestField(state.request, enabled = !state.busy, change = controller::setRequest)
                state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                if (!state.busy && state.status.isNotBlank()) StatusText(state.status)
                state.validationIssue?.let { StatusText("候选校验：$it", DesktopBootstrapColors.warning) }
                if (state.sourceCharacterName.isNotBlank()) StatusText("参考角色卡：${state.sourceCharacterName}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BootstrapButton("生成候选", enabled = !state.busy && !state.loadingOptions && state.characterId != null, onClick = controller::generate)
                    if (state.busy) BootstrapButton("取消生成", secondary = true, onClick = controller::cancel)
                    BootstrapButton("应用到新卡", enabled = !state.busy && state.candidate != null) { controller.apply() }
                    BootstrapButton("请求日志", secondary = true) { showLogs = true }
                }
                state.candidate?.let { draft ->
                    DesktopManagementHeading("候选预览 · ${draft.name}")
                    SelectionContainer { StatusText(draft.content) }
                    StatusText("用户工具 · ${draft.userTools.size}")
                    draft.userTools.forEachIndexed { index, tool ->
                        SelectionContainer { StatusText("${index + 1}. ${tool.type}\n" +
                            if (tool.type == com.example.chatbar.data.local.entity.FormatCardUserToolType.RANDOM_NUMBER)
                                "${tool.minimum} ～ ${tool.maximum}" else tool.text) }
                    }
                }
                DesignTextDisclosure("生成原文", state.rawText, initiallyExpanded = state.candidate == null)
                DesignTextDisclosure("生成思考", state.reasoning)
                DesignTextDisclosure("修复原文", state.repairText, initiallyExpanded = state.candidate == null)
                DesignTextDisclosure("修复思考", state.repairReasoning)
            }
        }
    }
    if (fullRequest && !state.busy) DesktopModalSurface {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { DesktopManagementHeading("定制要求") }
                DesktopIconAction("返回候选预览", DesktopAppIcons.Close) { fullRequest = false }
            }
            DesignRequestField(state.request, Modifier.weight(1f), expanded = true, change = controller::setRequest)
        }
    }
    if (showLogs) controller.diagnosticSessionId?.let { session ->
        DesktopChatDebugOverlay(controller.diagnosticOwner, session, onClose = { showLogs = false })
    }
}

@Composable
private fun DesignRequestField(value: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    expanded: Boolean = false, change: (String) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText("定制要求（可选）")
        val fieldModifier = if (expanded) Modifier.weight(1f).fillMaxWidth()
            else Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 220.dp)
        BasicTextField(value, change, fieldModifier
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
            .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp)).padding(10.dp)
            .verticalScroll(rememberScrollState()),
            enabled = enabled, cursorBrush = DesktopBootstrapColors.inputCaret,
            textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
    }
}

@Composable
internal fun DesignTextDisclosure(label: String, text: String, initiallyExpanded: Boolean = false) {
    if (text.isEmpty()) return
    var expanded by remember(label) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp)) {
            StatusText("${if (expanded) "▾" else "▸"} $label")
        }
        if (expanded) SelectionContainer { StatusText(text) }
    }
}
