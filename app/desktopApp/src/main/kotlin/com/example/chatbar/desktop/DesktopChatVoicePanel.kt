@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessagePlacement
import com.example.chatbar.domain.voice.*
import kotlinx.coroutines.launch

@Composable
internal fun rememberDesktopVoicePlacements(runtime: DesktopChatVoiceRuntime?, message: ChatMessage): List<VoiceMessagePlacement> {
    if (runtime == null || message.role != MessageRole.ASSISTANT) return emptyList()
    val records by runtime.repository.voices.collectAsState()
    var failed by remember(message) { mutableStateOf(false) }
    val result by produceState(emptyList<VoiceMessagePlacement>(), runtime, message, records) {
        try { value = runtime.repository.placementsForMessage(message); failed = false }
        catch (_: Exception) { value = emptyList(); failed = true }
    }
    if (failed) StatusText("语音记录读取失败；已有文件已保留", DesktopBootstrapColors.destructive)
    return result
}

@Composable
internal fun DesktopVoiceRows(runtime: DesktopChatVoiceRuntime?, message: ChatMessage,
    placements: List<VoiceMessagePlacement>, state: DesktopPrimaryChatState) {
    if (runtime == null || placements.isEmpty()) return
    val scope = rememberCoroutineScope()
    val playback by runtime.playback.state.collectAsState()
    placements.forEach { placement -> key(placement.voice.id) {
        val voice = placement.voice
        val active = playback.currentVoiceId == voice.id && playback.playing
        val paused = playback.currentVoiceId == voice.id && playback.paused
        var menuAnchor by remember { mutableStateOf<Rect?>(null) }
        var bounds by remember { mutableStateOf(Rect.Zero) }
        var tagged by remember(voice.updatedAt) { mutableStateOf(voice.taggedText) }
        var confirm by remember { mutableStateOf<String?>(null) }
        var pulse by remember { mutableStateOf(false) }
        LaunchedEffect(active) { while (active) { delay(450); pulse = !pulse } }
        Row(Modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInWindow() }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                if (event.buttons.isSecondaryPressed) { menuAnchor = bounds; event.changes.forEach { it.consume() } }
            }, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(32.dp).background(DesktopBootstrapColors.muted, RoundedCornerShape(6.dp))
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(6.dp))
                .semantics { contentDescription = "${if (active) "暂停" else if (paused) "继续播放" else "播放"} · ${voice.characterName} · ${voice.durationMs / 1000}s" }
                .clickable {
                    val intent = runtime.playbackIntent()
                    scope.launch { runtime.play(voice, message, intent) }
                }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(if (active) "Ⅱ" else "▶", style = TextStyle(color = DesktopBootstrapColors.foreground))
                BasicText("${voice.durationMs / 1000}s", style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
                if (active) BasicText(if (pulse) "▂▆▃▇" else "▆▂▇▃", maxLines = 1, overflow = TextOverflow.Clip,
                    style = TextStyle(color = DesktopBootstrapColors.primary, fontSize = 12.sp))
            }
            DesktopChatIconAction("录音操作 · ${voice.characterName}", DesktopAppIcons.More) { menuAnchor = bounds }
        }
        menuAnchor?.let { anchor ->
            DesktopChatContextPopup(anchor, listOf("录音" to listOf(
                DesktopChatMenuEntry("再生成", DesktopAppIcons.Refresh, true) { confirm = "additional" },
                DesktopChatMenuEntry("编辑替换", DesktopAppIcons.Edit, true) { confirm = "replace" },
                DesktopChatMenuEntry("保存 MP3", DesktopAppIcons.JumpBottom, true) { scope.launch { runtime.save(voice, state.selectedSession?.title ?: "聊天") } },
                DesktopChatMenuEntry("删除", DesktopAppIcons.Delete, true, true) { confirm = "delete" }
            ))) { menuAnchor = null }
        }
        confirm?.let { action ->
            Popup(alignment = Alignment.Center, onDismissRequest = { confirm = null }, properties = PopupProperties(focusable = true)) {
                Column(Modifier.widthIn(max = 480.dp).heightIn(max = 400.dp).background(DesktopBootstrapColors.card)
                    .border(1.dp, DesktopBootstrapColors.border).padding(12.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusText("音色快照：${voice.voice.title} · ${voice.fishModelId}")
                    StatusText("口播文本：${voice.effectiveSynthesisText}")
                    if (action == "replace") {
                        StatusText("成功后替换此录音并删除旧文件；失败保留原录音。标签文本由你确认。")
                        VoiceTextField("已确认的合成标签文本", tagged) { tagged = it }
                    } else StatusText(if (action == "delete") "确认仅删除这条录音？" else "新建独立录音，保留旧录音；翻译/标签可能使用所选辅助模型并计费。")
                    ActionRow {
                        StudioAction("确认${if (action == "delete") "删除" else "生成"}") {
                            val intent = runtime.playbackIntent()
                            scope.launch {
                                if (action == "delete") runtime.deleteVoice(voice)
                                else runtime.generate(message, voice.characterId, auxiliaryAuthorized = true,
                                    replace = voice.takeIf { action == "replace" }, editedTaggedText = tagged,
                                    additional = voice.takeIf { action == "additional" }, originGeneration = intent)
                                confirm = null
                            }
                        }
                        StudioAction("取消", style = StudioActionStyle.GHOST) { confirm = null }
                    }
                }
            }
        }
    } }
}

