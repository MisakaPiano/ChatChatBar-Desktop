@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.community.*
import com.example.chatbar.data.local.entity.WorldBookPosition
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

@Composable
internal fun DesktopCommunityPanel(controller: DesktopCommunityController, management: DesktopManagementController) {
    val state by controller.state.collectAsState()
    val t = LocalDesktopUiStrings.current
    val listState = rememberLazyListState()
    LaunchedEffect(controller) { controller.open() }
    LaunchedEffect(controller, state.items.size, state.mine, state.busy, state.hasMore) {
        if (!state.busy && state.hasMore) snapshotFlow {
            (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= listState.layoutInfo.totalItemsCount - 4
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) controller.prefetchNextPage() }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DesktopDataSectionHeading(t(DesktopUiText.COMMUNITY))
            StudioAction(t(DesktopUiText.COMMUNITY_ALL), selected = !state.mine, enabled = !state.busy) { controller.section(false) }
            StudioAction(t(DesktopUiText.COMMUNITY_MINE), selected = state.mine, enabled = !state.busy && state.displayName != null) { controller.section(true) }
            StudioAction(t(DesktopUiText.REFRESH), enabled = !state.busy) { controller.refresh() }
            if (state.displayName == null) StudioAction(t(DesktopUiText.COMMUNITY_LOGIN), enabled = !state.busy && state.enabled) { controller.beginLogin() }
            else {
                StatusText(state.displayName.orEmpty())
                StudioAction(t(DesktopUiText.COMMUNITY_LOGOUT), enabled = !state.busy) { controller.signOut() }
                CompactChoice(t(DesktopUiText.COMMUNITY_UPLOAD), CommunityItemType.entries, null, { communityTypeLabel(t, it) }) { controller.beginUpload(it) }
            }
            if (state.busy) StudioAction(t(DesktopUiText.COMMUNITY_STOP)) { controller.stop() }
        }
        DesktopFishField(t(DesktopUiText.COMMUNITY_SEARCH), state.query, onChange = controller::query)
        StudioChips("", listOf<CommunityItemType?>(null) + CommunityItemType.entries, state.type,
            { it?.let { communityTypeLabel(t, it) } ?: t(DesktopUiText.COMMUNITY_ALL) }, controller::filter)
        CommunityNotice(state)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.updates.isNotEmpty()) {
                item { DesktopDataSectionHeading(t(DesktopUiText.COMMUNITY_UPDATES)) }
                items(state.updates, key = { "update-${it.localId}" }) { update ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusText(update.localName)
                        StudioAction(t(DesktopUiText.COMMUNITY_IMPORT), enabled = !state.busy) { controller.importItem(update.remote, update.localId) }
                    }
                }
            }
            items(state.filteredItems, key = CommunityItem::id) { item ->
                Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.previews[communityPreviewKey(item)]?.let { bytes ->
                            val bitmap = remember(bytes) { DesktopImageEditing.decode(bytes).toComposeImageBitmap() }
                            Image(bitmap, null, Modifier.size(72.dp).clickable { if (!state.busy) controller.detail(item) })
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            StudioAction(item.title.take(200), enabled = !state.busy, style = StudioActionStyle.GHOST) { controller.detail(item) }
                            StatusText("${communityTypeLabel(t, item.type)} · ${item.authorName.take(100)}")
                            if (item.description.isNotBlank()) StatusText(item.description.take(500))
                            if (item.tags.isNotEmpty()) StatusText(item.tags.joinToString(" · ").take(300))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StudioAction(t(DesktopUiText.COMMUNITY_IMPORT), enabled = !state.busy) { controller.importItem(item) }
                        if (state.mine) {
                            StudioAction(t(DesktopUiText.COMMUNITY_REPLACE), enabled = !state.busy && !state.recheckRequired) { controller.beginUpload(item.type, item) }
                            BootstrapButton(t(DesktopUiText.DELETE), enabled = !state.busy && !state.recheckRequired, variant = DesktopActionVariant.DESTRUCTIVE) { controller.askDelete(item) }
                        }
                    }
                }
            }
            if (state.filteredItems.isEmpty() && !state.busy) item { StatusText(t(DesktopUiText.NO_ITEMS)) }
            if (state.hasMore) item { StudioAction(t(DesktopUiText.COMMUNITY_MORE), enabled = !state.busy) { controller.nextPage() } }
        }
    }
    when {
        state.loginOpen -> CommunityLoginDialog(controller, state)
        state.uploadType != null -> CommunityUploadDialog(controller, state)
        state.detail != null -> CommunityDialog(t(DesktopUiText.COMMUNITY_DETAIL), !state.busy, controller::closeDetail,
            notice = { CommunityNotice(state) }, actions = {
                StudioAction(t(DesktopUiText.COMMUNITY_IMPORT), enabled = !state.busy) { controller.importItem(requireNotNull(state.detail)) }
                if (state.busy) StudioAction(t(DesktopUiText.COMMUNITY_STOP)) { controller.stop() }
            }) {
            CommunityPayloadPreview(state.detailPayload, requireNotNull(state.detail).description)
        }
        state.deleting != null -> CommunityDialog(t(DesktopUiText.DELETE), !state.busy, controller::cancelDelete,
            notice = { CommunityNotice(state) }, actions = {
                BootstrapButton(t(DesktopUiText.DELETE), enabled = !state.busy, variant = DesktopActionVariant.DESTRUCTIVE) { controller.deleteConfirmed() }
                if (state.busy) StudioAction(t(DesktopUiText.COMMUNITY_STOP)) { controller.stop() }
            }) {
            Column { StatusText(state.deleting!!.title); StatusText(t(DesktopUiText.COMMUNITY_DELETE_NOTE)) }
        }
    }
    DesktopManagementOverlays(management, showTransferConflict = true)
}

