package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.voice.VoiceGenerationPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal class DesktopVoiceUiSelection {
    var narrator by mutableStateOf<String?>(null)
    var narratorCollapsed by mutableStateOf(false)
    var composerExpanded by mutableStateOf(false)
}
internal val LocalDesktopVoiceUi = staticCompositionLocalOf { DesktopVoiceUiSelection() }

internal fun desktopNarratorChoices(state: DesktopPrimaryChatState) =
    state.participatingCharacter?.characters.orEmpty().filter { !it.fishAudioVoice?.referenceId.isNullOrBlank() }

@Composable
internal fun DesktopNarratorDropdown(state: DesktopPrimaryChatState, selected: String?, collapse: () -> Unit = {},
    choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val bound = desktopNarratorChoices(state)
    if (bound.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth().height(38.dp).background(DesktopBootstrapColors.card, RoundedCornerShape(19.dp))
        .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(19.dp))) {
        val name = bound.firstOrNull { it.id == selected }?.name ?: "选择角色"
        val label = if (maxWidth < 76.dp) "旁白" else "旁白：$name"
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "选择角色音色 · $name" }
            .clickable { open = true }.wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 8.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
        DesktopChatIconAction("折叠音色选择", DesktopAppIcons.DetailsClosed, targetDp = 28, onClick = collapse)
        }
        if (open) Popup(alignment = Alignment.TopEnd, properties = PopupProperties(focusable = true), onDismissRequest = { open = false }) {
            Column(Modifier.width(IntrinsicSize.Max).widthIn(max = 280.dp).heightIn(max = 320.dp).background(DesktopBootstrapColors.card)
                .border(1.dp, DesktopBootstrapColors.border).verticalScroll(rememberScrollState()).padding(6.dp)) {
                if (bound.isEmpty()) StatusText("当前启用人物没有已绑定音色")
                bound.forEach { c ->
                    BasicText("${c.name} · ${c.fishAudioVoice?.title.orEmpty()}", Modifier.fillMaxWidth()
                        .semantics { contentDescription = "选择 ${c.name}" }.clickable { choose(c.id); open = false }.padding(8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
                }
            }
        }
    }
}

@Composable
internal fun DesktopNarratorDock(state: DesktopPrimaryChatState, ui: DesktopVoiceUiSelection) {
    if (desktopNarratorChoices(state).isEmpty()) return
    if (ui.narratorCollapsed) Box(Modifier.size(38.dp).testTag("voice-role-ball")
        .background(DesktopBootstrapColors.card, androidx.compose.foundation.shape.CircleShape)
        .border(1.dp, DesktopBootstrapColors.border, androidx.compose.foundation.shape.CircleShape)
        .semantics { contentDescription = "展开音色选择" }.clickable { ui.narratorCollapsed = false },
        contentAlignment = Alignment.Center) {
        BasicText("音色", style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
    } else DesktopNarratorDropdown(state, ui.narrator, { ui.narratorCollapsed = true }) { ui.narrator = it }
}

/** Narrator configuration belongs to the composer; generation and playback remain in their own deck. */
@Composable
internal fun DesktopComposerVoiceControl(state: DesktopPrimaryChatState, ui: DesktopVoiceUiSelection, audiobook: Boolean,
    availableWidthDp: Float = 600f) {
    if (!audiobook || desktopNarratorChoices(state).isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("composer-voice-control")) {
        DesktopChatIconAction(if (ui.composerExpanded) "收起朗读音色" else "朗读音色设置", DesktopAppIcons.Voice, targetDp = 36) {
            ui.composerExpanded = !ui.composerExpanded
        }
        if (ui.composerExpanded) Box(Modifier.width((availableWidthDp - 240f).coerceIn(76f, 220f).dp)) {
            DesktopNarratorDropdown(state, ui.narrator, { ui.composerExpanded = false }) { ui.narrator = it }
        }
    }
}

@Composable
internal fun DesktopVoiceDeck(runtime: DesktopChatVoiceRuntime?, state: DesktopPrimaryChatState,
    preferences: DesktopChatUiController? = null) {
    if (runtime == null || state.selectedSession == null) return
    val batches by runtime.coordinator.batches.collectAsState()
    val playback by runtime.playback.state.collectAsState()
    val playerVisible = playback.currentVoiceId != null && (playback.playing || playback.paused) &&
        playback.sessionId == state.selectedSession.id
    val sessionBatches = batches.filter { it.sessionId == state.selectedSession.id }
    val batch = sessionBatches.firstOrNull()
    val audiobook = runtime.effectiveAudiobook(state.selectedSession)
    val ui = LocalDesktopVoiceUi.current
    var details by remember(state.selectedSession.id) { mutableStateOf(false) }
    if (batch == null && !playerVisible) return
    DesktopVoiceDeckRow(batch?.let { "${desktopVoicePhaseLabel(it.phase)} · ${it.completedCount}/${it.totalCount}" }.orEmpty(),
        if (batch != null) ({ details = true }) else null,
        cancel = batch?.let { b -> { if (b.phase in setOf(VoiceGenerationPhase.FAILED, VoiceGenerationPhase.CANCELLED)) runtime.coordinator.dismiss(b.id) else runtime.coordinator.cancel(b.id) } },
        player = { if (playerVisible) DesktopRuntimeVoicePlayer(runtime, playback, preferences) },
        narrator = {})
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
internal fun DesktopVoiceDeckRow(text: String, detail: (() -> Unit)?, cancel: (() -> Unit)?,
    player: @Composable () -> Unit = {}, narrator: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).testTag("voice-generation-deck"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f)) {
            if (text.isNotEmpty()) Row(Modifier.fillMaxWidth().height(38.dp)
                .background(DesktopBootstrapColors.card, RoundedCornerShape(19.dp))
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(19.dp)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicText(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 11.sp))
                if (detail != null) DesktopChatIconAction("语音生成详情", DesktopAppIcons.More, onClick = detail)
                if (cancel != null) DesktopChatIconAction("取消或关闭本批语音", DesktopAppIcons.Stop, onClick = cancel)
            }
        }
        Box(contentAlignment = Alignment.Center) { player() }
        Box(Modifier.weight(1f).testTag("voice-role-dock"), contentAlignment = Alignment.CenterEnd) { narrator() }
    }
}

