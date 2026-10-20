package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.voice.VoiceGenerationPolicy
import kotlinx.coroutines.delay

internal class DesktopVoiceUiSelection { var narrator by mutableStateOf<String?>(null) }
internal val LocalDesktopVoiceUi = staticCompositionLocalOf { DesktopVoiceUiSelection() }

@Composable
internal fun DesktopNarratorDropdown(state: DesktopPrimaryChatState, selected: String?, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val bound = state.selectedCharacter?.characters.orEmpty().filter { it.fishAudioVoice != null }
    Row(Modifier.width(156.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("角色音色", maxLines = 1, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
        Box {
            StudioAction(bound.firstOrNull { it.id == selected }?.name?.let { if(it.length>4) it.take(4)+"…" else it } ?: "选择",
                accessibilityLabel = "选择角色音色 · ${bound.firstOrNull { it.id == selected }?.name ?: "尚未选择"}", style = StudioActionStyle.GHOST) { open = true }
            if (open) Popup(alignment = Alignment.TopEnd, properties = PopupProperties(focusable = true), onDismissRequest = { open = false }) {
                Column(Modifier.widthIn(max = 280.dp).heightIn(max = 320.dp).background(DesktopBootstrapColors.card)
                    .border(1.dp, DesktopBootstrapColors.border).verticalScroll(rememberScrollState()).padding(6.dp)) {
                    if (bound.isEmpty()) StatusText("当前角色卡没有已绑定音色")
                    bound.forEach { c -> StudioAction("${c.name} · ${c.fishAudioVoice?.title.orEmpty()}") { choose(c.id); open = false } }
                }
            }
        }
    }
}

@Composable
internal fun DesktopVoiceDeck(runtime: DesktopChatVoiceRuntime?, state: DesktopPrimaryChatState) {
    if (runtime == null || state.selectedSession == null) return
    val batches by runtime.coordinator.batches.collectAsState()
    val sessionBatches = batches.filter { it.sessionId == state.selectedSession.id }
    val batch = sessionBatches.firstOrNull()
    val audiobook = runtime.effectiveAudiobook(state.selectedSession)
    val ui = LocalDesktopVoiceUi.current
    var details by remember(state.selectedSession.id) { mutableStateOf(false) }
    if (batch == null) {
        if (audiobook) Row(Modifier.fillMaxWidth().height(40.dp).testTag("voice-role-dock"), horizontalArrangement = Arrangement.End) {
            DesktopNarratorDropdown(state, ui.narrator) { ui.narrator = it }
        }
        return
    }
    DesktopVoiceDeckRow(batch?.let { "${desktopVoicePhaseLabel(it.phase)} · ${it.completedCount}/${it.totalCount}" } ?: "听书模式",
        if (batch != null) ({ details = true }) else null,
        cancel = batch?.let { b -> { if (b.phase in setOf(VoiceGenerationPhase.FAILED, VoiceGenerationPhase.CANCELLED)) runtime.coordinator.dismiss(b.id) else runtime.coordinator.cancel(b.id) } },
        narrator = { if (audiobook) DesktopNarratorDropdown(state, ui.narrator) { ui.narrator = it } })
    if (details && batch != null) Popup(alignment = Alignment.BottomCenter, onDismissRequest = { details = false }, properties = PopupProperties(focusable = true)) {
        Column(Modifier.widthIn(max = 480.dp).heightIn(max = 360.dp).background(DesktopBootstrapColors.card)
            .border(1.dp, DesktopBootstrapColors.border).verticalScroll(rememberScrollState()).padding(12.dp)) {
            sessionBatches.forEachIndexed { index, item ->
                DesktopVoiceBatchDetails(item, index + 1,
                    resolve = { accepted -> runtime.coordinator.resolveTextConfirmation(item.id, accepted); details = false },
                    cancel = { if(item.phase in setOf(VoiceGenerationPhase.FAILED, VoiceGenerationPhase.CANCELLED)) runtime.coordinator.dismiss(item.id) else runtime.coordinator.cancel(item.id) })
            }
            StudioAction("关闭详情") { details = false }
        }
    }
}

@Composable
internal fun DesktopVoiceDeckRow(text: String, detail: (() -> Unit)?, cancel: (() -> Unit)?, narrator: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).testTag("voice-generation-deck")
        .background(DesktopBootstrapColors.card).border(1.dp, DesktopBootstrapColors.border).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicText(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 11.sp))
        if (detail != null) DesktopChatIconAction("语音生成详情", DesktopAppIcons.More, onClick = detail)
        if (cancel != null) DesktopChatIconAction("取消或关闭本批语音", DesktopAppIcons.Stop, onClick = cancel)
        Row(verticalAlignment = Alignment.CenterVertically) { narrator() }
    }
}