internal fun communityTypeLabel(t: DesktopUiStrings, type: CommunityItemType): String = t(when (type) {
    CommunityItemType.CHARACTER -> DesktopUiText.CHARACTERS
    CommunityItemType.FORMAT -> DesktopUiText.FORMAT_CARD
    CommunityItemType.WORLD_BOOK -> DesktopUiText.WORLD_BOOKS
})

@Composable
private fun CommunityDialog(title: String, closeEnabled: Boolean, close: () -> Unit,
    notice: @Composable () -> Unit = {},
    actions: @Composable () -> Unit, content: @Composable () -> Unit) {
    val t = LocalDesktopUiStrings.current
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().heightIn(max = 650.dp)
                .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopDataSectionHeading(title) }
                    DesktopIconAction(t(DesktopUiText.CLOSE), DesktopAppIcons.Close, enabled = closeEnabled, onClick = close)
                }
                notice()
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) { content() }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
            }
        }
    }
}

@Composable private fun CommunityNotice(state: DesktopCommunityState) {
    val t = LocalDesktopUiStrings.current
    if (state.notice.isNotBlank()) {
        val key = DesktopUiText.entries.firstOrNull { it.zhCn == state.notice }
        StatusText(key?.let(t::invoke) ?: t.status(state.notice), if (state.error) DesktopBootstrapColors.destructive else DesktopBootstrapColors.mutedForeground)
    }
    if (state.recheckRequired) StatusText(t(DesktopUiText.COMMUNITY_RECHECK))
}

@Composable
private fun CommunityLoginDialog(controller: DesktopCommunityController, state: DesktopCommunityState) {
    val t = LocalDesktopUiStrings.current
    var callback by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf(false) }
    CommunityDialog(t(DesktopUiText.COMMUNITY_LOGIN), !state.busy, controller::closeLogin,
        notice = { CommunityNotice(state) },
        actions = {
            StudioAction(t(DesktopUiText.COMMUNITY_LOGIN), enabled = !state.busy) { controller.beginLogin() }
            if (recovery) StudioAction(t(DesktopUiText.COMMUNITY_FINISH_LOGIN), enabled = !state.busy && callback.isNotBlank()) {
                val submitted = callback; callback = ""; visible = false; controller.finishLogin(submitted)
            }
            if (state.busy) StudioAction(t(DesktopUiText.COMMUNITY_STOP)) { controller.stop() }
        }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText(t(DesktopUiText.COMMUNITY_RETURN_HELP))
            StudioAction(t(DesktopUiText.COMMUNITY_MANUAL_RETURN), enabled = !state.busy) { recovery = !recovery; callback = ""; visible = false }
            if (recovery) {
                DesktopFishField(t(DesktopUiText.COMMUNITY_RETURN), callback, secret = !visible) { callback = it.take(16_384) }
                DesktopIconAction(if (visible) t(DesktopUiText.COMMUNITY_HIDE_RETURN) else t(DesktopUiText.COMMUNITY_SHOW_RETURN),
                    if (visible) DesktopAppIcons.HideSecret else DesktopAppIcons.ShowSecret) { visible = !visible }
            }
        }
    }
}

