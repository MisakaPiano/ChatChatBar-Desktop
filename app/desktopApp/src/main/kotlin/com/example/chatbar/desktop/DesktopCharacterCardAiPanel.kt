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
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.domain.card.CharacterCardAiOperation
import com.example.chatbar.data.local.entity.CharacterResearchSourceMode
import com.example.chatbar.domain.search.usesManualUrls

internal data class DesktopCharacterCardDiff(val label: String, val before: String, val after: String)

/** Compare the actual merged card, including additions/deletions, never just the AI's patch JSON. */
internal fun desktopCharacterCardAiDiff(before: CharacterCard, after: CharacterCard): List<DesktopCharacterCardDiff> = buildList {
    fun field(label: String, previous: String, next: String) { if (previous != next) add(DesktopCharacterCardDiff(label, previous, next)) }
    field("卡名", before.name, after.name); field("开场白", before.greeting, after.greeting)
    field("基础设定", before.basicSetting, after.basicSetting)
    field("自由人物设定", before.freeformCharacterText, after.freeformCharacterText)
    field("画风", before.defaultImagePrompt, after.defaultImagePrompt)
    field("负面词", before.defaultImageNegativePrompt, after.defaultImageNegativePrompt)
    fun values(p: CharacterInfo?) = if (p == null) emptyList() else listOf(p.name, p.profile, p.appearance,
        p.clothing, p.abilities, p.habits, p.background, p.relationships, p.speakingStyle, p.imagePrompt)
    val labels = listOf("姓名", "简介", "外貌", "服装", "能力", "习惯", "背景", "关系", "说话风格", "生图标签")
    (before.characters.map { it.id } + after.characters.map { it.id }).distinct().forEach { id ->
        val previous = before.characters.firstOrNull { it.id == id }; val next = after.characters.firstOrNull { it.id == id }
        val name = next?.name?.takeIf(String::isNotBlank) ?: previous?.name.orEmpty()
        if (previous == null) field("新增人物 · $name", "", name)
        if (next == null) field("删除人物 · $name", name, "")
        val a = values(previous); val b = values(next)
        labels.indices.forEach { index -> field("$name · ${labels[index]}", a.getOrElse(index) { "" }, b.getOrElse(index) { "" }) }
    }
}

