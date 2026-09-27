package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import kotlinx.coroutines.launch

@Composable
internal fun DesktopPrimaryChatPanel(
    controller: DesktopPrimaryChatController,
    size: DesktopShellSize,
) {
    val state by controller.state.collectAsState()
    val tasks by controller.taskRuntime.tasks.collectAsState()
    val scope = rememberCoroutineScope()
    var compactBrowser by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    val terminalTasks = tasks.filter { it.status != DesktopTaskStatus.RUNNING }
    val terminalSignature = terminalTasks.joinToString("|") { "${it.taskId}:${it.completedAt}" }
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(terminalSignature) {
        terminalTasks.forEach { task -> task.sessionId?.let { controller.refreshAfterTerminalTask(it) } }
    }
    val running = tasks.firstOrNull {
        it.sessionId == state.selectedSession?.id && it.status == DesktopTaskStatus.RUNNING
    }
    val colors = DesktopBootstrapColors

    Box(
        Modifier.fillMaxSize().border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .background(colors.card, RoundedCornerShape(14.dp)),
    ) {
        Row(Modifier.fillMaxSize()) {
            if (size != DesktopShellSize.COMPACT || compactBrowser) {
                Column(
                    Modifier.then(if (size == DesktopShellSize.COMPACT) Modifier.fillMaxSize() else Modifier.width(236.dp))
                        .fillMaxHeight().border(1.dp, colors.border).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrimaryHeading("Sessions")
                    BootstrapButton("Refresh", secondary = true) { scope.launch { controller.refresh() } }
                    state.configurationMessage?.takeIf { !state.modelUsable && state.selectedSession == null && it != "Select or create a session" }
                        ?.let { StatusText(it, colors.warning) }
                    state.error?.let { StatusText(it, colors.destructive) }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { StatusText("Create from character") }
                        if (state.characters.isEmpty()) item { StatusText("No characters yet · import one in Manage") }
                        items(state.characters, key = { "character:${it.id}" }) { character ->
                            BootstrapButton("New · ${character.label.take(32)}", secondary = true) {
                                scope.launch {
                                    controller.createSession(character.id)
                                    if (controller.state.value.selectedSession != null) compactBrowser = false
                                }
                            }
                        }
                        item { PrimaryHeading("Recent sessions") }
                        if (state.sessions.isEmpty()) item { StatusText("No sessions") }
                        items(state.sessions, key = { it.id }) { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                BootstrapButton(
                                    "${if (item.pinned) "● " else ""}${item.title.take(42)}",
                                    secondary = state.selectedSession?.id != item.id,
                                ) {
                                    scope.launch {
                                        controller.selectSession(item.id)
                                        compactBrowser = false
                                    }
                                }
                                StatusText(item.characterName ?: "Archived · character missing", if (item.characterMissing) colors.warning else colors.mutedForeground)
                                item.lastMessagePreview?.takeIf(String::isNotBlank)?.let { StatusText(it.take(75)) }
                            }
                        }
                    }
                }
            }
            if (size != DesktopShellSize.COMPACT || !compactBrowser) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (size == DesktopShellSize.COMPACT) {
                        BootstrapButton("← Sessions", secondary = true) { compactBrowser = true }
                    }
                    val selected = state.selectedSession
                    PrimaryHeading(state.sessions.firstOrNull { it.id == selected?.id }?.title ?: "Chat")
                    if (selected == null) {
                        StatusText("Choose a session or create one from a character")
                    } else {
                        if (state.selectedCharacterMissing) StatusText("Archived session · character missing · history remains readable", colors.warning)
                        if (size != DesktopShellSize.WIDE) {
                            BootstrapButton("Session settings", secondary = true) { showSettings = true }
                        }
                        PrimaryTimeline(state, running, controller, Modifier.weight(1f))
                        state.configurationMessage?.let { StatusText(it, colors.warning) }
                        state.error?.let { StatusText(it, colors.destructive) }
                        state.status?.let { StatusText(it) }
                        PrimaryComposer(state, running, controller)
                    }
                }
            }
            if (size == DesktopShellSize.WIDE) {
                Column(
                    Modifier.width(264.dp).fillMaxHeight().border(1.dp, colors.border)
                        .verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrimaryUtilities(state, running, controller)
                }
            }
        }
        if (showSettings && size != DesktopShellSize.WIDE) {
            Column(
                Modifier.fillMaxSize().background(Color(0xEEFFFFFF)).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BootstrapButton("Close settings", secondary = true) { showSettings = false }
                PrimaryUtilities(state, running, controller)
            }
        }
    }
}

