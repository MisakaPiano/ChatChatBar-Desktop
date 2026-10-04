package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

@Composable
internal fun DesktopPrimaryChatPanel(
    controller: DesktopPrimaryChatController,
    size: DesktopShellSize,
) {
    val t = LocalDesktopUiStrings.current
    val state by controller.state.collectAsState()
    val tasks by controller.taskRuntime.tasks.collectAsState()
    val scope = rememberCoroutineScope()
    val platformClipboard = LocalClipboard.current
    val clipboard = remember(platformClipboard) { DesktopSafeClipboard(platformClipboard) }
    var compactBrowser by remember { mutableStateOf(true) }
    var browser by remember(controller) { mutableStateOf(DesktopPrimaryChatBrowserState()) }
    var renameText by remember { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingText by remember { mutableStateOf("") }
    var deletingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingSegment by remember(state.selectedSession?.id) { mutableStateOf<Pair<ChatMessage, DesktopPresentedSegment>?>(null) }
    var deletingSegment by remember(state.selectedSession?.id) { mutableStateOf<Pair<ChatMessage, DesktopPresentedSegment>?>(null) }
    var segmentText by remember { mutableStateOf("") }
    var diagnosticDisclosure by remember(state.selectedSession?.id) {
        mutableStateOf(DesktopDiagnosticDisclosure(state.selectedSession?.id))
    }
    var relinkOpen by remember { mutableStateOf(false) }
    var relinkCharacterId by remember { mutableStateOf<String?>(null) }
    var worldBookQuery by remember { mutableStateOf("") }
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
        Modifier.fillMaxSize().background(colors.background),
    ) {
        Row(Modifier.fillMaxSize()) {
            if (browser.browserVisible(size, compactBrowser)) {
                Column(
                    Modifier.then(if (size == DesktopShellSize.COMPACT) Modifier.fillMaxSize() else Modifier.width(280.dp))
                        .fillMaxHeight().background(colors.card).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { PrimaryHeading(t(DesktopUiText.SESSIONS)) }
                        DesktopIconAction(t(DesktopUiText.NEW_CHAT), DesktopAppIcons.Add) { browser = browser.openNewChat() }
                        DesktopIconAction(t(DesktopUiText.REFRESH), DesktopAppIcons.Refresh) { scope.launch { controller.refresh() } }
                        if (size != DesktopShellSize.COMPACT) DesktopIconAction(t(DesktopUiText.HIDE_SESSIONS), DesktopAppIcons.Collapse) {
                            browser = browser.toggleWideBrowser()
                        }
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
                            PrimarySessionRow(item, state.selectedSession?.id == item.id, controller,
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
                            PrimarySessionRow(item, state.selectedSession?.id == item.id, controller,
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
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.width(DesktopChatReadingWidth.forAvailableWidth(maxWidth.value).dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val selected = state.selectedSession
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (size == DesktopShellSize.COMPACT || !browser.wideBrowserExpanded) {
                            DesktopIconAction(t(DesktopUiText.SHOW_SESSIONS), DesktopAppIcons.Expand) {
                                if (size == DesktopShellSize.COMPACT) compactBrowser = true else browser = browser.toggleWideBrowser()
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PrimaryHeading(state.sessions.firstOrNull { it.id == selected?.id }?.title
                            ?: selected?.let {
                                desktopRenderedSessionTitle(it, state.selectedCharacter, state.globalPlayerName)
                            } ?: t(DesktopUiText.CHAT))
                            if (selected != null) StatusText(desktopModelSummary(state.modelDiagnostic, t))
                        }
                        if (selected != null) {
                            DesktopIconAction(t(DesktopUiText.MODEL_DETAILS),
                                if (diagnosticDisclosure.expanded) DesktopAppIcons.DetailsOpen else DesktopAppIcons.DetailsClosed) {
                                diagnosticDisclosure = diagnosticDisclosure.toggle(selected.id)
                            }
                            DesktopIconAction(t(DesktopUiText.SESSION_SETTINGS), DesktopAppIcons.Settings) {
                                browser = browser.openSettings(selected.id)
                            }
                        }
                    }
                    if (selected == null) {
                        StatusText(t(DesktopUiText.SELECT_SESSION))
                    } else {
                        if (diagnosticDisclosure.select(selected.id).expanded) DesktopModelEvidence(state.modelDiagnostic, session = true)
                        if (state.selectedCharacterMissing) {
                            StatusText(t(DesktopUiText.ARCHIVED_READABLE), colors.warning)
                            BootstrapButton(t(DesktopUiText.RELINK_CHARACTER), secondary = true) {
                                relinkCharacterId = null
                                relinkOpen = true
                            }
                        }
                        CompositionLocalProvider(LocalClipboard provides clipboard) { key(state.selectedSession?.id) {
                            PrimaryTimeline(
                                state, running, controller, clipboard, Modifier.weight(1f),
                                onEdit = { message -> editingMessage = message; editingText = message.displayContent },
                                onDelete = { deletingMessage = it },
                                onEditSegment = { message, segment -> editingSegment = message to segment; segmentText = segment.source!!.rawText },
                                onDeleteSegment = { message, segment -> deletingSegment = message to segment },
                            )
                        } }
                        if (clipboard.unavailable) StatusText(t(DesktopUiText.CLIPBOARD_UNAVAILABLE), colors.warning)
                        state.configurationMessage?.let { StatusText(t.status(it), colors.warning) }
                        state.error?.let { StatusText(t.status(it), colors.destructive) }
                        tasks.firstOrNull { it.sessionId == selected.id }
                            ?.takeIf { it.operation == DesktopChatOperation.REGENERATE && it.status == DesktopTaskStatus.FAILED }
                            ?.let { StatusText(t.status(it.message), colors.destructive) }
                        state.status?.let { StatusText(t.status(it)) }
                        PrimaryComposer(state, running, controller)
                    }
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
                    PrimaryUtilities(state, controller, worldBookQuery) { worldBookQuery = it }
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
        if (relinkOpen) {
            PrimaryModal(t(DesktopUiText.RELINK_CHARACTER)) {
                StatusText(t(DesktopUiText.RELINK_EXPLANATION))
                if (state.characters.isEmpty()) StatusText(t(DesktopUiText.RELINK_NO_CHARACTERS))
                state.characters.forEach { character ->
                    BootstrapButton(character.label, secondary = relinkCharacterId != character.id) {
                        relinkCharacterId = character.id
                    }
                }
                state.error?.let { StatusText(t.status(it), colors.destructive) }
                ActionRow {
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { relinkOpen = false }
                    BootstrapButton(t(DesktopUiText.CONFIRM_RELINK), enabled = relinkCharacterId != null) {
                        val id = relinkCharacterId ?: return@BootstrapButton
                        scope.launch { if (controller.relinkArchivedSession(id)) relinkOpen = false }
                    }
                }
            }
        }
        editingSegment?.let { (message, segment) ->
            PrimaryModal(t(DesktopUiText.EDIT_SEGMENT)) {
                PrimaryField(t(DesktopUiText.SEGMENT), segmentText) { segmentText = it }
                state.error?.let { StatusText(t.status(it), colors.destructive) }
                ActionRow {
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { editingSegment = null }
                    BootstrapButton(t(DesktopUiText.SAVE)) {
                        scope.launch {
                            val source = segment.source!!
                            if (controller.editMessageSegment(message.id, source.start, source.endExclusive,
                                    segmentText, message.displayContent)) editingSegment = null
                        }
                    }
                }
            }
        }
        deletingSegment?.let { (message, segment) ->
            PrimaryModal(t(DesktopUiText.DELETE_SEGMENT)) {
                StatusText(t(DesktopUiText.DELETE_SEGMENT_WARNING), colors.warning)
                state.error?.let { StatusText(t.status(it), colors.destructive) }
                ActionRow {
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { deletingSegment = null }
                    BootstrapButton(t(DesktopUiText.DELETE_SEGMENT), variant = DesktopActionVariant.DESTRUCTIVE) {
                        scope.launch {
                            val source = segment.source!!
                            if (controller.editMessageSegment(message.id, source.start, source.endExclusive,
                                    "", message.displayContent)) deletingSegment = null
                        }
                    }
                }
            }
        }
        editingMessage?.let { message ->
            PrimaryModal(t(DesktopUiText.EDIT_MESSAGE)) {
                PrimaryField(t(DesktopUiText.MESSAGE), editingText) { editingText = it }
                state.error?.let { StatusText(t.status(it), colors.destructive) }
                ActionRow {
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { editingMessage = null }
                    BootstrapButton(t(DesktopUiText.SAVE), enabled = editingText.isNotBlank() || message.images.isNotEmpty()) {
                        scope.launch { if (controller.editMessage(message.id, editingText)) editingMessage = null }
                    }
                }
            }
        }
        deletingMessage?.let { message ->
            PrimaryModal(t(DesktopUiText.DELETE_MESSAGE_CONFIRM)) {
                StatusText(t(DesktopUiText.DELETE_MESSAGE_WARNING), colors.warning)
                state.error?.let { StatusText(t.status(it), colors.destructive) }
                ActionRow {
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { deletingMessage = null }
                    BootstrapButton(t(DesktopUiText.DELETE), variant = DesktopActionVariant.DESTRUCTIVE) {
                        scope.launch { if (controller.deleteMessage(message.id)) deletingMessage = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryModal(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(DesktopBootstrapColors.overlay).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PrimaryHeading(title)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun PrimarySessionRow(
    item: DesktopPrimarySessionItem,
    selected: Boolean,
    controller: DesktopPrimaryChatController,
    expanded: Boolean,
    preview: String?,
    onSelect: () -> Unit,
    onExpand: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val row = desktopSessionRowPresentation(item, selected, preview)
    val colors = DesktopBootstrapColors
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem(t(if (item.pinned) DesktopUiText.UNPIN else DesktopUiText.PIN), onPin),
            ContextMenuItem(t(DesktopUiText.RENAME), onRename),
            ContextMenuItem(t(DesktopUiText.SESSION_SETTINGS), onSettings),
        )
    }) {
        Row(Modifier.fillMaxWidth().background(if (row.selected) colors.secondary else Color.Transparent, RoundedCornerShape(8.dp))
            .selectable(row.selected, role = Role.Tab, onClick = onSelect).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.clip(CircleShape)) { PrimaryAvatar(row.avatarReference, item.characterName ?: row.title, controller) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(row.title, style = TextStyle(color = colors.foreground, fontSize = 14.sp,
                    fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal))
                if (row.archived) StatusText(t(DesktopUiText.ARCHIVED), colors.warning)
                if (expanded) {
                    item.characterName?.let { StatusText(it) }
                    row.preview?.let { BasicText(it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = colors.mutedForeground, fontSize = 12.sp)) }
                }
            }
            if (row.pinned) Image(rememberVectorPainter(DesktopAppIcons.Pin), t(DesktopUiText.PINNED), Modifier.size(12.dp),
                colorFilter = ColorFilter.tint(colors.mutedForeground))
            DesktopIconAction(t(DesktopUiText.SESSION_SUMMARY),
                if (expanded) DesktopAppIcons.DetailsOpen else DesktopAppIcons.DetailsClosed, onClick = onExpand)
        }
    }
}

@Composable
private fun PrimaryTimeline(
    state: DesktopPrimaryChatState,
    running: DesktopTaskEntry?,
    controller: DesktopPrimaryChatController,
    clipboard: DesktopSafeClipboard,
    modifier: Modifier = Modifier,
    onEdit: (ChatMessage) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onEditSegment: (ChatMessage, DesktopPresentedSegment) -> Unit,
    onDeleteSegment: (ChatMessage, DesktopPresentedSegment) -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val visibleMessages = desktopVisibleMessages(state.messages, running)
    val mapping = remember(state.messages, visibleMessages, state.hasOlderMessages, state.hasNewerMessages, running?.taskId) {
        desktopChatTimelineMapping(state, running)
    }
    val viewport = rememberDesktopChatViewport(state, mapping, controller, running?.contentPreview to running?.reasoningPreview)
    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize().alpha(if (viewport.ready) 1f else 0f)
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(8.dp),
            state = viewport.list,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.hasOlderMessages) item(key = "older") {
                BootstrapButton("${t(DesktopUiText.LOAD_OLDER)} · ${state.totalMessageCount} ${t(DesktopUiText.TOTAL)}", secondary = true) {
                    scope.launch { viewport.loadAdjacent(controller, older = true) }
                }
            }
            items(visibleMessages, key = ChatMessage::id) { message ->
                val presented = desktopPresentMessage(
                    message, state.selectedCharacter, state.globalPlayerName,
                    state.assistantSegmentedBubblesEnabled,
                    DesktopRoleLabels(t(DesktopUiText.ASSISTANT_ROLE), t(DesktopUiText.YOU_ROLE),
                        t(DesktopUiText.SYSTEM_ROLE)),
                    session = state.selectedSession,
                )
                val actions = desktopMessageActions(state.messages, message, running)
                val perform: (DesktopMessageAction) -> Unit = { action ->
                    when (action) {
                        DesktopMessageAction.COPY -> { scope.launch { clipboard.copyText(presented.copyText) }; Unit }
                        DesktopMessageAction.EDIT -> onEdit(message)
                        DesktopMessageAction.DELETE -> onDelete(message)
                        DesktopMessageAction.REGENERATE, DesktopMessageAction.RETRY ->
                            scope.launch { controller.regenerate(message.id) }
                    }
                }
                PrimaryMessageBubble(message, state, controller, clipboard, actions, perform,
                    onEditSegment = { onEditSegment(message, it) },
                    onDeleteSegment = { onDeleteSegment(message, it) })
            }
            if (state.hasNewerMessages) item(key = "newer") {
                BootstrapButton(t(DesktopUiText.LOAD_NEWER), secondary = true) { scope.launch { viewport.loadAdjacent(controller, older = false) } }
            }
            if (running != null) item(key = "stream:${running.taskId}") {
                val streamingMessage = remember(running.taskId, running.contentPreview, running.reasoningPreview) {
                    desktopStreamingMessage(
                        running.sessionId.orEmpty(), running.taskId,
                        running.contentPreview, running.reasoningPreview,
                    )
                }
                PrimaryMessageBubble(streamingMessage, state, controller, clipboard)
            }
        }
        if (state.readingPositionError) Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            StatusText(t(DesktopUiText.READING_POSITION_ERROR))
        }
        val canEarlier = viewport.canEarlier()
        val canLater = viewport.canLater(state.messageWindowAnchorId)
        if (viewport.ready && (canEarlier || canLater)) DesktopChatNavigationSurface(
            Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp),
        ) {
            if (canEarlier) DesktopChatIconAction(t(DesktopUiText.PREVIOUS_MESSAGE), DesktopAppIcons.MessagePrevious,
                enabled = !viewport.restoring, targetDp = 48) {
                scope.launch { viewport.navigate(controller, DesktopChatJump.PREVIOUS) }
            }
            if (canLater) DesktopChatIconAction(t(DesktopUiText.NEXT_MESSAGE), DesktopAppIcons.MessageNext,
                enabled = !viewport.restoring, targetDp = 48) {
                scope.launch { viewport.navigate(controller, DesktopChatJump.NEXT) }
            }
            if (canEarlier) DesktopChatIconAction(t(DesktopUiText.FIRST_MESSAGE), DesktopAppIcons.MessageFirst,
                enabled = !viewport.restoring, targetDp = 48) {
                scope.launch { viewport.navigate(controller, DesktopChatJump.FIRST) }
            }
        }
        if (viewport.ready && mapping.keys.isNotEmpty() && !viewport.atBottom()) DesktopChatNavigationSurface(
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            DesktopChatIconAction(t(DesktopUiText.JUMP_BOTTOM), DesktopAppIcons.JumpBottom,
                enabled = !viewport.restoring, targetDp = 48) {
                scope.launch { viewport.navigate(controller, DesktopChatJump.BOTTOM) }
            }
        }
    }
}

@Composable
private fun PrimaryMessageBubble(
    message: ChatMessage,
    state: DesktopPrimaryChatState,
    controller: DesktopPrimaryChatController,
    clipboard: DesktopSafeClipboard,
    actions: List<DesktopMessageAction> = emptyList(),
    onAction: (DesktopMessageAction) -> Unit = {},
    onEditSegment: (DesktopPresentedSegment) -> Unit = {},
    onDeleteSegment: (DesktopPresentedSegment) -> Unit = {},
) {
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val presented = remember(
        message, state.selectedCharacter, state.selectedSession, state.globalPlayerName,
        state.assistantSegmentedBubblesEnabled, t,
    ) {
        desktopPresentMessage(
            message, state.selectedCharacter, state.globalPlayerName,
            state.assistantSegmentedBubblesEnabled,
            DesktopRoleLabels(t(DesktopUiText.ASSISTANT_ROLE), t(DesktopUiText.YOU_ROLE),
                t(DesktopUiText.SYSTEM_ROLE)),
            session = state.selectedSession,
        )
    }
    val colors = DesktopBootstrapColors
    Column(
        Modifier.fillMaxWidth()
            .then(if (presented.enclosingCard) Modifier
                .background(if (message.role == MessageRole.USER) colors.muted else colors.card, RoundedCornerShape(8.dp))
                .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(10.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (presented.showWholeMessageHeader) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryAvatar(
                state.selectedCharacter?.avatar.takeIf { message.role == MessageRole.ASSISTANT },
                presented.speakerLabel,
                controller,
            )
            StatusText(presented.speakerLabel)
        }
        presented.reasoning?.let { reasoning ->
            val expansion = remember(message.id, message.currentAlternativeIndex) {
                DesktopPresentationExpansion(presented.defaultReasoningExpanded)
            }
            DesktopChatDisclosure(t(DesktopUiText.REASONING), expansion)
            if (expansion.expanded) SelectionContainer {
                DesktopMarkdownText(reasoning, colors.mutedForeground)
            }
        }
        presented.segments.forEachIndexed { index, segment ->
            val segmentColor = when (desktopSegmentSurface(segment)) {
                DesktopSegmentSurface.DIALOGUE -> colors.muted
                DesktopSegmentSurface.THOUGHT -> colors.input
                DesktopSegmentSurface.STATUS -> colors.card
                DesktopSegmentSurface.NARRATION -> Color.Transparent
            }
            ContextMenuDataProvider(items = {
                if (!presented.segmented || segment.source == null || actions.isEmpty()) emptyList()
                else buildList {
                    add(ContextMenuItem(t(DesktopUiText.COPY_SEGMENT)) { scope.launch { clipboard.copyText(segment.text) } })
                    if (DesktopMessageAction.EDIT in actions) add(ContextMenuItem(t(DesktopUiText.EDIT_SEGMENT)) { onEditSegment(segment) })
                    if (DesktopMessageAction.DELETE in actions) add(ContextMenuItem(t(DesktopUiText.DELETE_SEGMENT)) { onDeleteSegment(segment) })
                }
            }) {
                Column(
                    Modifier.fillMaxWidth(if (presented.segmented &&
                        (segment.kind == RoleplaySegmentKind.DIALOGUE || segment.kind == RoleplaySegmentKind.THOUGHT)) 0.9f else 1f)
                        .then(if (presented.segmented || segment.kind == RoleplaySegmentKind.STATUS) {
                            Modifier.background(segmentColor, RoundedCornerShape(8.dp)).padding(6.dp)
                        } else Modifier),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val showSpeaker = presented.segmented && index in presented.speakerHeaderIndexes
                    if (showSpeaker) {
                        val speaker = segment.speaker
                        val speakerName = when (speaker?.displayName) {
                            "未标注" -> t(DesktopUiText.UNLABELED_SPEAKER)
                            null -> presented.speakerLabel
                            else -> speaker.displayName.orEmpty()
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PrimaryAvatar(
                                desktopPresentedAvatarReference(speaker, state.selectedCharacter),
                                speakerName,
                                controller,
                            )
                            StatusText(speakerName)
                        }
                    }
                    if (segment.kind == RoleplaySegmentKind.STATUS) {
                        val expansion = remember(message.id, message.currentAlternativeIndex, index) {
                            DesktopPresentationExpansion(segment.statusDefaultExpanded)
                        }
                        DesktopChatDisclosure(t(DesktopUiText.STATUS_OPTIONS), expansion)
                        if (expansion.expanded) SelectionContainer {
                            DesktopMarkdownText(segment.text, typography = DesktopChatTypography(state.chatBubbleFontScale))
                        }
                    } else if (segment.text.isNotBlank()) {
                        SelectionContainer {
                            DesktopMarkdownText(segment.text, typography = DesktopChatTypography(state.chatBubbleFontScale))
                        }
                    }
                }
            }
        }
        // Only action chrome is condensed; content and segment spacing above stays unchanged.
        val navigation = desktopAlternativeNavigation(message, state.alternativeEligibleIds)
        if (navigation != null || actions.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(DesktopChatControlDensity.GAP_DP.dp)) {
            navigation?.let {
                DesktopChatAlternativeControls(navigation) { delta ->
                    scope.launch { controller.selectAssistantAlternative(message.id, delta) }
                }
            }
            if (actions.isNotEmpty()) DesktopChatMessageToolbar(actions, onAction)
        }
    }
}

@Composable
private fun PrimaryAvatar(reference: String?, fallbackName: String, controller: DesktopPrimaryChatController) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, reference, controller) {
        value = reference?.let { ref ->
            withContext(Dispatchers.IO) {
                runCatching {
                    SkiaImage.makeFromEncoded(controller.characterResources.readBytes(ref)).toComposeImageBitmap()
                }.getOrNull()
            }
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(loaded, fallbackName, Modifier.size(30.dp), contentScale = ContentScale.Crop)
    } else {
        Box(
            Modifier.size(30.dp).background(DesktopBootstrapColors.muted, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(fallbackName.trim().take(1).ifBlank { "?" },
                style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 13.sp))
        }
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
        if (desktopComposerAction(running != null) == DesktopComposerAction.SEND) BootstrapButton(t(DesktopUiText.SEND), enabled = canLaunch && state.composerDraft.isNotBlank()) {
            scope.launch { controller.send() }
        }
        if (running != null) BootstrapButton(t(DesktopUiText.STOP), secondary = true) { controller.stop(running.taskId) }
    }
    running?.let { StatusText("${t(DesktopUiText.TASK)}: ${t.status(it.message)}") }
}

@Composable
private fun PrimaryUtilities(
    state: DesktopPrimaryChatState,
    controller: DesktopPrimaryChatController,
    worldBookQuery: String,
    onWorldBookQuery: (String) -> Unit,
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
    PrimaryHeading(t(DesktopUiText.SESSION_WORLD_BOOKS))
    StatusText(t(DesktopUiText.WORLD_BOOK_INHERITED_NOTE))
    PrimaryField(t(DesktopUiText.SEARCH_WORLD_BOOKS), worldBookQuery, onWorldBookQuery)
    val inheritedIds = state.selectedCharacter?.worldBookIds.orEmpty()
    val availableIds = state.worldBookChoices.mapTo(mutableSetOf(), DesktopPrimaryChoice::id)
    state.worldBookChoices.filter { it.label.contains(worldBookQuery.trim(), ignoreCase = true) }
        .forEach { book ->
            val inherited = book.id in inheritedIds
            val extra = book.id in draft.extraWorldBookIds
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                StatusText("${book.label}${if (inherited) " · ${t(DesktopUiText.CHARACTER_INHERITED)}" else ""}")
                BootstrapButton(t(if (inherited || extra) DesktopUiText.SELECTED else DesktopUiText.ADD),
                    secondary = true, enabled = !inherited) {
                    controller.editSessionSettings { current ->
                        current.copy(extraWorldBookIds = if (extra) current.extraWorldBookIds - book.id
                            else (current.extraWorldBookIds + book.id).distinct())
                    }
                }
            }
        }
    inheritedIds.filterNot(availableIds::contains).forEach { id ->
        StatusText("${t(DesktopUiText.INHERITED_WORLD_BOOK_UNAVAILABLE)}: $id", DesktopBootstrapColors.warning)
    }
    draft.extraWorldBookIds.filterNot(availableIds::contains).forEach { id ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusText("${t(DesktopUiText.EXTRA_WORLD_BOOK_UNAVAILABLE)}: $id", DesktopBootstrapColors.warning)
            BootstrapButton(t(DesktopUiText.REMOVE), secondary = true) {
                controller.editSessionSettings { current ->
                    current.copy(extraWorldBookIds = current.extraWorldBookIds - id)
                }
            }
        }
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