/** App-lifetime position; hiding the player or changing routes does not reset a user's drag. */
internal class DesktopFloatingVoicePosition {
    var preferred by mutableStateOf<Offset?>(null)
}

@Composable
internal fun DesktopFloatingVoicePlayerHost(runtime: DesktopChatVoiceRuntime?, preferences: DesktopChatUiController?,
    position: DesktopFloatingVoicePosition) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var playerSize by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val parentSize = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val margin = with(density) { 16.dp.toPx() }
        val endX = (parentSize.width - playerSize.width).coerceAtLeast(0).toFloat()
        val endY = (parentSize.height - playerSize.height).coerceAtLeast(0).toFloat()
        val insetX = margin.coerceAtMost(endX / 2)
        val insetY = margin.coerceAtMost(endY / 2)
        val wanted = position.preferred ?: Offset(endX - insetX, endY - insetY)
        val visible = Offset(wanted.x.coerceIn(insetX, endX - insetX), wanted.y.coerceIn(insetY, endY - insetY))
        Box(Modifier.offset { IntOffset(visible.x.roundToInt(), visible.y.roundToInt()) }
            .onSizeChanged { playerSize = it }.testTag("floating-voice-player-window")) {
            DesktopFloatingVoicePlayer(runtime, preferences) { delta ->
                val current = position.preferred?.let { Offset(it.x.coerceIn(insetX, endX - insetX),
                    it.y.coerceIn(insetY, endY - insetY)) } ?: visible
                position.preferred = Offset((current.x + delta.x).coerceIn(insetX, endX - insetX),
                    (current.y + delta.y).coerceIn(insetY, endY - insetY))
            }
        }
    }
}

@Composable
internal fun DesktopFloatingVoicePlayer(runtime: DesktopChatVoiceRuntime?, preferences: DesktopChatUiController? = null,
    onDrag: ((Offset) -> Unit)? = null) {
    if (runtime == null) return
    val state by runtime.playback.state.collectAsState()
    if (state.currentVoiceId == null) return
    val source by produceState("语音播放", runtime, state.sessionId) {
        value = runCatching { runtime.playbackSourceTitle(state.sessionId) }.getOrDefault("语音播放")
    }
    val notice by runtime.notice.collectAsState()
    val drag by rememberUpdatedState(onDrag)
    Column(Modifier.widthIn(max = 340.dp).background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp))
        .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(12.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(source, Modifier.fillMaxWidth().heightIn(min = 28.dp)
            .testTag("floating-voice-player-drag-handle")
            .then(if (onDrag == null) Modifier else Modifier.semantics { contentDescription = "拖动播放器" }
                .pointerInput(Unit) { detectDragGestures { change, delta -> change.consume(); drag?.invoke(delta) } }),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
        DesktopRuntimeVoicePlayer(runtime, state, preferences)
        state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        notice?.let { text -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { StatusText(text) }
            DesktopChatIconAction("关闭播放器提示", DesktopAppIcons.Close, targetDp = 28) { runtime.dismissNotice(text) }
        } }
    }
}

@Composable private fun DesktopRuntimeVoicePlayer(runtime: DesktopChatVoiceRuntime,
    state: DesktopVoicePlaybackState, preferences: DesktopChatUiController?) {
    DesktopVoicePlayerRow(state, runtime::resumePlayback, runtime::stop,
        settings = preferences?.let { { DesktopPlaybackSettings(it, state.sessionId) } },
        pause = runtime::pausePlayback,
        previous = { runtime.adjacentPlayback(false) }, next = { runtime.adjacentPlayback(true) })
}

