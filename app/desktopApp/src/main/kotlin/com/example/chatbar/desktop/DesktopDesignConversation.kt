package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.launch

/** The whole Design tool is one lifetime, including its settings/history sub-surfaces. */
@Composable
internal fun DesktopDesignToolLifecycle(controller: DesktopNovelAiStudioController, active: Boolean) {
    DisposableEffect(controller, active) { onDispose { if (active) controller.leaveDesignScreen() } }
}

@Composable
internal fun ColumnScope.DesktopDesignConversation(controller: DesktopNovelAiStudioController, draft: NovelAiStudioDraft,
    busy: Boolean, authentication: DesktopDesignAuthentication, onSettings: () -> Unit, onHistory: () -> Unit,
    onModelSettings: () -> Unit, onApplied: () -> Unit) {
    val conversations by controller.designRepository.conversations.collectAsState()
    val currentId by controller.designRepository.currentConversationId.collectAsState()
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val composer by controller.designComposer.collectAsState()
    LaunchedEffect(controller) { controller.initializeDesignComposer() }
    if (!composer.initialized) { StatusText("正在读取设计对话…"); return }
    val conversation = conversations.firstOrNull { !composer.composingNew && it.id == currentId }
    val initial = remember(controller, conversation?.id) {
        conversation?.let { controller.designRepository.consumeInitialScrollPosition(it.id, it.turns.size) } ?: (0 to 0)
    }
    val scroll = key(conversation?.id) { rememberLazyListState(initial.first, initial.second) }
    DisposableEffect(controller, conversation?.id, scroll) {
        onDispose { conversation?.let {
            controller.designRepository.rememberScrollPosition(it.id, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
        } }
    }
    val latestTurn = conversation?.turns?.lastOrNull()?.id
    var observedTurn by remember(conversation?.id) { mutableStateOf(latestTurn) }
    LaunchedEffect(conversation?.id, latestTurn) {
        if (latestTurn != observedTurn) {
            observedTurn = latestTurn
            conversation?.turns?.lastIndex?.takeIf { it >= 0 }?.let { scroll.animateScrollToItem(it) }
        }
    }
    StudioActions {
        StudioAction("设计历史", onClick = onHistory)
        StudioAction("新对话", enabled = !busy && composer.initialized) { controller.newDesignConversation() }
        StudioAction("设计设置", onClick = onSettings)
    }
    StatusText("${authentication.model} · ${authentication.provider} · ${authentication.status}")
    if (!authentication.configured) StudioAction("打开模型设置", onClick = onModelSettings)
    LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "AI 设计对话记录" }, state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (conversation == null) item { StatusText("描述想要的画面。首轮设计完整 Prompt，之后继续提出修改。") }
        items(conversation?.turns.orEmpty(), key = { it.id }) { turn ->
            var editing by remember { mutableStateOf(false) }
            var edited by remember(turn.userText) { mutableStateOf(turn.userText) }
            Column(Modifier.fillMaxWidth().padding(start = 44.dp).background(DesktopBootstrapColors.muted).padding(12.dp)) {
                StatusText("你")
                if (editing) DesktopDesignField("编辑本轮需求", edited, turn.id) { edited = it }
                else SelectionContainer { StatusText(turn.userText) }
                turn.attachedStudioPrompt?.let { StatusText("已附加基础 Prompt + ${it.characterPrompts.size} 个角色") }
                StudioActions {
                    StudioAction(if (editing) "取消编辑" else "编辑并分支", enabled = !busy) { editing = !editing; edited = turn.userText }
                    if (editing) StudioAction("确认并分支", enabled = !busy && authentication.configured && edited.isNotBlank()) {
                        scope.launch { controller.retryDesign(requireNotNull(conversation), turn, editedText = edited); editing = false }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusText("AI 设计")
                turn.reply?.let { DesktopDesignResult(it) }
                if (turn.status != NovelAiDesignTurnStatus.COMPLETED) StatusText(turn.error.ifBlank {
                    if (turn.status == NovelAiDesignTurnStatus.PENDING) "正在设计…" else turn.status.name
                }, DesktopBootstrapColors.warning)
                StudioActions {
                    StudioAction(if (turn.status == NovelAiDesignTurnStatus.COMPLETED) "重新设计" else "重试",
                        enabled = !busy && authentication.configured) { scope.launch {
                        controller.retryDesign(requireNotNull(conversation), turn, regenerate = turn.status == NovelAiDesignTurnStatus.COMPLETED)
                    } }
                    turn.reply?.let { reply -> StudioAction("应用到 Studio", enabled = !busy, style = StudioActionStyle.PRIMARY) {
                        scope.launch { controller.applyDesign(reply); onApplied() }
                    } }
                }
            }
        }
        if (state.designProgress.stage.isNotBlank()) item {
            key(busy) { StudioDisclosure("本地 Tag 检索 / 设计进度", if (busy) state.designProgress.stage else "已结束", initiallyOpen = busy) {
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { DesktopMarkdownText(state.designProgress.content) }
                    if (state.designProgress.reasoning.isNotBlank()) StudioDisclosure("思考") {
                        SelectionContainer { DesktopMarkdownText(state.designProgress.reasoning) }
                    }
                }
            } }
        }
    }
    Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(8.dp)) {
        StudioToggle("附加当前 Studio 正面 Prompt · 基础 + ${draft.activeCharacters.size} 个角色", composer.attachStudioPrompt) { controller.attachDesignPrompt(!composer.attachStudioPrompt) }
        DesktopDesignField("画面需求", composer.input, "composer-${composer.revision}-$currentId", onChange = controller::editDesignInput)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            StudioAction(if (busy) "停止" else "发送", enabled = busy || (composer.initialized && authentication.configured && composer.input.isNotBlank()),
                style = StudioActionStyle.PRIMARY) {
                if (busy) controller.stop() else scope.launch {
                    controller.design(composer.input, composer.composingNew, composer.attachStudioPrompt)
                }
            }
        }
    }
}