@Composable
private fun PrimaryTimeline(
    state: DesktopPrimaryChatState,
    running: DesktopTaskEntry?,
    controller: DesktopPrimaryChatController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val selectedId = state.selectedSession?.id
    val itemCount = state.messages.size + (if (running != null) 1 else 0) + (if (state.hasOlderMessages) 1 else 0)
    LaunchedEffect(selectedId) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }
    LaunchedEffect(itemCount, running?.contentPreview?.length, running?.reasoningPreview?.length) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (itemCount > 0 && lastVisible >= itemCount - 2) listState.animateScrollToItem(itemCount - 1)
    }
    LazyColumn(
        modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(8.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.hasOlderMessages) item(key = "older") {
            BootstrapButton("Load older · ${state.totalMessageCount} total", secondary = true) {
                scope.launch { controller.loadOlder() }
            }
        }
        items(state.messages, key = ChatMessage::id) { message -> PrimaryMessageBubble(message) }
        if (running != null) item(key = "stream:${running.taskId}") {
            PrimaryBubble("ASSISTANT · generating", running.contentPreview, running.reasoningPreview)
        }
    }
}

@Composable
private fun PrimaryMessageBubble(message: ChatMessage) {
    PrimaryBubble(message.role.name, message.displayContent, message.reasoningContent.orEmpty())
}

