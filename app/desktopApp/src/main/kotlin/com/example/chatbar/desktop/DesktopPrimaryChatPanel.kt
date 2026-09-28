package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
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
    val t = LocalDesktopUiStrings.current
    val state by controller.state.collectAsState()
    val tasks by controller.taskRuntime.tasks.collectAsState()
    val scope = rememberCoroutineScope()
    var compactBrowser by remember { mutableStateOf(true) }
    var browser by remember(controller) { mutableStateOf(DesktopPrimaryChatBrowserState()) }
    var renameText by remember { mutableStateOf("") }
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
                    PrimaryHeading(t(DesktopUiText.SESSIONS))
                    ActionRow {
                        BootstrapButton(t(DesktopUiText.NEW_CHAT)) { browser = browser.openNewChat() }
                        BootstrapButton(t(DesktopUiText.REFRESH), secondary = true) { scope.launch { controller.refresh() } }
                    }
                    PrimaryField(t(DesktopUiText.SEARCH_SESSIONS), state.sessionQuery) { query ->
                        scope.launch { controller.searchSessions(query) }
                    }
                    state.configurationMessage?.takeIf { !state.modelUsable && state.selectedSession == null && it != "Select or create a session" }
                        ?.let { StatusText(t.status(it), colors.warning) }
                    state.error?.let { StatusText(t.status(it), colors.destructive) }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.sessions.isEmpty()) item {
                            StatusText(t(if (state.sessionQuery.isBlank()) DesktopUiText.NO_SESSIONS
                                else DesktopUiText.NO_MATCHING_SESSIONS))
                        }
                        val pinned = state.sessions.filter { it.pinned }
                        val recent = state.sessions.filterNot { it.pinned }
                        if (pinned.isNotEmpty()) item { PrimaryHeading(t(DesktopUiText.PINNED)) }
                        items(pinned, key = { "pinned:${it.id}" }) { item ->
                            PrimarySessionRow(item, state.selectedSession?.id == item.id,
                                expanded = browser.expandedSessionId == item.id,
                                preview = browser.visiblePreview(item),
                                onSelect = { scope.launch { controller.selectSession(item.id); compactBrowser = false } },
                                onExpand = { browser = browser.toggleSummary(item.id) },
                                onPin = { scope.launch { controller.togglePin(item.id) } },
                                onRename = { browser = browser.copy(renameSessionId = item.id); renameText = item.displayTitleOverride.orEmpty() },
                                onSettings = { scope.launch { controller.selectSession(item.id); browser = browser.openSettings(item.id); compactBrowser = false } },
                            )
                        }
                        if (recent.isNotEmpty()) item { PrimaryHeading(t(DesktopUiText.RECENT_SESSIONS)) }
                        items(recent, key = { "recent:${it.id}" }) { item ->
                            PrimarySessionRow(item, state.selectedSession?.id == item.id,
                                expanded = browser.expandedSessionId == item.id,
                                preview = browser.visiblePreview(item),
                                onSelect = { scope.launch { controller.selectSession(item.id); compactBrowser = false } },
                                onExpand = { browser = browser.toggleSummary(item.id) },
                                onPin = { scope.launch { controller.togglePin(item.id) } },
                                onRename = { browser = browser.copy(renameSessionId = item.id); renameText = item.displayTitleOverride.orEmpty() },
                                onSettings = { scope.launch { controller.selectSession(item.id); browser = browser.openSettings(item.id); compactBrowser = false } },
                            )
                        }
                    }
                }
            }
            if (size != DesktopShellSize.COMPACT || !compactBrowser) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (size == DesktopShellSize.COMPACT) {
                        BootstrapButton("← ${t(DesktopUiText.SESSIONS)}", secondary = true) { compactBrowser = true }
                    }
                    val selected = state.selectedSession
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        PrimaryHeading(state.sessions.firstOrNull { it.id == selected?.id }?.title
                            ?: selected?.displayTitleOverride?.takeIf(String::isNotBlank)
                            ?: selected?.title ?: t(DesktopUiText.CHAT))
                        if (selected != null) BootstrapButton(t(DesktopUiText.SESSION_SETTINGS), secondary = true) {
                            browser = browser.openSettings(selected.id)
                        }
                    }
                    if (selected == null) {
                        StatusText(t(DesktopUiText.SELECT_SESSION))
                    } else {
                        DesktopModelEvidence(state.modelDiagnostic, session = true)
                        if (state.selectedCharacterMissing) StatusText(t(DesktopUiText.ARCHIVED_READABLE), colors.warning)
                        PrimaryTimeline(state, running, controller, Modifier.weight(1f))
                        state.configurationMessage?.let { StatusText(t.status(it), colors.warning) }
                        state.error?.let { StatusText(t.status(it), colors.destructive) }
                        state.status?.let { StatusText(t.status(it)) }
                        PrimaryComposer(state, running, controller)
                    }
                }
            }
        }
        if (browser.settingsSessionId == state.selectedSession?.id && browser.settingsSessionId != null) {
            Column(
                Modifier.fillMaxSize().background(colors.overlay).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PrimaryHeading(t(DesktopUiText.SESSION_SETTINGS))
                    BootstrapButton(t(DesktopUiText.CLOSE), variant = DesktopActionVariant.GHOST) {
                        scope.launch { controller.requestSessionSettingsLeave {
                            browser = browser.copy(settingsSessionId = null)
                        } }
                    }
                }
                if (state.sessionSettingsDirty) StatusText("● ${t(DesktopUiText.UNSAVED_CHANGES)}", colors.warning)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryUtilities(state, controller)
                }
                ActionRow {
                    BootstrapButton(t(DesktopUiText.CANCEL), variant = DesktopActionVariant.GHOST) {
                        scope.launch { controller.requestSessionSettingsLeave {
                            browser = browser.copy(settingsSessionId = null)
                        } }
                    }
                    BootstrapButton(t(DesktopUiText.SAVE_CHANGES), enabled = state.sessionSettingsDirty &&
                        state.sessionReplyLengthInput.toIntOrNull()?.let { it > 0 } == true) {
                        scope.launch { controller.saveSessionSettings() }
                    }
                }
            }
        }
        if (state.sessionSettingsLeavePrompt) {
            Box(Modifier.fillMaxSize().background(colors.dim).padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Column(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(12.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryHeading(t(DesktopUiText.UNSAVED_CHANGES))
                    ActionRow {
                        BootstrapButton(t(DesktopUiText.SAVE_AND_LEAVE)) {
                            scope.launch { controller.resolveSessionSettingsLeave(save = true) }
                        }
                        BootstrapButton(t(DesktopUiText.DISCARD_CHANGES), variant = DesktopActionVariant.DESTRUCTIVE) {
                            scope.launch { controller.resolveSessionSettingsLeave(save = false) }
                        }
                        BootstrapButton(t(DesktopUiText.CONTINUE_EDITING), variant = DesktopActionVariant.GHOST) {
                            controller.continueSessionSettingsEditing()
                        }
                    }
                }
            }
        }
        if (browser.renameSessionId != null) {
            Column(Modifier.fillMaxSize().background(colors.overlay).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryHeading(t(DesktopUiText.RENAME))
                PrimaryField(t(DesktopUiText.DISPLAY_TITLE), renameText) { renameText = it }
                ActionRow {
                    BootstrapButton(t(DesktopUiText.SAVE)) {
                        val id = browser.renameSessionId ?: return@BootstrapButton
                        scope.launch { controller.setDisplayTitle(id, renameText); browser = browser.copy(renameSessionId = null) }
                    }
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { browser = browser.copy(renameSessionId = null) }
                }
            }
        }
        if (browser.newChatOpen) {
            Column(Modifier.fillMaxSize().background(colors.overlay).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionRow {
                    PrimaryHeading(t(DesktopUiText.NEW_CHAT))
                    BootstrapButton(t(DesktopUiText.CLOSE), secondary = true) { browser = browser.closeNewChat() }
                }
                PrimaryField(t(DesktopUiText.SEARCH_CHARACTERS), browser.characterQuery) {
                    browser = browser.copy(characterQuery = it)
                }
                val matches = browser.filteredCharacters(state.characters)
                if (matches.isEmpty()) StatusText(t(if (state.characters.isEmpty()) DesktopUiText.NO_CHARACTERS else DesktopUiText.NO_MATCHING_CHARACTERS))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(matches, key = DesktopPrimaryChoice::id) { character ->
                        BootstrapButton(character.label, secondary = true) {
                            scope.launch {
                                val before = controller.state.value.sessions.map { it.id }.toSet()
                                controller.createSession(character.id)
                                if (controller.state.value.selectedSession?.id !in before) {
                                    browser = browser.closeNewChat()
                                    compactBrowser = false
                                }
                            }
                        }
                    }
                }
                state.error?.let { StatusText(t.status(it), colors.destructive) }
            }
        }
    }
}

