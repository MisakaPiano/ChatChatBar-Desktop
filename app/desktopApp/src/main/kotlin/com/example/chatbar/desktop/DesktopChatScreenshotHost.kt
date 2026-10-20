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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessagePlacement
import com.example.chatbar.domain.chat.*
import kotlinx.coroutines.*

internal data class DesktopScreenshotWorkspaceSnapshot(
    val state: DesktopPrimaryChatState, val colors: DesktopSemanticColors,
    val messages: List<ChatMessage>, val voices: Map<String, List<VoiceMessagePlacement>>,
    val initialSelected: Set<String>, val clickedMessageId: String,
)

/** Component tests replace only the native shell; production always uses an owned Dialog. */
@Composable
internal fun DesktopChatScreenshotHost(controller: DesktopPrimaryChatController, state: DesktopPrimaryChatState,
    workspaceWindow: @Composable (() -> Unit, @Composable () -> Unit) -> Unit = { close, body -> DesktopScreenshotWindow(close, body) },
    content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var workspace by remember(state.selectedSession?.id) { mutableStateOf<DesktopScreenshotWorkspaceSnapshot?>(null) }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val colors = LocalDesktopPalette.current
    val start: (ChatMessage, String?) -> Unit = { clicked, block ->
        if (!opening && workspace == null) scope.launch {
            opening = true; error = null
            try {
                val timeline = controller.screenshotTimeline()
                check(timeline.any { it == clicked }) { "消息已变化，请重新选择" }
                val voices = linkedMapOf<String, List<VoiceMessagePlacement>>()
                for (m in timeline) voices[m.id] = controller.voices?.repository?.placementsForMessage(m).orEmpty()
                controller.verifyScreenshotMessages(timeline, voices)
                val ids = roleplayScreenshotBlockIds(clicked, state.assistantSegmentedBubblesEnabled, voices[clicked.id].orEmpty().map { it.voice.id })
                check(block == null || block in ids)
                workspace = DesktopScreenshotWorkspaceSnapshot(state, colors, timeline, voices,
                    if (block == null) ids.toSet() else setOf(block), clicked.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "无法读取截图内容，请检查消息和资源后重新选择" }
            finally { opening = false }
        }
    }
    CompositionLocalProvider(LocalDesktopChatScreenshot provides start, content = content)
    workspace?.let { snapshot ->
        val close = { workspace = null; error = null }
        workspaceWindow(close) {
            CompositionLocalProvider(LocalDesktopPalette provides snapshot.colors) {
                DesktopScreenshotWorkspace(controller, snapshot, close)
            }
        }
    }
    if (workspace == null) error?.let { text ->
        Row { StatusText(text); StudioAction("关闭提示") { error = null } }
    }
}

@Composable
internal fun DesktopScreenshotWorkspace(controller: DesktopPrimaryChatController, snapshot: DesktopScreenshotWorkspaceSnapshot, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = snapshot.state
    val all = remember(snapshot) { snapshot.messages.flatMap { m -> roleplayScreenshotBlockIds(m, state.assistantSegmentedBubblesEnabled, snapshot.voices[m.id].orEmpty().map { it.voice.id }) }.toSet() }
    var tree by remember(snapshot) { mutableStateOf(DesktopScreenshotTreeState(snapshot.initialSelected, setOf(snapshot.clickedMessageId))) }
    var statusExpanded by remember(snapshot) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var png by remember(snapshot) { mutableStateOf<DesktopScreenshotPng?>(null) }
    val viewport = remember(png) { png?.let { DesktopScreenshotViewport(it.width, it.height) } }
    var capturedMessages by remember(snapshot) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var previewTab by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Preserve the application's native picker service and its verified main HWND owner.
    val picker = controller.imagePicker
    val read: (String) -> ByteArray = { controller.characterResources.readScreenshotImageBytes(it) }
    fun selection(next: DesktopScreenshotTreeState) { tree = next; png = null; feedback = null }
    fun preview() {
        scope.launch {
            busy = true; feedback = null; failed = false
            try {
                val chosen = orderedChatScreenshotMessages(snapshot.messages, tree.selected, state.assistantSegmentedBubblesEnabled)
                controller.verifyScreenshotMessages(chosen, snapshot.voices)
                val preferred = state.selectedCharacter?.id?.let { controller.backgroundLibrary?.preferred(it) }
                val background = desktopEffectiveBackground(state.selectedSession?.chatBackground, preferred, state.selectedCharacter?.chatBackground).first
                val result = DesktopChatScreenshotRenderer.render(DesktopScreenshotRequest(state.selectedSession?.title ?: "聊天", state,
                    chosen, tree.selected, snapshot.voices, statusExpanded, background), snapshot.colors, read)
                controller.verifyScreenshotMessages(chosen, snapshot.voices)
                withContext(Dispatchers.IO) { DesktopChatScreenshotRenderer.verifyResources(result, read) }
                capturedMessages = chosen; png = result; previewTab = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; feedback = "预览失败或内容已变化；请减少选择、检查资源后重试，原数据未修改" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp)
        .onPreviewKeyEvent { e -> if (e.key == Key.Escape && e.type == KeyEventType.KeyDown) { onClose(); true } else false }
        .focusRequester(focus).focusable(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText("聊天截图 · 选择内容与 PNG 预览")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StudioAction("全部展开", enabled = !busy) { tree = tree.copy(expanded = snapshot.messages.map { it.id }.toSet()) }
            StudioAction("全部折叠", enabled = !busy) { tree = tree.copy(expanded = emptySet()) }
            StudioAction("全选", enabled = !busy) { selection(tree.selectAll(all)) }
            StudioAction("全不选", enabled = !busy) { selection(tree.clear()) }
            StudioAction("反选", enabled = !busy) { selection(tree.invert(all)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            StatusText("已选 ${tree.selected.size} 项 · 录音 ${tree.selected.count { "::voice::" in it }} 项")
            StudioAction(if (busy) "正在处理…" else "预览 PNG", enabled = !busy && tree.selected.isNotEmpty(), onClick = ::preview)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = maxWidth < 760.dp
            val treeWidth = (maxWidth * .36f).coerceIn(280.dp, 440.dp)
            val treeContent: @Composable (Modifier) -> Unit = { modifier ->
                DesktopScreenshotSelectionTree(snapshot, tree, statusExpanded, !busy, modifier,
                    { selection(it) }, { tree = it }, { statusExpanded = it; png = null; feedback = null })
            }
            val previewContent: @Composable (Modifier) -> Unit = { modifier ->
                val result = png
                if (result != null) DesktopScreenshotPreview(result, checkNotNull(viewport), modifier)
                else Box(modifier.background(DesktopBootstrapColors.muted).padding(12.dp), contentAlignment = Alignment.Center) {
                    StatusText("选择内容后点击“预览 PNG”。缩放仅影响预览，不改变导出像素。")
                }
            }
            if (compact) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    StudioAction("选择内容", selected = !previewTab) { previewTab = false }
                    StudioAction("长图预览", selected = previewTab) { previewTab = true }
                }
                if (previewTab) previewContent(Modifier.weight(1f).fillMaxWidth()) else treeContent(Modifier.weight(1f).fillMaxWidth())
            } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                treeContent(Modifier.width(treeWidth).fillMaxHeight())
                previewContent(Modifier.weight(1f).fillMaxHeight())
            }
        }
        feedback?.let { text ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(text, Modifier.weight(1f).heightIn(max = 60.dp).verticalScroll(rememberScrollState()), style = TextStyle(color = if (failed) DesktopBootstrapColors.destructive else DesktopBootstrapColors.foreground))
                StudioAction("关闭提示") { feedback = null }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioAction("返回聊天", onClick = onClose)
            StudioAction("导出 PNG", enabled = !busy && png != null) {
                val result = checkNotNull(png)
                scope.launch {
                    busy = true; failed = false; feedback = null
                    try {
                        val target = saveDesktopScreenshotVerified(result, { controller.verifyScreenshotMessages(capturedMessages, snapshot.voices) }, read) {
                            withContext(Dispatchers.IO) { picker.pickSaveFile(DesktopFileType("PNG 图片", listOf("png")), buildChatScreenshotFileName(state.selectedSession?.title ?: "聊天")) }
                        }
                        feedback = if (target == null) "已取消保存，预览已保留，可再次导出" else "PNG 已保存：$target"
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed = true; feedback = "无法保存 PNG；请使用新文件名并检查目录、消息版本及资源，预览已保留，可重试" }
                    finally { busy = false }
                }
            }
            StudioAction("关闭", onClick = onClose)
        }
    }
}

@Composable
private fun DesktopScreenshotSelectionTree(snapshot: DesktopScreenshotWorkspaceSnapshot, tree: DesktopScreenshotTreeState,
    statusExpanded: Map<String, Boolean>, enabled: Boolean, modifier: Modifier,
    onSelection: (DesktopScreenshotTreeState) -> Unit, onExpansion: (DesktopScreenshotTreeState) -> Unit,
    onStatus: (Map<String, Boolean>) -> Unit) {
    val groups = remember(snapshot) { desktopScreenshotGroups(snapshot.messages) }
    val replyNumbers = remember(snapshot) { snapshot.messages.filter { it.role == MessageRole.ASSISTANT }.mapIndexed { i, m -> m.id to i + 1 }.toMap() }
    val all = remember(snapshot) { snapshot.messages.flatMap { roleplayScreenshotBlockIds(it, snapshot.state.assistantSegmentedBubblesEnabled, snapshot.voices[it.id].orEmpty().map { p -> p.voice.id }) }.toSet() }
    val scroll = rememberLazyListState()
    Box(modifier.border(1.dp, DesktopBootstrapColors.border)) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 14.dp, start = 8.dp), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEachIndexed { groupIndex, group ->
                item(key = "context-$groupIndex") {
                    BasicText(group.user?.let { "User 发言 · ${it.displayContent.take(160).ifBlank { "[${it.images.size} 张图片]" }}" } ?: "无前置 User 的历史消息",
                        Modifier.fillMaxWidth().background(DesktopBootstrapColors.muted).padding(8.dp), style = TextStyle(color = DesktopBootstrapColors.foreground))
                }
                items(group.messages, key = { it.id }) { m ->
                    val ids = roleplayScreenshotBlockIds(m, snapshot.state.assistantSegmentedBubblesEnabled, snapshot.voices[m.id].orEmpty().map { it.voice.id })
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
                        BasicText("已选 $count/${ids.size} · 录音 ${ids.count { "::voice::" in it }} 项", Modifier.padding(8.dp), style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 12.sp))
                        if (m.id in tree.expanded) ids.forEach { id ->
                            val segment = segments.getOrNull(id.substringAfterLast("::").toIntOrNull() ?: -1)
                            val label = when {
                                "::image::" in id -> "图片 ${id.substringAfterLast("::")}"
                                "::voice::" in id -> "录音 · ${snapshot.voices[m.id].orEmpty().firstOrNull { roleplayVoiceBlockId(m.id, it.voice.id) == id }?.voice?.characterName.orEmpty()}"
                                id.endsWith("::legacy") -> m.displayContent.take(100)
                                else -> segment?.displayText?.take(100).orEmpty()
                            }
                            StudioAction("${if (id in tree.selected) "☑" else "☐"} $label", enabled = enabled) {
                                onSelection(tree.copy(selected = toggleChatScreenshotSelection(tree.selected, id, all)))
                            }
                            if ("::text::" in id && segment?.kind == RoleplaySegmentKind.STATUS) StudioAction("截图中的状态：${if (statusExpanded[id] ?: segment.statusDefaultExpanded) "展开" else "收起"}", enabled = enabled) {
                                onStatus(statusExpanded + (id to !(statusExpanded[id] ?: segment.statusDefaultExpanded)))
                            }
                        }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
