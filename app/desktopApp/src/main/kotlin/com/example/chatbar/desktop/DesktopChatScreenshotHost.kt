@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessagePlacement
import com.example.chatbar.domain.chat.*
import kotlinx.coroutines.*
import java.nio.file.Path

internal data class DesktopScreenshotWorkspaceSnapshot(
    val state: DesktopPrimaryChatState, val colors: DesktopSemanticColors,
    val messages: List<ChatMessage>, val voices: Map<String, List<VoiceMessagePlacement>>,
    val initialSelected: Set<String>, val clickedMessageId: String,
)

/** Component tests replace only the native shell; production always uses an owned Dialog. */
@Composable
internal fun DesktopChatScreenshotHost(controller: DesktopPrimaryChatController, state: DesktopPrimaryChatState,
    workspaceWindow: @Composable (() -> Unit, @Composable () -> Unit) -> Unit = { close, body -> DesktopScreenshotWindow(close, body) },
    previewWindow: @Composable (() -> Unit, @Composable () -> Unit) -> Unit = { close, body -> DesktopScreenshotWindow(close, body, "PNG 截图预览") },
    content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var workspace by remember(state.selectedSession?.id) { mutableStateOf<DesktopScreenshotWorkspaceSnapshot?>(null) }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val colors = LocalDesktopPalette.current
    val start: (ChatMessage, String?) -> Unit = { clicked, block ->
        if (!opening && workspace == null) scope.launch {
            opening = true; error = null
            var stage = "OPEN_TIMELINE"
            try {
                val timeline = controller.screenshotTimeline()
                stage = "OPEN_IDENTITY"
                check(timeline.any { it == clicked }) { "消息已变化，请重新选择" }
                val voices = linkedMapOf<String, List<VoiceMessagePlacement>>()
                // Audio is never screenshot content; do not read or select recording identities.
                stage = "OPEN_VERIFY"
                controller.verifyScreenshotMessages(timeline)
                stage = "OPEN_SELECTION"
                val ids = desktopScreenshotBlockIds(clicked, state.assistantSegmentedBubblesEnabled)
                check(block == null || block in ids)
                workspace = DesktopScreenshotWorkspaceSnapshot(state, colors, timeline, voices,
                    if (block == null) ids.toSet() else setOf(block), clicked.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "无法读取截图内容（$stage），请检查消息和资源后重新选择" }
            finally { opening = false }
        }
    }
    CompositionLocalProvider(LocalDesktopChatScreenshot provides start, content = content)
    workspace?.let { snapshot ->
        val close = { workspace = null; error = null }
        workspaceWindow(close) {
            CompositionLocalProvider(LocalDesktopPalette provides snapshot.colors) {
                DesktopScreenshotWorkspace(controller, snapshot, close, previewWindow)
            }
        }
    }
    if (workspace == null) error?.let { text ->
        Row { StatusText(text); StudioAction("关闭提示") { error = null } }
    }
}

@Composable
internal fun DesktopScreenshotWorkspace(controller: DesktopPrimaryChatController, snapshot: DesktopScreenshotWorkspaceSnapshot, onClose: () -> Unit,
    previewWindow: @Composable (() -> Unit, @Composable () -> Unit) -> Unit = { close, body -> DesktopScreenshotWindow(close, body, "PNG 截图预览") }) {
    val scope = rememberCoroutineScope()
    val state = snapshot.state
    val all = remember(snapshot) { snapshot.messages.flatMap { m -> desktopScreenshotBlockIds(m, state.assistantSegmentedBubblesEnabled) }.toSet() }
    var tree by remember(snapshot) { mutableStateOf(DesktopScreenshotTreeState(snapshot.initialSelected, setOf(snapshot.clickedMessageId))) }
    var contentOptions by remember(snapshot) { mutableStateOf(DesktopScreenshotContentOptions()) }
    var pages by remember(snapshot) { mutableStateOf<List<DesktopScreenshotPng>?>(null) }
    var pageIndex by remember(snapshot) { mutableStateOf(0) }
    val png = pages?.getOrNull(pageIndex)
    val viewport = remember(png) { png?.let { DesktopScreenshotViewport(it.width, it.height) } }
    var capturedMessages by remember(snapshot) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var capturedDocument by remember(snapshot) { mutableStateOf<List<DesktopScreenshotDocumentBlock>>(emptyList()) }
    var exportFormat by remember(snapshot) { mutableStateOf(DesktopScreenshotExportFormat.PNG) }
    var savedPath by remember(snapshot) { mutableStateOf<Path?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Preserve the application's native picker service and its verified main HWND owner.
    val picker = controller.imagePicker
    val platformClipboard = LocalClipboard.current
    val clipboard = remember(platformClipboard) { DesktopSafeClipboard(platformClipboard) }
    val read: (String) -> ByteArray = { controller.characterResources.readScreenshotImageBytes(it) }
    fun selection(next: DesktopScreenshotTreeState) { tree = next; pages = null; pageIndex = 0; previewOpen = false; feedback = null; savedPath = null }
    fun preview() {
        if (busy) return
        busy = true
        scope.launch {
            busy = true; feedback = null; failed = false; savedPath = null
            var stage = "PREVIEW_SELECTION"
            try {
                val chosen = orderedChatScreenshotMessages(snapshot.messages, tree.selected, state.assistantSegmentedBubblesEnabled,
                    snapshot.voices.mapValues { (_, placements) -> placements.map { it.voice.id } })
                stage = "PREVIEW_VERIFY"
                controller.verifyScreenshotMessages(chosen)
                stage = "PREVIEW_RESOURCES"
                val preferred = state.selectedCharacter?.id?.let { controller.backgroundLibrary?.preferred(it) }
                val background = desktopEffectiveBackground(state.selectedSession?.chatBackground, preferred, state.selectedCharacter?.chatBackground).first
                stage = "PREVIEW_RENDER"
                val request = DesktopScreenshotRequest(state.selectedSession?.title ?: "聊天", state,
                    chosen, tree.selected, snapshot.voices, background = background, contentOptions = contentOptions)
                val result = DesktopChatScreenshotRenderer.renderPages(request, snapshot.colors, read,
                    onPage = { current, total -> feedback = if (total > 1) "正在生成预览分块 $current/$total…" else null })
                stage = "PREVIEW_DOCUMENT"
                val document = desktopScreenshotDocument(request, snapshot.colors)
                stage = "PREVIEW_VERIFY"
                controller.verifyScreenshotMessages(chosen)
                withContext(Dispatchers.IO) { DesktopChatScreenshotRenderer.verifyResources(result.first(), read) }
                capturedMessages = chosen; capturedDocument = document; pages = result; pageIndex = 0; previewOpen = true; feedback = null
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { failed = true; feedback = "预览未完成（$stage）；${desktopScreenshotFailureDetails(error)}" }
            finally { busy = false }
        }
    }
    fun save() {
        if (busy) return
        busy = true
        val result = pages
        val format = exportFormat
        val document = capturedDocument
        scope.launch {
            busy = true; failed = false; feedback = null; savedPath = null
            var stage = "EXPORT_VERIFY_WRITE"
            try {
                val choose: suspend () -> Path? = {
                    withContext(Dispatchers.IO) { picker.pickSaveFile(DesktopFileType("${format.label} 文件", listOf(format.extension)), format.fileName(state.selectedSession?.title ?: "聊天")) }
                }
                val target = if (format == DesktopScreenshotExportFormat.WORD) {
                    stage = "EXPORT_WORD_SELECTION"
                    val chosen = orderedChatScreenshotMessages(snapshot.messages, tree.selected, state.assistantSegmentedBubblesEnabled)
                    require(chosen.isNotEmpty())
                    controller.verifyScreenshotMessages(chosen)
                    stage = "EXPORT_WORD_DOCUMENT"
                    val preferred = state.selectedCharacter?.id?.let { controller.backgroundLibrary?.preferred(it) }
                    val background = desktopEffectiveBackground(state.selectedSession?.chatBackground, preferred, state.selectedCharacter?.chatBackground).first
                    val request = DesktopScreenshotRequest(state.selectedSession?.title ?: "聊天", state,
                        chosen, tree.selected, snapshot.voices, background = background, contentOptions = contentOptions)
                    val blocks = withContext(Dispatchers.Default) { desktopScreenshotDocument(request, snapshot.colors) }
                    stage = "EXPORT_WORD_VERIFY_WRITE"
                    saveDesktopWordDocumentVerified(blocks, { controller.verifyScreenshotMessages(chosen) }, read, choose)
                } else saveDesktopScreenshotPagesVerified(format, checkNotNull(result), document,
                    { controller.verifyScreenshotMessages(capturedMessages) }, read, choose)
                savedPath = target
                feedback = if (target == null) "已取消保存，预览已保留，可再次导出" else "${format.label} 已保存：$target"
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { failed = true; feedback = "无法保存 ${format.label}（$stage）；${desktopScreenshotFailureDetails(error)}" }
            finally { busy = false }
        }
    }
    fun copyPath() {
        val path = savedPath ?: return
        scope.launch {
            try {
                val copied = clipboard.copyText(path.toString()) == DesktopClipboardResult.COPIED
                if (savedPath == path) {
                    failed = !copied
                    feedback = if (copied) "已复制保存路径：$path" else "文件已保存，但剪贴板暂不可用，请重试复制路径：$path"
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (savedPath == path) { failed = true; feedback = "文件已保存，但无法复制路径：$path" } }
        }
    }
    LaunchedEffect(previewOpen) { if (!previewOpen) focus.requestFocus() }
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp)
        .onPreviewKeyEvent { e -> if (e.key == Key.Escape && e.type == KeyEventType.KeyDown) { onClose(); true } else false }
        .focusRequester(focus).focusable(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText("聊天截图 · 选择内容与导出")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StudioAction("全部展开", enabled = !busy) { tree = tree.copy(expanded = snapshot.messages.map { it.id }.toSet()) }
            StudioAction("全部折叠", enabled = !busy) { tree = tree.copy(expanded = emptySet()) }
            StudioAction("全选", enabled = !busy) { selection(tree.selectAll(all)) }
            StudioAction("全不选", enabled = !busy) { selection(tree.clear()) }
            StudioAction("反选", enabled = !busy) { selection(tree.invert(all)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusText("已选 ${tree.selected.size} 项")
            Spacer(Modifier.weight(1f))
            if (!busy) CompactChoice("格式", DesktopScreenshotExportFormat.entries, exportFormat, { it.label }) { exportFormat = it }
            StudioAction(if (busy) "正在处理…" else "预览 PNG", enabled = !busy && tree.selected.isNotEmpty(), onClick = ::preview)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StudioSwitch("包含状态栏", contentOptions.includeStatus, enabled = !busy) {
                contentOptions = contentOptions.copy(includeStatus = !contentOptions.includeStatus); selection(tree)
            }
            StudioSwitch("包含行动选项", contentOptions.includeActions, enabled = !busy) {
                contentOptions = contentOptions.copy(includeActions = !contentOptions.includeActions); selection(tree)
            }
            StudioSwitch("包含背景图", contentOptions.includeBackground, enabled = !busy) {
                contentOptions = contentOptions.copy(includeBackground = !contentOptions.includeBackground); selection(tree)
            }
        }
        StatusText("所选状态栏和行动选项完整展开导出；与聊天中的折叠状态无关。")
        if (exportFormat == DesktopScreenshotExportFormat.PDF)
            StatusText("PDF 保留截图外观，当前为图片式页面，无法选择或搜索原文；不嵌入音频。")
        if (exportFormat == DesktopScreenshotExportFormat.WORD)
            StatusText("Word 包含可编辑文字和嵌入图片，阅读器会重新排版，与 PNG 预览外观不同；不嵌入音频。")
        DesktopScreenshotSelectionTree(snapshot, tree, !busy, Modifier.weight(1f).fillMaxWidth(),
            { selection(it) }, { tree = it })
        feedback?.let { text ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(text, Modifier.weight(1f).heightIn(max = 60.dp).verticalScroll(rememberScrollState()), style = TextStyle(color = if (failed) DesktopBootstrapColors.destructive else DesktopBootstrapColors.foreground))
                if (savedPath != null) StudioAction("复制路径", enabled = !busy, onClick = ::copyPath)
                StudioAction("关闭提示") { feedback = null }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioAction("返回聊天", onClick = onClose)
            StudioAction("导出 ${exportFormat.label}", enabled = !busy && tree.selected.isNotEmpty() &&
                (exportFormat == DesktopScreenshotExportFormat.WORD || png != null), onClick = ::save)
            StudioAction("关闭", onClick = onClose)
        }
    }
    if (previewOpen && png != null) previewWindow({ previewOpen = false }) {
        val previewFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { previewFocus.requestFocus() }
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp)
            .onPreviewKeyEvent { event -> if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { previewOpen = false; true } else false }
            .focusRequester(previewFocus).focusable(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusText("PNG 截图预览")
                Spacer(Modifier.weight(1f))
                if (!busy) CompactChoice("格式", DesktopScreenshotExportFormat.entries, exportFormat, { it.label }) { exportFormat = it }
            }
            if (checkNotNull(pages).size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                StudioAction("上一页", enabled = !busy && pageIndex > 0) { pageIndex-- }
                StatusText("预览 ${pageIndex + 1}/${checkNotNull(pages).size} · 导出包含全部选择")
                StudioAction("下一页", enabled = !busy && pageIndex < checkNotNull(pages).lastIndex) { pageIndex++ }
            }
            DesktopScreenshotPreview(checkNotNull(png), checkNotNull(viewport), Modifier.weight(1f).fillMaxWidth())
            feedback?.let { text -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(text, Modifier.weight(1f).heightIn(max = 60.dp).verticalScroll(rememberScrollState()),
                    style = TextStyle(color = if (failed) DesktopBootstrapColors.destructive else DesktopBootstrapColors.foreground))
                if (savedPath != null) StudioAction("复制路径", enabled = !busy, onClick = ::copyPath)
            } }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioAction("返回选择") { previewOpen = false }
                StudioAction("保存 ${exportFormat.label}", enabled = !busy, onClick = ::save)
                StudioAction("关闭预览") { previewOpen = false }
            }
        }
    }

}

@Composable
private fun DesktopScreenshotSelectionTree(snapshot: DesktopScreenshotWorkspaceSnapshot, tree: DesktopScreenshotTreeState,
    enabled: Boolean, modifier: Modifier,
    onSelection: (DesktopScreenshotTreeState) -> Unit, onExpansion: (DesktopScreenshotTreeState) -> Unit) {
    val groups = remember(snapshot) { desktopScreenshotGroups(snapshot.messages) }
    val replyNumbers = remember(snapshot) { snapshot.messages.filter { it.role == MessageRole.ASSISTANT }.mapIndexed { i, m -> m.id to i + 1 }.toMap() }
    val all = remember(snapshot) { snapshot.messages.flatMap { desktopScreenshotBlockIds(it, snapshot.state.assistantSegmentedBubblesEnabled) }.toSet() }
    val scroll = rememberLazyListState()
    Box(modifier.border(1.dp, DesktopBootstrapColors.border)) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 14.dp, start = 8.dp), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEachIndexed { groupIndex, group ->
                item(key = "context-$groupIndex") {
                    BasicText(group.user?.let { "User 发言 · ${it.displayContent.take(160).ifBlank { "[${it.images.size} 张图片]" }}" } ?: "无前置 User 的历史消息",
                        Modifier.fillMaxWidth().background(DesktopBootstrapColors.muted).padding(8.dp), style = TextStyle(color = DesktopBootstrapColors.foreground))
                }
                items(group.messages, key = { it.id }) { m ->
                    val ids = desktopScreenshotBlockIds(m, snapshot.state.assistantSegmentedBubblesEnabled)
                    val segments = remember(m) { parseRoleplayTextSegments(m.displayContent) }
                    if (ids.isNotEmpty()) Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(6.dp)) {
                        val title = when (m.role) { MessageRole.ASSISTANT -> "完整回复 ${replyNumbers[m.id]}"; MessageRole.USER -> "User 发言"; else -> "系统消息" }
                        val count = ids.count { it in tree.selected }
                        Row(Modifier.fillMaxWidth().background(DesktopBootstrapColors.card), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).semantics { contentDescription = "${if (m.id in tree.expanded) "折叠" else "展开"} · $title" }
                                .clickable(enabled = enabled) { onExpansion(tree.toggleExpanded(m.id)) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                BasicText(if (m.id in tree.expanded) "▾" else "▸", Modifier.width(18.dp), style = TextStyle(color = DesktopBootstrapColors.mutedForeground))
                                BasicText(title, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 13.sp))
                            }
                            val checkState = if (count == ids.size) ToggleableState.On else if (count == 0) ToggleableState.Off else ToggleableState.Indeterminate
                            Box(Modifier.semantics { contentDescription = "整条选择 · $title" }
                                .triStateToggleable(checkState, enabled = enabled, role = Role.Checkbox) {
                                    onSelection(tree.copy(selected = toggleChatScreenshotMessageSelection(tree.selected, ids, all)))
                                }.padding(8.dp)) {
                                BasicText(if (count == ids.size) "☑" else if (count == 0) "☐" else "▣", style = TextStyle(color = DesktopBootstrapColors.foreground))
                            }
                        }
                        BasicText("已选 $count/${ids.size} 项", Modifier.padding(8.dp), style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 12.sp))
                        if (m.id in tree.expanded) ids.forEach { id ->
                            val segment = segments.getOrNull(id.substringAfterLast("::").toIntOrNull() ?: -1)
                            val label = when {
                                "::image::" in id -> "图片 ${id.substringAfterLast("::")}"
                                id.endsWith("::legacy") -> m.displayContent.take(100)
                                else -> segment?.displayText?.take(100).orEmpty()
                            }
                            StudioAction("${if (id in tree.selected) "☑" else "☐"} $label", enabled = enabled) {
                                onSelection(tree.copy(selected = toggleChatScreenshotSelection(tree.selected, id, all)))
                            }
                        }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
