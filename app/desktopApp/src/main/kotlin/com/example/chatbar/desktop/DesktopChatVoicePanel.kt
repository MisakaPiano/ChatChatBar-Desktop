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
    DesktopModalSurface {
        Column(Modifier.widthIn(max = 520.dp).background(DesktopBootstrapColors.card).padding(16.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.text.BasicText("确认生成语音", style = TextStyle(color = DesktopBootstrapColors.foreground))
            StatusText(if (segment == null) "当前整条回复 · 补齐缺失片段" else "当前片段 · ${segment + 1}")
            StatusText("仅使用明确选择的免费 Fish 模型；翻译和标签可能使用辅助模型并计费。")
            // Explicit selection: hidden persistent control when audiobook is off, available on demand here.
            DesktopNarratorDropdown(state, narrator) { narrator = it; ui.narrator = it }
            StudioAction("授权并开始生成", enabled = runtime != null && state.selectedCharacter != null &&
                (bound.size <= 1 || bound.any { it.id == narrator })) {
                val intent = runtime!!.playbackIntent()
                scope.launch { runtime.generate(message, narrator, segment, auxiliaryAuthorized = true, originGeneration = intent); onClose() }
            }
            StudioAction("取消", style = StudioActionStyle.GHOST, onClick = onClose)
        }
    }
}