@Composable
private fun PrimaryBubble(role: String, content: String, reasoning: String) {
    val colors = DesktopBootstrapColors
    Column(
        Modifier.fillMaxWidth().background(if (role.startsWith("USER")) colors.muted else colors.card, RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        StatusText(role)
        if (reasoning.isNotBlank()) {
            SelectionContainer { BasicText(reasoning, style = TextStyle(color = colors.mutedForeground, fontSize = 12.sp)) }
        }
        SelectionContainer { BasicText(content, style = TextStyle(color = colors.foreground, fontSize = 14.sp)) }
    }
}

@Composable
private fun PrimaryComposer(
    state: DesktopPrimaryChatState,
    running: DesktopTaskEntry?,
    controller: DesktopPrimaryChatController,
) {
    val scope = rememberCoroutineScope()
    var input by remember(state.selectedSession?.id) { mutableStateOf(TextFieldValue(state.composerDraft)) }
    LaunchedEffect(state.selectedSession?.id, state.composerDraft) {
        if (input.text != state.composerDraft && input.composition == null) input = TextFieldValue(state.composerDraft)
    }
    val canLaunch = state.modelUsable && !state.selectedCharacterMissing && running == null
    BasicTextField(
        value = input,
        onValueChange = {
            input = it
            controller.editComposer(it.text)
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(10.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed &&
                    input.composition == null && canLaunch && input.text.isNotBlank()
                ) {
                    scope.launch { controller.send() }
                    true
                } else false
            },
        textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
    )
    StatusText("Ctrl+Enter sends · Enter / Shift+Enter inserts a newline")
    ActionRow {
        BootstrapButton("Send", enabled = canLaunch && state.composerDraft.isNotBlank()) {
            scope.launch { controller.send() }
        }
        BootstrapButton("Continue", enabled = canLaunch, secondary = true) {
            scope.launch { controller.continueReply() }
        }
        if (running != null) BootstrapButton("Stop", secondary = true) { controller.stop(running.taskId) }
    }
    running?.let { StatusText("Task: ${it.message}") }
}

@Composable
private fun PrimaryUtilities(
    state: DesktopPrimaryChatState,
    running: DesktopTaskEntry?,
    controller: DesktopPrimaryChatController,
) {
    val scope = rememberCoroutineScope()
    PrimaryHeading("Session & task")
    val session = state.selectedSession
    if (session == null) {
        StatusText("Select a session")
        return
    }
    val item = state.sessions.firstOrNull { it.id == session.id }
    BootstrapButton(if (item?.pinned == true) "Unpin" else "Pin", secondary = true) {
        scope.launch { controller.togglePin(session.id) }
    }
    var title by remember(session.id, session.displayTitleOverride) {
        mutableStateOf(session.displayTitleOverride.orEmpty())
    }
    PrimaryField("Display title override · blank uses original", title) { title = it }
    BootstrapButton("Save title", secondary = true) { scope.launch { controller.setDisplayTitle(session.id, title) } }
    running?.let { StatusText("Running · ${it.message}") }
    PrimaryHeading("Session settings")
    val draft = state.sessionSettingsDraft ?: return
    var replyLengthText by remember(session.id) { mutableStateOf(draft.replyLength.toString()) }
    PrimaryChoiceField("Chat model", draft.modelId, state.modelChoices) { id ->
        controller.editSessionSettings { it.copy(modelId = id) }
    }
    PrimaryChoiceField("Format card", draft.formatCardId, state.formatChoices) { id ->
        controller.editSessionSettings { it.copy(formatCardId = id) }
    }
    PrimaryField("Reply length", replyLengthText) { value ->
        replyLengthText = value
        value.toIntOrNull()?.takeIf { it > 0 }?.let { parsed ->
            controller.editSessionSettings { it.copy(replyLength = parsed) }
        }
    }
    if (replyLengthText.toIntOrNull()?.let { it > 0 } != true) {
        StatusText("Reply length must be a positive number", DesktopBootstrapColors.warning)
    }
    PrimaryField("Reply language", draft.replyLanguage.orEmpty()) { value ->
        controller.editSessionSettings { it.copy(replyLanguage = value.takeIf(String::isNotBlank)) }
    }
    PrimaryField("Supplementary setting", draft.supplementarySetting.orEmpty()) { value ->
        controller.editSessionSettings { it.copy(supplementarySetting = value.takeIf(String::isNotBlank)) }
    }
    PrimaryField("Player name override", draft.playerName.orEmpty()) { value ->
        controller.editSessionSettings { it.copy(playerName = value.takeIf(String::isNotBlank)) }
    }
    PrimaryField("Player setting override", draft.playerSetting.orEmpty()) { value ->
        controller.editSessionSettings { it.copy(playerSetting = value.takeIf(String::isNotBlank)) }
    }
    BootstrapButton("Save session settings", enabled = replyLengthText.toIntOrNull()?.let { it > 0 } == true) {
        scope.launch { controller.saveSessionSettings() }
    }
    StatusText("Prompt Inspector is available in Tools")
}

@Composable
private fun PrimaryChoiceField(
    label: String,
    selectedId: String?,
    choices: List<DesktopPrimaryChoice>,
    onSelect: (String?) -> Unit,
) {
    val selected = choices.firstOrNull { it.id == selectedId }
    StatusText("$label: ${selected?.label ?: selectedId?.let { "Unavailable: $it" } ?: "Follow default"}")
    BootstrapButton("Follow default", secondary = selectedId != null) { onSelect(null) }
    choices.forEach { choice ->
        BootstrapButton(choice.label.take(48), secondary = selectedId != choice.id) { onSelect(choice.id) }
    }
}

@Composable
private fun PrimaryField(label: String, value: String, onChange: (String) -> Unit) {
    StatusText(label)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
            .padding(8.dp),
        textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
    )
}

@Composable
private fun PrimaryHeading(text: String) {
    BasicText(text, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
}