@Composable
private fun PrimarySessionRow(
    item: DesktopPrimarySessionItem,
    selected: Boolean,
    expanded: Boolean,
    preview: String?,
    onSelect: () -> Unit,
    onExpand: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem(t(if (item.pinned) DesktopUiText.UNPIN else DesktopUiText.PIN), onPin),
            ContextMenuItem(t(DesktopUiText.RENAME), onRename),
            ContextMenuItem(t(DesktopUiText.SESSION_SETTINGS), onSettings),
        )
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BootstrapButton(if (expanded) "▾" else "▸", variant = DesktopActionVariant.GHOST, onClick = onExpand)
                BootstrapButton("${if (item.pinned) "● " else ""}${item.title.take(42)}", secondary = !selected, onClick = onSelect)
            }
            if (item.characterMissing) StatusText(t(DesktopUiText.ARCHIVED), DesktopBootstrapColors.warning)
            if (expanded) {
                item.characterName?.let { StatusText(it) }
                preview?.let { StatusText(it.take(100)) }
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
    val t = LocalDesktopUiStrings.current
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
            BootstrapButton("${t(DesktopUiText.LOAD_OLDER)} · ${state.totalMessageCount} ${t(DesktopUiText.TOTAL)}", secondary = true) {
                scope.launch { controller.loadOlder() }
            }
        }
        items(state.messages, key = ChatMessage::id) { message -> PrimaryMessageBubble(message) }
        if (running != null) item(key = "stream:${running.taskId}") {
            PrimaryBubble("ASSISTANT · ${t(DesktopUiText.GENERATING)}", running.contentPreview, running.reasoningPreview)
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
    val t = LocalDesktopUiStrings.current
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
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
            .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp)).padding(10.dp)
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
    StatusText(t(DesktopUiText.COMPOSER_HINT))
    ActionRow {
        BootstrapButton(t(DesktopUiText.SEND), enabled = canLaunch && state.composerDraft.isNotBlank()) {
            scope.launch { controller.send() }
        }
        BootstrapButton(t(DesktopUiText.CONTINUE), enabled = canLaunch, secondary = true) {
            scope.launch { controller.continueReply() }
        }
        if (running != null) BootstrapButton(t(DesktopUiText.STOP), secondary = true) { controller.stop(running.taskId) }
    }
    running?.let { StatusText("${t(DesktopUiText.TASK)}: ${it.message}") }
}

