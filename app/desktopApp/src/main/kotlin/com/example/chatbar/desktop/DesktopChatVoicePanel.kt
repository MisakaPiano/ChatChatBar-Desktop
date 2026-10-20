package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
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
internal fun DesktopVoiceProgress(runtime: DesktopChatVoiceRuntime?, sessionId: String) {
    if (runtime == null) return
    val batches by runtime.coordinator.batches.collectAsState()
    val playback by runtime.playback.state.collectAsState()
    val notice by runtime.notice.collectAsState()
    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (playback.playing) StudioAction("停止语音", icon = DesktopAppIcons.Stop,
            style = StudioActionStyle.PRIMARY) { runtime.stop() }
        playback.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        notice?.let { StatusText(it) }
        batches.filter { it.sessionId == sessionId }.forEach { batch ->
            StatusText("语音 · ${batch.phase} · ${batch.completedCount}/${batch.totalCount} · ${batch.receivedBytes} bytes")
            batch.errors.values.distinct().forEach { StatusText(it, DesktopBootstrapColors.destructive) }
            batch.textConfirmations.forEach { confirmation ->
                StatusText("${confirmation.characterName} · 原文：${confirmation.originalText}")
                StatusText("AI 建议：${confirmation.proposedTaggedText}")
            }
            if (batch.phase == VoiceGenerationPhase.AWAITING_TEXT_CONFIRMATION) ActionRow {
                StudioAction("采用 AI 文本并生成") { runtime.coordinator.resolveTextConfirmation(batch.id, true) }
                StudioAction("拒绝改写") { runtime.coordinator.resolveTextConfirmation(batch.id, false) }
            }
            if (batch.phase in setOf(VoiceGenerationPhase.FAILED, VoiceGenerationPhase.CANCELLED))
                StudioAction("关闭语音状态", style = StudioActionStyle.GHOST) { runtime.coordinator.dismiss(batch.id) }
            else StudioAction("取消本批语音", icon = DesktopAppIcons.Stop) { runtime.coordinator.cancel(batch.id) }
        }
    }
}

@Composable
internal fun DesktopVoiceRows(runtime: DesktopChatVoiceRuntime?, message: ChatMessage,
    placements: List<VoiceMessagePlacement>, state: DesktopPrimaryChatState) {
    if (runtime == null || placements.isEmpty()) return
    val scope = rememberCoroutineScope()
    placements.forEach { placement -> key(placement.voice.id) {
        val voice = placement.voice
        var expanded by remember { mutableStateOf(false) }
        var tagged by remember(voice.updatedAt) { mutableStateOf(voice.taggedText) }
        var confirm by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionRow {
                StudioAction("播放 · ${voice.characterName} · ${voice.durationMs / 1000}s", icon = DesktopAppIcons.Play) {
                    val intent = runtime.playbackIntent()
                    scope.launch { runtime.play(voice, message, intent) }
                }
                StudioAction(if (expanded) "收起录音操作" else "录音操作", style = StudioActionStyle.GHOST) { expanded = !expanded }
            }
            if (expanded) {
                StatusText("音色快照：${voice.voice.title} · ${voice.fishModelId}")
                StatusText("口播文本：${voice.effectiveSynthesisText}")
                ActionRow {
                    StudioAction("再生成一条（保留旧录音）") { confirm = "additional" }
                    StudioAction("编辑标签 / 替换录音") { confirm = "replace" }
                    StudioAction("保存 MP3") { scope.launch { runtime.save(voice, state.selectedSession?.title ?: "聊天") } }
                    StudioAction("删除此录音") { confirm = "delete" }
                }
            }
            confirm?.let { action ->
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
    } }
}

@Composable
internal fun DesktopVoiceGenerationControls(runtime: DesktopChatVoiceRuntime?, message: ChatMessage, state: DesktopPrimaryChatState) {
    if (runtime == null || message.role != MessageRole.ASSISTANT) return
    val scope = rememberCoroutineScope()
    var expanded by remember(message.id) { mutableStateOf(false) }
    var narrator by remember(message.id) { mutableStateOf<String?>(null) }
    var pending by remember(message.id) { mutableStateOf<Int?>(null) }
    var confirm by remember(message.id) { mutableStateOf(false) }
    val card = state.selectedCharacter
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioAction(if (expanded) "收起语音生成" else "语音生成", style = StudioActionStyle.GHOST) { expanded = !expanded }
        if (expanded) {
            StatusText("仅明确点击后生成。整条会补齐缺失片段；新录音默认保留历史。")
            StatusText("仅当前明确选择的免费模型可用；翻译/标签会使用配置的辅助模型，可能计费。")
            card?.characters?.filter { it.fishAudioVoice != null }?.let { bound ->
                ActionRow { bound.forEach { character ->
                    StudioAction("朗读声线 · ${character.name}", selected = narrator == character.id, style = StudioActionStyle.CHIP) { narrator = character.id }
                } }
            }
            StudioAction("生成整条回复 / 补齐语音", enabled = card != null) { pending = null; confirm = true }
            runtime.generationSegments(message, state.selectedSession, state.assistantSegmentedBubblesEnabled).forEach { segment ->
                if (segment.sourceScope != VoiceSourceScope.WHOLE_MESSAGE) StudioAction(
                    "生成本段 · ${segment.speakerName ?: "旁白"} · ${segment.spokenText.take(32)}",
                    enabled = card != null && VoiceGenerationPolicy.hasResolvableTarget(card, segment)) { pending = segment.segmentIndex; confirm = true }
            }
            if (confirm) {
                StatusText("确认使用当前免费 Fish 模型及所需的辅助模型？未授权的收费 Fish 模型不会发送。")
                ActionRow {
                    StudioAction("授权并开始生成", style = StudioActionStyle.PRIMARY) {
                        val intent = runtime.playbackIntent()
                        scope.launch { runtime.generate(message, narrator, pending, auxiliaryAuthorized = true, originGeneration = intent); confirm = false }
                    }
                    StudioAction("取消", style = StudioActionStyle.GHOST) { confirm = false }
                }
            }
        }
    }
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
    var narrator by remember(message.id) { mutableStateOf<String?>(null) }
    DesktopModalSurface {
        Column(Modifier.widthIn(max = 520.dp).background(DesktopBootstrapColors.card).padding(16.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.text.BasicText("确认生成语音", style = TextStyle(color = DesktopBootstrapColors.foreground))
            StatusText(if (segment == null) "当前整条回复 · 补齐缺失片段" else "当前片段 · ${segment + 1}")
            StatusText("仅使用明确选择的免费 Fish 模型；翻译和标签可能使用辅助模型并计费。")
            StatusText("选择朗读音色 · 角色音色")
            state.selectedCharacter?.characters?.filter { it.fishAudioVoice != null }?.forEach { c ->
                StudioAction(c.name, selected = narrator == c.id, style = StudioActionStyle.CHIP) { narrator = c.id }
            }
            StudioAction("授权并开始生成", enabled = runtime != null && state.selectedCharacter != null) {
                val intent = runtime!!.playbackIntent()
                scope.launch { runtime.generate(message, narrator, segment, auxiliaryAuthorized = true, originGeneration = intent); onClose() }
            }
            StudioAction("取消", style = StudioActionStyle.GHOST, onClick = onClose)
        }
    }
}
