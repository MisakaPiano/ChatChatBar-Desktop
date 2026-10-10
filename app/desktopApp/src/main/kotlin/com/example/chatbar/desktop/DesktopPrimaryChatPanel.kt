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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    composerLayout: DesktopComposerLayoutState,
    onOpenStudio: () -> Unit = {},
    compactNavigation: DesktopCompactChatNavigation = remember { DesktopCompactChatNavigation() },
) {
    val t = LocalDesktopUiStrings.current
    val state by controller.state.collectAsState()
    val tasks by controller.taskRuntime.tasks.collectAsState()
    val scope = rememberCoroutineScope()
    val platformClipboard = LocalClipboard.current
    val clipboard = remember(platformClipboard) { DesktopSafeClipboard(platformClipboard) }
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
    var backgroundOpen by remember { mutableStateOf(false) }
    var settingsTab by remember(state.selectedSession?.id) { mutableStateOf(DesktopSessionSettingsTab.BASIC) }
    var relinkOpen by remember { mutableStateOf(false) }
    var relinkCharacterId by remember { mutableStateOf<String?>(null) }
    var worldBookQuery by remember { mutableStateOf("") }
    val terminalTasks = tasks.filter { it.status != DesktopTaskStatus.RUNNING }
    val terminalSignature = terminalTasks.joinToString("|") { "${it.taskId}:${it.completedAt}" }
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(size, state.selectedSession?.id) {
        compactNavigation.onWideChatDisplayed(size, state.selectedSession?.id)
    }
    LaunchedEffect(terminalSignature) {
        terminalTasks.forEach { task -> task.sessionId?.let { controller.refreshAfterTerminalTask(it) } }
    }
    val running = tasks.firstOrNull {
        it.kind == DesktopTaskKind.REAL_CHAT && it.sessionId == state.selectedSession?.id && it.status == DesktopTaskStatus.RUNNING
    }
    val composer = rememberDesktopComposerInput(state.selectedSession?.id, state.composerDraft)
    composer.hasAttachments = state.pendingImages.isNotEmpty()
    val canLaunch = state.modelUsable && !state.selectedCharacterMissing && running == null
    // Workspace ownership survives either presentation closing; existing controller accepts/rejects Send.
    val send: () -> Unit = {
        scope.launch {
            if (controller.state.value.selectedSession?.id == composer.sessionId)
                composer.send(canLaunch, controller::send)
        }
        Unit
    }
    val colors = DesktopBootstrapColors

    fun selectFromBrowser(id: String, openSettings: Boolean = false) {
        scope.launch {
            suspend fun select() {
                controller.selectSession(id)
                if (compactNavigation.onSessionEntered(id, controller.state.value) && openSettings)
                    browser = browser.openSettings(id)
            }
            val current = controller.state.value
            if (current.sessionSettingsDirty && current.selectedSession?.id != id)
                controller.requestSessionSettingsLeave { select() }
            else select()
        }
    }

    fun toggleWideBrowser() {
        browser = browser.toggleWideBrowser()
        compactNavigation.onWideBrowserToggled(browser.wideBrowserExpanded)
    }

    Box(
        Modifier.fillMaxSize().background(colors.background),
    ) {
        Row(Modifier.fillMaxSize()) {
            if (browser.browserVisible(size, compactNavigation.browserVisible(size, state.selectedSession?.id))) {
                Column(
                    Modifier.then(if (size == DesktopShellSize.COMPACT) Modifier.fillMaxSize() else Modifier.width(280.dp))
                        .fillMaxHeight().background(colors.card).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { PrimaryHeading(t(DesktopUiText.SESSIONS)) }
                        DesktopIconAction(t(DesktopUiText.NEW_CHAT), DesktopAppIcons.Add) { browser = browser.openNewChat() }
                        DesktopIconAction(t(DesktopUiText.REFRESH), DesktopAppIcons.Refresh) { scope.launch { controller.refresh() } }
                        if (size == DesktopShellSize.COMPACT && compactNavigation.enteredChat && state.selectedSession != null)
                            DesktopIconAction(t(DesktopUiText.HIDE_SESSIONS), DesktopAppIcons.Collapse) {
                                compactNavigation.returnToChat(state.selectedSession?.id)
                            }
                        if (size != DesktopShellSize.COMPACT) DesktopIconAction(t(DesktopUiText.HIDE_SESSIONS), DesktopAppIcons.Collapse) {
                            toggleWideBrowser()
                        }
                    }
                    PrimaryField(t(DesktopUiText.SEARCH_SESSIONS), state.sessionQuery) { query ->
                        scope.launch { controller.searchSessions(query) }
                    }
                    state.configurationMessage?.takeIf { !state.modelUsable && state.selectedSession == null && it != "Select or create a session" }
                        ?.let { StatusText(t.status(it), colors.warning) }
                    if (state.selectedSession == null) state.error?.let { StatusText(t.status(it), colors.destructive) }
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
                                onSelect = { selectFromBrowser(item.id) },
                                onExpand = { browser = browser.toggleSummary(item.id) },
                                onPin = { scope.launch { controller.togglePin(item.id) } },
                                onRename = { browser = browser.copy(renameSessionId = item.id); renameText = item.displayTitleOverride.orEmpty() },
                                onSettings = { selectFromBrowser(item.id, openSettings = true) },
                            )
                        }
                        if (recent.isNotEmpty()) item { PrimaryHeading(t(DesktopUiText.RECENT_SESSIONS)) }
                        items(recent, key = { "recent:${it.id}" }) { item ->
                            PrimarySessionRow(item, state.selectedSession?.id == item.id, controller,
                                expanded = browser.expandedSessionId == item.id,
                                preview = browser.visiblePreview(item),
                                onSelect = { selectFromBrowser(item.id) },
                                onExpand = { browser = browser.toggleSummary(item.id) },
                                onPin = { scope.launch { controller.togglePin(item.id) } },
                                onRename = { browser = browser.copy(renameSessionId = item.id); renameText = item.displayTitleOverride.orEmpty() },
                                onSettings = { selectFromBrowser(item.id, openSettings = true) },
                            )
                        }
                    }
                }
            }
            if (compactNavigation.chatVisible(size, state.selectedSession?.id)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                val placement = DesktopChatNavigationPlacementPolicy.resolve(maxWidth.value)
                val workspaceHeightDp = maxHeight.value
                LaunchedEffect(workspaceHeightDp) { composerLayout.clamp(workspaceHeightDp) }
                Column(Modifier.width(placement.workspaceWidthDp.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val selected = state.selectedSession
                    Column(Modifier.width(placement.contentWidthDp.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (size == DesktopShellSize.COMPACT || !browser.wideBrowserExpanded) {
                                DesktopIconAction(t(DesktopUiText.SHOW_SESSIONS), DesktopAppIcons.Expand) {
                                    if (size == DesktopShellSize.COMPACT) compactNavigation.openBrowser() else toggleWideBrowser()
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
                        if (selected != null) {
                            if (diagnosticDisclosure.select(selected.id).expanded) DesktopModelEvidence(state.modelDiagnostic, session = true)
                            if (state.selectedCharacterMissing) {
                                StatusText(t(DesktopUiText.ARCHIVED_READABLE), colors.warning)
                                BootstrapButton(t(DesktopUiText.RELINK_CHARACTER), secondary = true) {
                                    relinkCharacterId = null
                                    relinkOpen = true
                                }
                            }
                        }
                    }
                    if (selected == null) {
                        StatusText(t(DesktopUiText.SELECT_SESSION))
                    } else {
                        CompositionLocalProvider(LocalClipboard provides clipboard) { key(state.selectedSession?.id) {
                            PrimaryTimeline(
                                state, running, controller, clipboard, placement, Modifier.weight(1f),
                                onEdit = { message -> editingMessage = message; editingText = message.displayContent },
                                onDelete = { deletingMessage = it },
                                onEditSegment = { message, segment -> editingSegment = message to segment; segmentText = segment.source!!.rawText },
                                onDeleteSegment = { message, segment -> deletingSegment = message to segment },
                            )
                        } }
                        Column(Modifier.width(placement.contentWidthDp.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (clipboard.unavailable) StatusText(t(DesktopUiText.CLIPBOARD_UNAVAILABLE), colors.warning)
                            state.configurationMessage?.let { StatusText(t.status(it), colors.warning) }
                            state.error?.let { StatusText(t.status(it), colors.destructive) }
                            tasks.firstOrNull { it.sessionId == selected.id }
                                ?.takeIf { it.operation == DesktopChatOperation.REGENERATE && it.status == DesktopTaskStatus.FAILED }
                                ?.let { StatusText(t.status(it.message), colors.destructive) }
                            state.status?.let { StatusText(t.status(it)) }
                            tasks.firstOrNull { it.sessionId == selected.id && it.kind == DesktopTaskKind.NOVELAI && it.targetMessageId == null }?.let { imageTask ->
                                StatusText(imageTask.message)
                                if (imageTask.status == DesktopTaskStatus.RUNNING) BootstrapButton("停止图片生成") { controller.stop(imageTask.taskId) }
                            }
                            tasks.firstOrNull { it.sessionId == selected.id && it.kind == DesktopTaskKind.REAL_CHAT }
                                ?.message?.takeIf { it.contains("自动生图已跳过") || it.contains("自动生图未启动") }?.let { StatusText(it) }
                            PrimaryComposer(composer, canLaunch, running, controller, composerLayout, workspaceHeightDp, send)

                        }
                    }
                }
                }
            }
        }
        if (backgroundOpen) DesktopChatBackgroundPanel(controller) { backgroundOpen = false }
        if (composer.expanded) {
            DesktopFullComposer(composer, canLaunch, state.configurationMessage, state.error,
                onDraft = controller::editComposer, onSend = send,
                attachments = { DesktopPendingImageStrip(state.pendingImages, canLaunch,
                    onRemove = { scope.launch { controller.removePendingImage(it) } }, onPick = {}, showPicker = false,
                    onReorder = { id, to -> scope.launch { controller.reorderPendingImage(id, to) } }) },
                onPickImage = { scope.launch { controller.pickImage() } },
                ingressModifier = Modifier.desktopImageIngress(canLaunch, controller::imageIngressFailure) { input -> scope.launch { controller.receiveImages(input) } })
        }
        if (browser.settingsSessionId == state.selectedSession?.id && browser.settingsSessionId != null) {
            DesktopModalSurface { Column(
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
                DesktopSessionSettingsTabs(settingsTab) { settingsTab = it }
                key(settingsTab) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DesktopSessionSettingsBody(state, controller, worldBookQuery, settingsTab, { backgroundOpen = true }, onOpenStudio) { worldBookQuery = it }
                }
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
            } }
        }
        if (state.sessionSettingsLeavePrompt) {
            DesktopModalSurface { Box(Modifier.fillMaxSize().background(colors.dim).padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
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
            } }
        }
        if (browser.renameSessionId != null) {
            DesktopModalSurface { Column(Modifier.fillMaxSize().background(colors.overlay).padding(20.dp),
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
            } }
        }
        if (browser.newChatOpen) {
            DesktopModalSurface { Column(Modifier.fillMaxSize().background(colors.overlay).padding(20.dp),
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
                        val summary by produceState<DesktopCharacterManagementPresentation?>(null, character.id) { value = controller.characterSummary(character.id) }
                        DesktopCharacterSummaryCard(summary) {
                            scope.launch {
                                val before = controller.state.value.sessions.map { it.id }.toSet()
                                controller.createSession(character.id)
                                val selected = controller.state.value.selectedSession?.id
                                if (selected != null && selected !in before &&
                                    compactNavigation.onSessionEntered(selected, controller.state.value)) {
                                    browser = browser.closeNewChat()
                                }
                            }
                        }
                    }
                }
                state.error?.let { StatusText(t.status(it), colors.destructive) }
            } }
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
            var retainedImages by remember(message.id) { mutableStateOf(message.images) }
            var addedImages by remember(message.id) { mutableStateOf(emptyList<DesktopPendingImage>()) }
            PrimaryModal(t(DesktopUiText.EDIT_MESSAGE)) {
                PrimaryField(t(DesktopUiText.MESSAGE), editingText) { editingText = it }
                StudioActions { retainedImages.forEach { reference -> Column {
                    DesktopOwnedImage(reference, controller.characterResources::readBytes, Modifier.size(80.dp))
                    BootstrapButton("移除此图片") { retainedImages = retainedImages - reference }
                } } }
                DesktopPendingImageStrip(addedImages, true, onRemove = { id -> addedImages = addedImages.filterNot { it.id == id } },
                    onPick = { scope.launch { controller.pickMessageEditImage()?.let { addedImages = addedImages + it } } })
                state.error?.let { StatusText(t.status(it), colors.destructive) }
                ActionRow {
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { editingMessage = null }
                    BootstrapButton(t(DesktopUiText.SAVE), enabled = editingText.isNotBlank() || retainedImages.isNotEmpty() || addedImages.isNotEmpty()) {
                        scope.launch { if (controller.editMessage(message.id, editingText, retainedImages, addedImages, message)) editingMessage = null }
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
    DesktopModalSurface { Column(
        Modifier.fillMaxSize().background(DesktopBootstrapColors.overlay).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PrimaryHeading(title)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    } }
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
    placement: DesktopChatNavigationLayout,
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
        val backgroundRevision = controller.backgroundLibrary?.revision?.collectAsState()?.value
        val preferred by produceState<String?>(null, state.selectedCharacter?.id, backgroundRevision) {
            value = state.selectedCharacter?.id?.let { runCatching { controller.backgroundLibrary?.preferred(it) }.getOrNull() }
        }
        val background = desktopEffectiveBackground(state.selectedSession?.chatBackground, preferred, state.selectedCharacter?.chatBackground).first
        if (!background.isNullOrBlank()) DesktopOwnedImage(background, controller.characterResources::readBytes,
            Modifier.width(placement.contentWidthDp.dp).fillMaxHeight().alpha(state.backgroundOpacity), crop = true)
        LazyColumn(
            Modifier.width(placement.contentWidthDp.dp).fillMaxHeight().alpha(if (viewport.ready) 1f else 0f)
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
        if (viewport.ready) {
            val groups = desktopChatNavigationGroups(viewport.canEarlier(), viewport.canLater(state.messageWindowAnchorId),
                mapping.keys.isNotEmpty() && !viewport.atBottom())
            DesktopChatNavigationGroup(groups.earlier, placement.external, !viewport.restoring,
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = if (placement.external) 0.dp else 8.dp)) {
                scope.launch { viewport.navigate(controller, it) }
            }
            DesktopChatNavigationGroup(groups.later, placement.external, !viewport.restoring,
                Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp, end = if (placement.external) 0.dp else 8.dp)) {
                scope.launch { viewport.navigate(controller, it) }
            }
        }
    }
}

@Composable
internal fun PrimaryMessageBubble(
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
    val imageOnly = desktopImageOnlyMessage(message, presented)
    val imageTaskRunning = imageOnly && controller.taskRuntime.tasks.collectAsState().value.any {
        it.sessionId == message.sessionId && it.status == DesktopTaskStatus.RUNNING
    }
    val imageOnlyMessageActions = if (imageOnly && imageTaskRunning)
        actions.filter { it == DesktopMessageAction.COPY } else actions
    Column(
        Modifier.fillMaxWidth()
            .then(if (presented.enclosingCard) Modifier
                .background(if (message.role == MessageRole.USER) colors.muted else colors.card, RoundedCornerShape(8.dp))
                .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(10.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (presented.showWholeMessageHeader) {
            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryAvatar(
                        state.selectedCharacter?.avatar.takeIf { message.role == MessageRole.ASSISTANT },
                        presented.speakerLabel,
                        controller,
                    )
                    StatusText(presented.speakerLabel)
                }
            }
            if (imageOnly) DesktopImageOnlyMessageContextMenu(imageOnlyMessageActions, onAction, header)
            else header()
        }
        DesktopMessageImages(message, state, controller)
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
        DesktopAssistantImageActions(message, state, controller, normalActions = actions.isNotEmpty())
        // Only action chrome is condensed; content and segment spacing above stays unchanged.
        val navigation = desktopAlternativeNavigation(message, state.alternativeEligibleIds)
        if (navigation != null || (actions.isNotEmpty() && !imageOnly)) Column(verticalArrangement = Arrangement.spacedBy(DesktopChatControlDensity.GAP_DP.dp)) {
            navigation?.let {
                DesktopChatAlternativeControls(navigation) { delta ->
                    scope.launch { controller.selectAssistantAlternative(message.id, delta) }
                }
            }
            if (actions.isNotEmpty() && !imageOnly) DesktopChatMessageToolbar(actions, onAction)
        }
    }
}

internal fun desktopImageOnlyMessage(message: ChatMessage, presented: DesktopPresentedMessage): Boolean =
    message.images.any { !it.startsWith(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX) } &&
        presented.copyText.isBlank() && presented.reasoning.isNullOrBlank()

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
    composer: DesktopComposerInput,
    canLaunch: Boolean,
    running: DesktopTaskEntry?,
    controller: DesktopPrimaryChatController,
    layout: DesktopComposerLayoutState,
    workspaceHeightDp: Float,
    onSend: () -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val performAction: () -> Unit = {
        if (running != null) controller.stop(running.taskId) else onSend()
    }
    val height = DesktopComposerHeightPolicy.clamp(layout.heightDp, workspaceHeightDp)
    val collapsed = DesktopComposerHeightPolicy.collapsed(height)
    DesktopComposerResizeHandle { deltaYDp -> layout.drag(deltaYDp, workspaceHeightDp) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().desktopImageIngress(running == null, controller::imageIngressFailure) { input -> scope.launch { controller.receiveImages(input) } }.border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp))) {
        DesktopPendingImageStrip(state.pendingImages, running == null,
            onRemove = { scope.launch { controller.removePendingImage(it) } }, onPick = { scope.launch { controller.pickImage() } }, showPicker = false,
            onReorder = { id, to -> scope.launch { controller.reorderPendingImage(id, to) } })
    Row(Modifier.fillMaxWidth().height(height.dp)
        .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
        .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp)), verticalAlignment = Alignment.CenterVertically) {
        DesktopComposerTextField(composer, full = false, canLaunch, controller::editComposer, onSend,
            Modifier.weight(1f).fillMaxHeight().padding(10.dp))
        Row(Modifier.align(Alignment.Bottom), verticalAlignment = Alignment.Bottom) {
        DesktopChatAttachmentAction(running == null) { scope.launch { controller.pickImage() } }
        DesktopChatIconAction(t(DesktopUiText.EXPAND_COMPOSER), DesktopAppIcons.ExpandComposer,
            enabled = canLaunch, targetDp = 48) { composer.open(canLaunch) }
        PrimaryComposerAction(running, composer.canSend(canLaunch), iconOnly = true, onClick = performAction)
        }
    }
    if (!collapsed) StatusText(t(DesktopUiText.COMPOSER_HINT))
    }
    running?.let { StatusText("${t(DesktopUiText.TASK)}: ${t.status(it.message)}") }
}