@Composable
private fun PrimaryUtilities(
    state: DesktopPrimaryChatState,
    controller: DesktopPrimaryChatController,
) {
    val scope = rememberCoroutineScope()
    val t = LocalDesktopUiStrings.current
    val session = state.selectedSession
    if (session == null) {
        StatusText(t(DesktopUiText.SELECT_SESSION))
        return
    }
    val draft = state.sessionSettingsDraft ?: return
    PrimaryChoiceField(t(DesktopUiText.CHAT_MODEL), draft.modelId, state.modelChoices) { id ->
        controller.editSessionSettings { it.copy(modelId = id) }
    }
    PrimaryChoiceField(t(DesktopUiText.FORMAT_CARD), draft.formatCardId, state.formatChoices) { id ->
        controller.editSessionSettings { it.copy(formatCardId = id) }
    }
    PrimaryField(t(DesktopUiText.REPLY_LENGTH), state.sessionReplyLengthInput) { value ->
        controller.editSessionReplyLengthInput(value)
    }
    if (state.sessionReplyLengthInput.toIntOrNull()?.let { it > 0 } != true) {
        StatusText(t(DesktopUiText.REPLY_LENGTH_POSITIVE), DesktopBootstrapColors.warning)
    }
    PrimaryField(t(DesktopUiText.REPLY_LANGUAGE), draft.replyLanguage.orEmpty()) { value ->
        controller.editSessionSettings { it.copy(replyLanguage = value.takeIf(String::isNotBlank)) }
    }
    PrimaryField(t(DesktopUiText.SUPPLEMENTARY_SETTING), draft.supplementarySetting.orEmpty()) { value ->
        controller.editSessionSettings { it.copy(supplementarySetting = value.takeIf(String::isNotBlank)) }
    }
    PrimaryField(t(DesktopUiText.PLAYER_NAME_OVERRIDE), draft.playerName.orEmpty()) { value ->
        controller.editSessionSettings { it.copy(playerName = value.takeIf(String::isNotBlank)) }
    }
    PrimaryField(t(DesktopUiText.PLAYER_SETTING_OVERRIDE), draft.playerSetting.orEmpty()) { value ->
        controller.editSessionSettings { it.copy(playerSetting = value.takeIf(String::isNotBlank)) }
    }
}

@Composable
private fun PrimaryChoiceField(
    label: String,
    selectedId: String?,
    choices: List<DesktopPrimaryChoice>,
    onSelect: (String?) -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val selected = choices.firstOrNull { it.id == selectedId }
    StatusText("$label: ${selected?.label ?: selectedId?.let { "${t(DesktopUiText.UNAVAILABLE)}: $it" } ?: t(DesktopUiText.FOLLOW_DEFAULT)}")
    BootstrapButton(t(DesktopUiText.FOLLOW_DEFAULT), secondary = selectedId != null) { onSelect(null) }
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
            .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp)).padding(8.dp),
        textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
    )
}

@Composable
private fun PrimaryHeading(text: String) {
    BasicText(text, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
}