@Composable
private fun VoiceTextField(label: String, text: String, change: (String) -> Unit) {
    BasicTextField(text, change, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
        .semantics { contentDescription = label }.border(1.dp, DesktopBootstrapColors.border).padding(8.dp),
        textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
}

@Composable
internal fun DesktopVoiceGenerationDialog(runtime: DesktopChatVoiceRuntime?, message: ChatMessage,
    state: DesktopPrimaryChatState, segment: Int?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ui = LocalDesktopVoiceUi.current
    var narrator by remember(message.id) { mutableStateOf(ui.narrator) }
    val bound = state.selectedCharacter?.characters.orEmpty().filter { it.fishAudioVoice != null }
    val targets = runtime?.generationSegments(message, state.selectedSession, state.assistantSegmentedBubblesEnabled).orEmpty()
        .filter { segment == null || it.segmentIndex == segment }
    val needsNarrator = state.selectedCharacter?.let { VoiceGenerationPolicy.requiresNarratorSelection(it, targets) } == true
    val hasTarget = state.selectedCharacter?.let { card -> targets.any { VoiceGenerationPolicy.hasResolvableTarget(card, it) } } == true
    val focus = remember { FocusRequester() }
    Popup(alignment = Alignment.Center, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.onPreviewKeyEvent { event -> if(event.key==Key.Escape && event.type==KeyEventType.KeyDown) {onClose();true} else false }
            .focusRequester(focus).focusable().widthIn(max = 520.dp).background(DesktopBootstrapColors.card).padding(16.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.text.BasicText("确认生成语音", style = TextStyle(color = DesktopBootstrapColors.foreground))
            StatusText(if (segment == null) "当前整条回复 · 补齐缺失片段" else "当前片段 · ${segment + 1}")
            StatusText("仅使用明确选择的免费 Fish 模型；翻译和标签可能使用辅助模型并计费。")
            // Explicit selection: hidden persistent control when audiobook is off, available on demand here.
            if (needsNarrator) DesktopNarratorDropdown(state, narrator) { narrator = it; ui.narrator = it }
            StudioAction("授权并开始生成", enabled = runtime != null && state.selectedCharacter != null &&
                hasTarget && (!needsNarrator || bound.any { it.id == narrator })) {
                val intent = runtime!!.playbackIntent()
                scope.launch { runtime.generate(message, narrator, segment, auxiliaryAuthorized = true, originGeneration = intent); onClose() }
            }
            StudioAction("取消", style = StudioActionStyle.GHOST, onClick = onClose)
        }
    }
}