@Composable
internal fun DesktopVoicePlayerRow(state: DesktopVoicePlaybackState, resume: () -> Unit, stop: () -> Unit,
    settings: (@Composable () -> Unit)? = null, pause: (() -> Unit)? = null,
    previous: (() -> Unit)? = null, next: (() -> Unit)? = null) {
    Row(Modifier.height(38.dp).testTag("voice-floating-player").background(DesktopBootstrapColors.card, RoundedCornerShape(19.dp))
        .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(19.dp)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        previous?.let { DesktopChatIconAction("上一句语音", DesktopAppIcons.Previous, targetDp = 28, onClick = it) }
        DesktopChatIconAction(if (state.paused) "继续播放" else "暂停并保留位置",
            if (state.paused) DesktopAppIcons.Play else DesktopAppIcons.Pause, targetDp = 28,
            onClick = if (state.paused) resume else pause ?: stop)
        next?.let { DesktopChatIconAction("下一句语音", DesktopAppIcons.Next, targetDp = 28, onClick = it) }
        BasicText(if (state.paused) "已暂停" else "播放中", Modifier.width(48.dp), maxLines = 1,
            style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
        DesktopChatIconAction("停止并关闭播放器", DesktopAppIcons.Stop, targetDp = 28, onClick = stop)
        settings?.invoke()
    }
}

@Composable internal fun DesktopPlaybackSettings(controller: DesktopChatUiController, sessionId: String? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        DesktopChatIconAction("播放设置", DesktopAppIcons.Settings, targetDp = 28) { open = !open }
        if (open) Popup(alignment = Alignment.TopEnd, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(260.dp).background(DesktopBootstrapColors.card, RoundedCornerShape(8.dp))
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopContinuousPlaybackSwitch(controller, showDescription = false) }
                    DesktopChatIconAction("关闭播放设置", DesktopAppIcons.Close, targetDp = 28) { open = false }
                }
                StatusText("播完当前回复后，继续播放后续助手回复已有的录音。")
                sessionId?.let {
                    DesktopSessionContinuousPlaybackSetting(controller, it, label = "会话连续播放")
                    StatusText("仅影响当前录音所属的会话；单独开关会覆盖全局默认。")
                }
            }
        }
    }
}

@Composable internal fun DesktopSessionContinuousPlaybackSetting(controller: DesktopChatUiController, sessionId: String,
    label: String = "本会话连续播放") {
    val preferences by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val selection = preferences.sessionContinuousVoicePlayback[sessionId]
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SearchableChoice(label, listOf<Boolean?>(null, true, false), selection, {
            when (it) { null -> "跟随全局（${if (preferences.continuousVoicePlayback) "开启" else "关闭"}）"; true -> "开启"; false -> "关闭" }
        }) { value -> scope.launch { controller.update {
            it.copy(sessionContinuousVoicePlayback = it.sessionContinuousVoicePlayback - sessionId +
                if (value == null) emptyMap() else mapOf(sessionId to value))
        } } }
    }
}

@Composable internal fun DesktopContinuousPlaybackSwitch(controller: DesktopChatUiController, showDescription: Boolean = true) {
    val preferences by controller.state.collectAsState()
    val error by controller.error.collectAsState()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioSwitch("全局默认连续播放", preferences.continuousVoicePlayback) {
            scope.launch { controller.update { it.copy(continuousVoicePlayback = !it.continuousVoicePlayback) } }
        }
        if (showDescription) StatusText("播完当前回复后，继续播放后续助手回复已有的录音；会话单独设置优先。")
        error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
    }
}

@Composable
internal fun DesktopChatNotifications(controller: DesktopPrimaryChatController, state: DesktopPrimaryChatState) {
    val runtime = controller.voices
    val residency = controller.residency
    val residentNotice = residency?.notices?.collectAsState()?.value?.get(state.selectedSession?.id)
    val notice = runtime?.notice?.collectAsState()?.value
    val feedbackEvent = runtime?.coordinator?.feedback?.collectAsState()?.value?.takeIf { it.sessionId == state.selectedSession?.id }
    val feedback = feedbackEvent?.text
    val playbackError = runtime?.playback?.state?.collectAsState()?.value?.error
    val text = feedback ?: notice ?: playbackError ?: residentNotice?.text ?: state.status
    if (text != null) {
        fun dismiss() { if (feedback != null) feedbackEvent?.let { runtime?.coordinator?.dismissFeedback(it) }
            else if (notice != null) runtime?.dismissNotice(text) else if (playbackError != null) runtime?.playback?.dismissError(text)
            else if (residentNotice != null) residency?.dismiss(residentNotice) else controller.dismissStatus(text) }
        LaunchedEffect(feedbackEvent, notice, playbackError, residentNotice, state.status) { delay(8000); dismiss() }
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
