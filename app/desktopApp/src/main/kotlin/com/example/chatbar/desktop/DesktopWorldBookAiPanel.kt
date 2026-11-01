@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.chatbar.data.local.entity.CharacterResearchSourceMode
import com.example.chatbar.domain.search.usesManualUrls
import com.example.chatbar.domain.worldbook.WorldBookAiOperation

@Composable
internal fun DesktopWorldBookAiPanel(controller: DesktopWorldBookAiController) {
    val state by controller.state.collectAsState()
    if (!state.open) return
    var logs by remember { mutableStateOf(false) }
    DesktopModalSurface {
        Column(Modifier.widthIn(max = 1080.dp).fillMaxWidth().heightIn(max = 900.dp)
            .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(16.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { DesktopManagementHeading(if (state.operation == WorldBookAiOperation.CREATE)
                    "AI 创建世界书条目" else "AI 填充空白正文") }
                DesktopIconAction("关闭世界书 AI", DesktopAppIcons.Close, enabled = !state.busy, onClick = controller::close)
            }
            StatusText(if (state.operation == WorldBookAiOperation.CREATE) "只生成名称与关键词骨架，每批最多五条、总计最多五十条。"
                else "只填写选中且正文为空的条目，保持条目 ID 与高级设置。")
            StatusText("先核对候选并勾选应用；应用只改变编辑草稿，正常保存仍需另行确认。")
            val enabled = !state.busy && !state.loading && !state.referenceLoading
            if (state.loading) StatusText("正在读取模型与资料设置…")
            val ids = listOf<String?>(null) + state.models.map { it.id } + listOfNotNull(state.modelId?.takeUnless { id -> state.models.any { it.id == id } })
            if (enabled) {
                SearchableChoice("生成模型", ids, state.modelId, { id -> if (id == null) "跟随默认对话模型 · ${state.defaultModel}"
                    else state.models.firstOrNull { it.id == id }?.name ?: "所选模型不可用" }, controller::selectModel)
                SearchableChoice("外部资料", CharacterResearchSourceMode.entries, state.sourceMode, { mode -> when (mode) {
                    CharacterResearchSourceMode.NONE -> "不使用外部网页"
                    CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH -> "百科搜索"
                    CharacterResearchSourceMode.MANUAL_URLS -> "仅指定网址"
                    CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH_AND_MANUAL_URLS -> "百科搜索与指定网址"
                } }, controller::selectSource)
            }
            if (state.sourceMode.usesManualUrls()) WorldBookAiField("指定网址（每行一个，最多五个，不保存网址文本）",
                state.manualUrls, enabled, controller::setManualUrls)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton("选择临时参考文档", secondary = true, enabled = enabled, onClick = controller::chooseReference)
                if (state.reference != null) BootstrapButton("移除参考文档", secondary = true, enabled = enabled, onClick = controller::clearReference)
            }
            if (state.referenceLoading) StatusText("正在读取临时参考文档…")
            state.reference?.let { StatusText("${it.fileName} · ${it.content.length} 字符；临时 RAG 检索，不写入世界书。") }
            WorldBookAiField("本次要求", state.request, enabled, controller::setRequest)
            if (state.operation == WorldBookAiOperation.FILL) {
                var targetsOpen by remember { mutableStateOf(true) }
                BootstrapButton("${if (targetsOpen) "收起" else "展开"}待填正文目标 · ${state.targetIds.size}/${controller.targets.size}", secondary = true) { targetsOpen = !targetsOpen }
                if (targetsOpen) Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    controller.targets.forEach { entry ->
                        StudioSwitch(entry.name.ifBlank { "未命名条目" }, entry.id in state.targetIds, enabled) {
                            controller.selectTarget(entry.id, entry.id !in state.targetIds)
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton("重新生成", enabled = enabled && (state.operation != WorldBookAiOperation.FILL || state.targetIds.isNotEmpty())) { controller.generate() }
                if (state.canResume) BootstrapButton("继续未完成批次", secondary = true, enabled = enabled) { controller.generate(resume = true) }
                if (state.busy) BootstrapButton("停止", secondary = true, onClick = controller::cancel)
                BootstrapButton("请求日志", secondary = true) { logs = true }
            }
            if (state.status.isNotBlank()) StatusText(state.status)
            state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
            if (state.warning.isNotBlank()) StatusText(state.warning, DesktopBootstrapColors.warning)
            if (state.candidateIds.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BootstrapButton("全选候选", secondary = true, enabled = !state.busy) { controller.selectAll(true) }
                    BootstrapButton("清空选择", secondary = true, enabled = !state.busy) { controller.selectAll(false) }
                    BootstrapButton("应用选中 ${state.selectedIds.size} 项", enabled = enabled && state.selectedIds.isNotEmpty()) { controller.apply() }
                }
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.plans.forEach { plan ->
                            Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(10.dp)) {
                                StudioSwitch(plan.name, plan.candidateId in state.selectedIds, !state.busy) { controller.selectCandidate(plan.candidateId, plan.candidateId !in state.selectedIds) }
                                StatusText("关键词：${plan.keys.joinToString("、")}")
                            }
                        }
                        state.contents.forEach { candidate ->
                            Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(10.dp)) {
                                StudioSwitch(candidate.name, candidate.targetId in state.selectedIds, !state.busy) { controller.selectCandidate(candidate.targetId, candidate.targetId !in state.selectedIds) }
                                StatusText(candidate.content)
                            }
                        }
                    }
                }
            }
            var outputsOpen by remember { mutableStateOf(false) }
            BootstrapButton("${if (outputsOpen) "收起" else "展开"}资料与生成输出", secondary = true) { outputsOpen = !outputsOpen }
            if (outputsOpen) SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.progress.forEach { StatusText(it) }
                    state.research?.briefFailureReason?.takeIf(String::isNotBlank)?.let { StatusText(it, DesktopBootstrapColors.warning) }
                    state.research?.sources.orEmpty().forEach { StatusText("${it.title}\n${it.excerpt}") }
                    state.research?.brief?.let { StatusText((it.facts + it.notes).joinToString("\n")) }
                    state.outputs.forEach { StatusText(it.title); StatusText(it.text) }
                    if (state.reasoning.isNotBlank()) { StatusText("思考过程"); StatusText(state.reasoning) }
                }
            }
        }
    }
    if (logs) controller.diagnosticSessionId?.let { DesktopChatDebugOverlay(controller.diagnosticOwner, it, onClose = { logs = false }) }
}

@Composable private fun WorldBookAiField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    StatusText(label)
    BasicTextField(value, change, Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 160.dp)
        .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).background(DesktopBootstrapColors.input)
        .padding(10.dp).verticalScroll(rememberScrollState()), enabled = enabled,
        cursorBrush = DesktopBootstrapColors.inputCaret, textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
}