@Composable
internal fun DesktopFloatingVoicePlayer(runtime: DesktopChatVoiceRuntime?) {
    if (runtime == null) return
    val state by runtime.playback.state.collectAsState()
    if (state.currentVoiceId == null || (!state.playing && !state.paused)) return
    DesktopVoicePlayerRow(state, { runtime.resumePlayback() }, { runtime.pauseOrClose() })
}

@Composable
internal fun DesktopVoicePlayerRow(state: DesktopVoicePlaybackState, resume: () -> Unit, stop: () -> Unit) {
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(state.playing) { while (state.playing) { delay(450); pulse = !pulse } }
    Row(Modifier.height(38.dp).testTag("voice-floating-player").background(DesktopBootstrapColors.card, RoundedCornerShape(19.dp))
        .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(19.dp)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.alpha(if (!state.playing || pulse) 1f else .4f)) {
            DesktopChatIconAction(if (state.paused) "继续播放" else "正在播放语音", if (state.paused) DesktopAppIcons.Play else DesktopAppIcons.Voice,
                enabled = state.paused, onClick = resume)
        }
        BasicText(if (state.paused) "已暂停" else "播放中", Modifier.width(48.dp), maxLines = 1,
            style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
        DesktopChatIconAction(if (state.paused) "停止并关闭播放器" else "暂停并保留位置", DesktopAppIcons.Stop, onClick = stop)
    }
}

@Composable
internal fun DesktopChatNotifications(controller: DesktopPrimaryChatController, state: DesktopPrimaryChatState) {
    val runtime = controller.voices
    val notice = runtime?.notice?.collectAsState()?.value
    val feedbackEvent = runtime?.coordinator?.feedback?.collectAsState()?.value?.takeIf { it.sessionId == state.selectedSession?.id }
    val feedback = feedbackEvent?.text
    val playbackError = runtime?.playback?.state?.collectAsState()?.value?.error
    val text = feedback ?: notice ?: playbackError ?: state.status
    if (text != null) {
        fun dismiss() { if (feedback != null) feedbackEvent?.let { runtime?.coordinator?.dismissFeedback(it) }
            else if (notice != null) runtime?.dismissNotice(text) else if (playbackError != null) runtime?.playback?.dismissError(text) else controller.dismissStatus(text) }
        LaunchedEffect(feedbackEvent, notice, playbackError, state.status) { delay(8000); dismiss() }
        Row(Modifier.widthIn(max = 540.dp).background(DesktopBootstrapColors.card).border(1.dp, DesktopBootstrapColors.border).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicText(text, Modifier.weight(1f, fill = false), maxLines = 2, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
            DesktopChatIconAction("关闭通知", DesktopAppIcons.Close) { dismiss() }
        }
    }
}

internal fun desktopVoicePhaseLabel(phase: VoiceGenerationPhase): String = when(phase) {
    VoiceGenerationPhase.QUEUED -> "等待生成"
    VoiceGenerationPhase.TRANSLATING -> "翻译中"
    VoiceGenerationPhase.TAGGING -> "处理语音标签"
    VoiceGenerationPhase.AWAITING_TEXT_CONFIRMATION -> "待确认文本"
    VoiceGenerationPhase.SYNTHESIZING -> "生成语音中"
    VoiceGenerationPhase.FAILED -> "生成失败"
    VoiceGenerationPhase.CANCELLED -> "已取消"
}

@Composable
internal fun DesktopVoiceBatchDetails(batch: VoiceGenerationBatchState, index: Int,
    resolve: (Boolean) -> Unit, cancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(6.dp)) {
        StatusText("任务 $index · ${desktopVoicePhaseLabel(batch.phase)} · ${batch.completedCount}/${batch.totalCount}")
        batch.errors.values.distinct().forEach { StatusText(it, DesktopBootstrapColors.destructive) }
        batch.textConfirmations.forEach {
            StatusText("${it.characterName} · 原文：${it.originalText}")
            StatusText("AI 建议：${it.proposedTaggedText}")
        }
        if(batch.phase == VoiceGenerationPhase.AWAITING_TEXT_CONFIRMATION) {
            StudioAction("采用 AI 文本并生成", accessibilityLabel = "任务 $index · 采用 AI 文本并生成") { resolve(true) }
            StudioAction("拒绝改写", accessibilityLabel = "任务 $index · 拒绝改写") { resolve(false) }
        }
        StudioAction(if(batch.phase in setOf(VoiceGenerationPhase.FAILED,VoiceGenerationPhase.CANCELLED)) "关闭本批状态" else "取消本批语音",
            accessibilityLabel = "任务 $index · 取消或关闭本批语音", onClick = cancel)
    }
}