@Composable
internal fun DesktopCharacterCardAiOverlay(controller: DesktopCharacterCardAiController) {
    val state by controller.state.collectAsState()
    if (!state.open) return
    var logs by remember(controller) { mutableStateOf(false) }
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 1120.dp).fillMaxHeight()
                .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopManagementHeading(
                        if (state.operation == CharacterCardAiOperation.FILL) "AI 补全空白" else "AI 定向改写") }
                    DesktopIconAction("关闭角色卡 AI", DesktopAppIcons.Close, enabled = !state.busy, onClick = controller::close)
                }
                StatusText("预览应用后的实际差异；应用只改变编辑草稿，保存仍需另行确认。")
                val ids = listOf<String?>(null) + state.models.map { it.id } +
                    listOfNotNull(state.modelId?.takeUnless { id -> state.models.any { it.id == id } })
                if (!state.busy) SearchableChoice("生成模型", ids, state.modelId, { id ->
                    if (id == null) "跟随默认对话模型 · ${state.defaultModel}"
                    else state.models.firstOrNull { it.id == id }?.name ?: "所选模型不可用"
                }, controller::selectModel)
                if (!state.busy && !state.referenceLoading && !state.loading)
                    SearchableChoice("外部资料", CharacterResearchSourceMode.entries, state.sourceMode, { mode ->
                        when (mode) {
                            CharacterResearchSourceMode.NONE -> "不使用外部网页"
                            CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH -> "百科搜索"
                            CharacterResearchSourceMode.MANUAL_URLS -> "仅指定网址"
                            CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH_AND_MANUAL_URLS -> "百科搜索与指定网址"
                        }
                    }, controller::selectSourceMode)
                if (state.sourceMode.usesManualUrls()) {
                    StatusText("指定网址（每行一个，最多五个；不保存网址文本）")
                    BasicTextField(state.manualUrls, controller::setManualUrls,
                        Modifier.fillMaxWidth().heightIn(min = 76.dp, max = 140.dp)
                            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                            .background(DesktopBootstrapColors.input).padding(10.dp).verticalScroll(rememberScrollState()),
                        enabled = !state.busy && !state.referenceLoading, cursorBrush = DesktopBootstrapColors.inputCaret,
                        textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BootstrapButton("选择临时参考文档", secondary = true, enabled = !state.busy && !state.referenceLoading,
                        onClick = controller::chooseReference)
                    if (state.reference != null) BootstrapButton("移除参考文档", secondary = true,
                        enabled = !state.busy && !state.referenceLoading, onClick = controller::clearReference)
                }
                state.reference?.let { document ->
                    StatusText("参考文档：${document.fileName} · ${document.content.length} 字符；通过临时 RAG 检索，不加入卡片文档库。")
                    if (document.content.length > 1_000_000) StatusText("文档较长，向量化可能需要较多时间与额度。", DesktopBootstrapColors.warning)
                }
                if (state.referenceLoading) StatusText("正在读取参考文档…")
                state.sourcePreferenceError?.let { StatusText(it, DesktopBootstrapColors.warning) }
                StatusText(if (state.operation == CharacterCardAiOperation.FILL) "角色信息与补全要求" else "改写要求")
                BasicTextField(state.request, controller::setRequest,
                    Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp)
                        .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                        .background(DesktopBootstrapColors.input).padding(10.dp).verticalScroll(rememberScrollState()),
                    enabled = !state.busy && !state.referenceLoading, cursorBrush = DesktopBootstrapColors.inputCaret,
                    textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
                if (state.loading) StatusText("正在读取模型…")
                if (state.status.isNotBlank()) StatusText(state.status)
                state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BootstrapButton("生成新候选", enabled = !state.busy && !state.loading && !state.referenceLoading) { controller.generate() }
                    if (state.busy) BootstrapButton("取消生成", secondary = true, onClick = controller::cancel)
                    if (!state.busy && state.candidate == null && (state.error != null || state.research?.hasContent() == true))
                        BootstrapButton("重试未完成步骤", secondary = true) { controller.generate(retry = true) }
                    if (!state.busy && state.research?.brief != null) BootstrapButton("重新生成最终结果", secondary = true,
                        onClick = controller::regenerateFinal)
                    BootstrapButton("应用到当前草稿", enabled = !state.busy && state.candidate != null) { controller.apply() }
                    BootstrapButton("刷新模型", secondary = true, enabled = !state.busy, onClick = controller::refreshModels)
                    BootstrapButton("请求日志", secondary = true) { logs = true }
                }
                state.candidate?.let { candidate -> state.before?.let { before ->
                    val diff = remember(before, candidate) { desktopCharacterCardAiDiff(before, candidate) }
                    DesktopManagementHeading("应用后差异 · ${diff.size} 项")
                    if (diff.isEmpty()) StatusText("没有需要更改的字段")
                    diff.forEach { change ->
                        Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                            .padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusText(change.label)
                            SelectionContainer { StatusText("原内容：\n${change.before.ifEmpty { "（空）" }}") }
                            SelectionContainer { StatusText("候选：\n${change.after.ifEmpty { "（清空）" }}") }
                        }
                    }
                } }
                DesignTextDisclosure("生成原文", state.raw, initiallyExpanded = state.candidate == null)
                DesignTextDisclosure("生成思考", state.reasoning)
                DesignTextDisclosure("修复原文", state.repair, initiallyExpanded = state.candidate == null)
                DesignTextDisclosure("修复思考", state.repairReasoning)
                state.sourceOutput.values.forEach { (title, text) -> DesignTextDisclosure(title, text) }
                state.research?.brief?.let { brief -> DesignTextDisclosure("研究摘要",
                    (brief.facts + brief.notes).joinToString("\n\n")) }
            }
        }
    }
    if (logs) controller.diagnosticSessionId?.let { session ->
        DesktopChatDebugOverlay(controller.diagnosticOwner, session, onClose = { logs = false })
    }
}