@Composable
private fun CommunityUploadDialog(controller: DesktopCommunityController, state: DesktopCommunityState) {
    val t = LocalDesktopUiStrings.current
    var selected by remember(state.uploadType, state.replacing?.id) { mutableStateOf(state.candidates.firstOrNull()?.id) }
    var title by remember(state.uploadType, state.replacing?.id) { mutableStateOf(state.replacing?.title.orEmpty()) }
    var description by remember(state.uploadType, state.replacing?.id) { mutableStateOf(state.replacing?.description.orEmpty()) }
    var tags by remember(state.uploadType, state.replacing?.id) { mutableStateOf(state.replacing?.tags?.joinToString(", ").orEmpty()) }
    CommunityDialog(t(DesktopUiText.COMMUNITY_UPLOAD), !state.busy, controller::closeUpload, notice = { CommunityNotice(state) }, actions = {
        if (state.prepared == null) StudioAction(t(DesktopUiText.COMMUNITY_PREPARE), enabled = !state.busy && selected != null) {
            controller.prepareUpload(requireNotNull(selected), title, description, tags)
        } else {
            StudioAction(t(DesktopUiText.EDIT), enabled = !state.busy) { controller.editPrepared() }
            StudioAction(t(DesktopUiText.COMMUNITY_PUBLISH), enabled = !state.busy && !state.recheckRequired) { controller.publish() }
        }
        if (state.busy) StudioAction(t(DesktopUiText.COMMUNITY_STOP)) { controller.stop() }
    }) {
        if (state.prepared != null) CommunityPayloadPreview(state.preparedPayload,
            "${state.prepared.title}\n${state.prepared.description}\n${state.prepared.tags.joinToString(", ")}\n${state.prepared.sizeBytes} B\n\n${t(DesktopUiText.COMMUNITY_SNAPSHOT_NOTE)}")
        else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText(t(DesktopUiText.COMMUNITY_SNAPSHOT_NOTE))
            if (state.candidates.isEmpty()) StatusText(t(DesktopUiText.NO_ITEMS))
            else CompactChoice(t(DesktopUiText.COMMUNITY_SELECT), state.candidates, state.candidates.firstOrNull { it.id == selected },
                { it.title }) { selected = it.id }
            DesktopFishField(t(DesktopUiText.WORLD_NAME), title) { title = it }
            DesktopFishField(t(DesktopUiText.WORLD_DESCRIPTION), description) { description = it }
            DesktopFishField(t(DesktopUiText.COMMUNITY_TAGS), tags) { tags = it }
        }
    }
}

@Composable
private fun CommunityPayloadPreview(payload: CommunityImportPayload?, introduction: String) {
    val t = LocalDesktopUiStrings.current
    val chunks = remember(payload, introduction, t) {
        buildString {
            appendLine(introduction)
            when (payload) {
                is CommunityImportPayload.Character -> with(payload.data) {
                    appendLine(card.name); appendLine(card.creator); appendLine(card.characterVersion)
                    appendLine(card.basicSetting); appendLine(card.freeformCharacterText)
                    appendLine(card.greeting); card.alternateGreetings.forEach { appendLine(it) }
                    card.characters.forEach { person ->
                        appendLine(person.name)
                        listOf(person.profile, person.appearance, person.clothing, person.abilities, person.habits,
                            person.background, person.relationships, person.speakingStyle, person.imagePrompt).forEach { appendLine(it) }
                    }
                    listOf(card.systemPrompt, card.postHistoryInstructions, card.mesExample, card.creatorNotes).forEach { appendLine(it) }
                    appendLine(card.defaultImagePrompt); appendLine(card.defaultImageNegativePrompt)
                    documents.forEach { appendLine(it.fileName); appendLine(it.content) }
                    worldBooks.forEach { book -> appendLine(book.name); book.entries.forEach { appendLine(it.name); appendLine(it.content) } }
                }
                is CommunityImportPayload.Format -> { appendLine(payload.data.name); appendLine(payload.data.content) }
                is CommunityImportPayload.WorldBook -> with(payload.data.book) {
                    appendLine(name); appendLine(description)
                    appendLine("${t(DesktopUiText.WORLD_SCAN_DEPTH)}: $scanDepth · ${t(DesktopUiText.WORLD_ENTRIES)}: ${entries.size}")
                    entries.forEach {
                        appendLine(it.name); appendLine(it.keys.joinToString(", ")); appendLine(it.content)
                        val position = t(when (it.position) {
                            WorldBookPosition.BEFORE_CHAR -> DesktopUiText.WORLD_BEFORE
                            WorldBookPosition.AFTER_CHAR -> DesktopUiText.WORLD_AFTER
                            WorldBookPosition.OUTLET -> DesktopUiText.WORLD_OUTLET
                        })
                        appendLine("${t(DesktopUiText.WORLD_POSITION)}: $position · ${t(DesktopUiText.WORLD_INSERTION_ORDER)}: ${it.insertionOrder}")
                    }
                }
                null -> Unit
            }
        }.communityPreviewChunks()
    }
    SelectionContainer { LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chunks) { StatusText(it, DesktopBootstrapColors.foreground) }
    } }
}

/** Text layout chunks preserve every UTF-16 pair and the original preview text. */
internal fun String.communityPreviewChunks(): List<String> = buildList {
    var start = 0
    while (start < length) {
        var end = (start + 4_000).coerceAtMost(length)
        if (end < length && this@communityPreviewChunks[end - 1].isHighSurrogate() && this@communityPreviewChunks[end].isLowSurrogate()) end--
        add(substring(start, end)); start = end
    }
}