@Composable
private fun PrimaryComposerAction(
    running: DesktopTaskEntry?, canSend: Boolean, iconOnly: Boolean, onClick: () -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val send = desktopComposerAction(running != null) == DesktopComposerAction.SEND
    val label = t(if (send) DesktopUiText.SEND else DesktopUiText.STOP)
    if (iconOnly) DesktopChatIconAction(label, if (send) DesktopAppIcons.Send else DesktopAppIcons.Stop,
        enabled = !send || canSend, targetDp = 48, onClick = onClick)
    else BootstrapButton(label, secondary = !send, enabled = !send || canSend, onClick = onClick)
}

@Composable
internal fun DesktopSessionSettingsBody(
    state: DesktopPrimaryChatState,
    controller: DesktopPrimaryChatController,
    worldBookQuery: String,
    tab: DesktopSessionSettingsTab,
    onBackgroundLibrary: () -> Unit,
    onOpenStudio: () -> Unit,
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
    if (tab == DesktopSessionSettingsTab.BASIC) {
    PrimaryChoiceField(t(DesktopUiText.CHAT_MODEL), draft.modelId, state.modelChoices,
        inheritedLabel = "${t(DesktopUiText.INHERIT_GLOBAL_CHAT_MODEL)} · ${state.effectiveModels.chat?.name ?: t(DesktopUiText.NOT_CONFIGURED)}") { id ->
        controller.editSessionSettings { it.copy(modelId = id) }
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
    if (tab == DesktopSessionSettingsTab.IMAGES) {
    val imageScope = rememberCoroutineScope()
    StatusText("会话背景（立即保存；清除后使用 Desktop 首选或角色卡背景）")
    ActionRow {
        BootstrapButton("选择背景") { imageScope.launch { controller.chooseSessionBackground() } }
        BootstrapButton("清除会话覆盖", secondary = true) { imageScope.launch { controller.chooseSessionBackground(clear = true) } }
    }
    DesktopImageSlider("背景透明度（全局）", state.backgroundOpacity) { value ->
        imageScope.launch { controller.setBackgroundOpacity(value) }
    }
    StudioAction("Desktop 角色背景库", onClick = onBackgroundLibrary)
    StudioAction("打开 Studio", onClick = onOpenStudio)
    PrimaryHeading("NovelAI 图片设置")
    PrimaryChoiceField("Prompt 设计模型", draft.imageModelId, state.modelChoices,
        inheritedLabel = state.effectiveModels.inheritedImageLabel(t)) { id ->
        controller.editSessionSettings { it.copy(imageModelId = id) }
    }
    if (draft.imageModelId == null) state.effectiveModels.unavailableImageOverride(t)?.let {
        StatusText(it, DesktopBootstrapColors.warning)
    }
    else if (state.modelChoices.none { it.id == draft.imageModelId }) {
        StatusText("${t(DesktopUiText.CURRENT_EFFECTIVE)} · ${state.effectiveModels.image?.name ?: t(DesktopUiText.NOT_CONFIGURED)}", DesktopBootstrapColors.warning)
    }
    val imageModels = com.example.chatbar.domain.image.NovelAiImageModel.entries
    PrimaryChoiceField("NovelAI 模型（空值跟随角色卡/全局）", draft.novelAiImageModel?.name,
        imageModels.map { DesktopPrimaryChoice(it.name, it.displayName) }) { id ->
        controller.editSessionSettings { it.copy(novelAiImageModel = imageModels.firstOrNull { model -> model.name == id }) }
    }
    PrimaryField("本会话图片 Prompt 要求", draft.imagePromptPreference) { value ->
        controller.editSessionSettings { it.copy(imagePromptPreference = value) }
    }
    BootstrapButton(if (draft.novelAiNaturalLanguageMode) "V5 自然语言模式：已开启" else "V5 自然语言模式：已关闭", secondary = true) {
        controller.editSessionSettings { it.copy(novelAiNaturalLanguageMode = !it.novelAiNaturalLanguageMode) }
    }
    StatusText("自然语言偏好仅在实际生图模型为 V5 时生效；V4.5 保留偏好但暂停使用。")
    BootstrapButton(if (draft.automaticImageGenerationEnabled) "自动聊天图片偏好：开启" else "自动聊天图片偏好：关闭", secondary = true) {
        controller.editSessionSettings { it.copy(automaticImageGenerationEnabled = !it.automaticImageGenerationEnabled) }
    }
    }
    if (tab == DesktopSessionSettingsTab.CONTEXT) {
    PrimaryChoiceField(t(DesktopUiText.FORMAT_CARD), draft.formatCardId, state.formatChoices) { id ->
        controller.editSessionSettings { it.copy(formatCardId = id) }
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
    if (tab == DesktopSessionSettingsTab.ADVANCED) StatusText("当前没有额外的低频会话设置。图片与上下文配置在对应 tab 中。")
}

@Composable
private fun PrimaryChoiceField(
    label: String,
    selectedId: String?,
    choices: List<DesktopPrimaryChoice>,
    inheritedLabel: String? = null,
    onSelect: (String?) -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    if (selectedId != null && choices.none { it.id == selectedId }) StatusText("$label · ${t(DesktopUiText.UNAVAILABLE)}: $selectedId", DesktopBootstrapColors.warning)
    val options = listOf<String?>(null) + choices.map { it.id } +
        listOfNotNull(selectedId?.takeUnless { id -> choices.any { it.id == id } })
    SearchableChoice(label, options, selectedId,
        { id -> if (id == null) inheritedLabel ?: t(DesktopUiText.FOLLOW_DEFAULT)
            else choices.firstOrNull { it.id == id }?.label ?: "${t(DesktopUiText.UNAVAILABLE)} · $id" }, onSelect)

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
