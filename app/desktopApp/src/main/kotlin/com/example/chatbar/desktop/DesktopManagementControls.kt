package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch

@Composable
internal fun DesktopManagementImportAction(kind: DesktopTransferKind, controller: DesktopManagementController) {
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val transfer by controller.transfer.state.collectAsState()
    BootstrapButton(t(when (kind) {
        DesktopTransferKind.CHARACTER -> DesktopUiText.IMPORT_CHARACTER
        DesktopTransferKind.FORMAT -> DesktopUiText.IMPORT_FORMAT
        DesktopTransferKind.WORLD_BOOK -> DesktopUiText.IMPORT_WORLD_BOOK
    }), secondary = true, enabled = !state.busy && !transfer.busy && transfer.pendingConflict == null && transfer.unresolvedTransfer == null) {
        scope.launch { controller.import(kind) }
    }
}

@Composable internal fun DesktopManagementHeading(title: String) {
    BasicText(title, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 18.sp))
}

@Composable internal fun DesktopManagementToolbar(search: String, query: String, onQuery: (String) -> Unit,
    create: String, enabled: Boolean, onCreate: () -> Unit, kind: DesktopTransferKind, management: DesktopManagementController) {
    @Composable fun Search(modifier: Modifier) {
        BasicTextField(query, onQuery, modifier.heightIn(min = 48.dp)
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
            .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp)).padding(12.dp)
            .semantics { contentDescription = search }, singleLine = true,
            cursorBrush = DesktopBootstrapColors.inputCaret,
            textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
            decorationBox = { inner -> Box { if (query.isEmpty()) StatusText(search); inner() } })
    }
    @Composable fun Actions() {
        BootstrapButton(create, icon = DesktopAppIcons.Add, enabled = enabled, onClick = onCreate)
        DesktopManagementImportAction(kind, management)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 560.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) { Search(Modifier.weight(1f)); Actions() }
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Search(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Actions() }
        }
    }
}

@Composable
internal fun DesktopManagementImportAndDrafts(kind: DesktopTransferKind, ids: Set<String>, controller: DesktopManagementController,
    showImport: Boolean = true) {
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val transfer by controller.transfer.state.collectAsState()
    val drafts by controller.drafts.collectAsState(emptyList())
    if (showImport) DesktopManagementImportAction(kind, controller)
    state.warning?.let { StatusText(t(it), DesktopBootstrapColors.warning) }
    if (state.characterReferences.isNotEmpty()) StatusText("${t(DesktopUiText.CHARACTERS)}: ${state.characterReferences.joinToString()}")
    if (state.sessionReferences.isNotEmpty()) StatusText("${t(DesktopUiText.SESSIONS)}: ${state.sessionReferences.joinToString()}")
    state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
    transfer.status?.let { StatusText(it) }
    transfer.committedNotice?.let { StatusText(t(it.uiText()), DesktopBootstrapColors.warning) }
    transfer.typedNotice?.let { StatusText(it.uiMessage(t), DesktopBootstrapColors.warning) }
    transfer.unresolvedTransfer?.let {
        StatusText("${t(DesktopUiText.TRANSFER_PENDING_VERIFICATION)}: ${it.targetId}", DesktopBootstrapColors.warning)
        BootstrapButton(t(DesktopUiText.TRANSFER_RECHECK), enabled = !state.busy && !transfer.busy) {
            scope.launch { controller.recheckTransfer() }
        }
    }
    transfer.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
    if (state.busy || transfer.busy) StatusText(t(DesktopUiText.WORKING))
    desktopManagementDraftRows(drafts, kind, ids).recoverable.forEach { draft ->
        key(draft.id) {
            Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .clickable(enabled = !state.busy) { scope.launch { controller.openDraft(draft) } }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    StatusText(draft.title)
                    StatusText(t(DesktopUiText.MANAGE_RECOVERABLE_DRAFT), DesktopBootstrapColors.warning)
                }
                DesktopIconAction(t(DesktopUiText.DISCARD_DRAFT), DesktopAppIcons.Delete, destructive = true) { controller.requestDiscard(draft) }
            }
        }
    }
}

internal fun DesktopTransferKind.presetSectionTitle(): DesktopUiText = when (this) {
    DesktopTransferKind.CHARACTER -> DesktopUiText.RECOVER_PRESET_CHARACTER
    DesktopTransferKind.FORMAT -> DesktopUiText.BUILT_IN_FORMATS
    DesktopTransferKind.WORLD_BOOK -> DesktopUiText.RECOVER_PRESET_WORLD_BOOK
}

internal fun showCompletePresetAction(kind: DesktopTransferKind, entry: com.example.chatbar.data.local.entity.PresetEntry): Boolean =
    kind == DesktopTransferKind.CHARACTER &&
        entry.type == com.example.chatbar.data.local.entity.PresetType.CHARACTER &&
        entry.worldBookPresetKeys.isNotEmpty()

internal fun DesktopPresetSuiteRestoreResult.uiMessage(t: (DesktopUiText) -> String): String =
    if (alreadyComplete) t(DesktopUiText.COMPLETE_PRESET_DONE)
    else "${t(DesktopUiText.COMPLETE_PRESET_RESTORED)}: " +
        "${if (characterCreated) 1 else 0} ${t(DesktopUiText.COMPLETE_PRESET_CHARACTERS)}, " +
        "$worldBooksCreated ${t(DesktopUiText.COMPLETE_PRESET_WORLDS)}, " +
        "$bindingsAdded ${t(DesktopUiText.COMPLETE_PRESET_BINDINGS)}."

@Composable
internal fun DesktopManagementPresets(
    kind: DesktopTransferKind,
    entities: List<Pair<String?, Int?>>,
    controller: DesktopManagementController,
) {
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val transfer by controller.transfer.state.collectAsState()
    var expanded by remember(kind) { mutableStateOf(false) }
    BootstrapButton("${if (expanded) "▾" else "▸"} ${t(kind.presetSectionTitle())}", secondary = true) { expanded = !expanded }
    if (!expanded) return
    if (kind == DesktopTransferKind.CHARACTER) state.suiteResult?.let { StatusText(it.uiMessage { key -> t(key) }) }
    desktopManagementPresetRows(controller.presetEntries(kind), entities).forEach { row ->
        Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
            .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                StatusText("${row.entry.displayName} · ${t(DesktopUiText.PRESET_VERSION)} ${row.entry.version}")
                when (row.availability) {
                    DesktopPresetAvailability.RECOVERABLE -> StatusText(t(DesktopUiText.PRESET_RECOVERABLE))
                    DesktopPresetAvailability.UPDATE_AVAILABLE -> StatusText(t(DesktopUiText.PRESET_UPDATE_AVAILABLE), DesktopBootstrapColors.warning)
                    DesktopPresetAvailability.PRESENT -> Unit
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                BootstrapButton(t(DesktopUiText.IMPORT_RESTORE), secondary = true,
                    enabled = !state.busy && !transfer.busy && transfer.pendingConflict == null && transfer.unresolvedTransfer == null) {
                    scope.launch { controller.recoverPreset(kind, row.entry) }
                }
                if (showCompletePresetAction(kind, row.entry)) {
                    BootstrapButton(t(DesktopUiText.RESTORE_COMPLETE_PRESET), secondary = true,
                        enabled = controller.canRestoreCompletePreset()) {
                        scope.launch { controller.restoreCompletePreset(row.entry) }
                    }
                }
            }
        }
        if (showCompletePresetAction(kind, row.entry))
            StatusText(t(DesktopUiText.COMPLETE_PRESET_NOTE))
    }
}

@Composable
internal fun DesktopManagementItemActions(kind: DesktopTransferKind, id: String, name: String, controller: DesktopManagementController) {
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val transfer by controller.transfer.state.collectAsState()
    val drafts by controller.drafts.collectAsState(emptyList())
    val draft = desktopManagementDraftRows(drafts, kind, setOf(id)).badges[id]
    var open by remember(kind, id) { mutableStateOf(false) }
    if (draft != null) StatusText(t(DesktopUiText.MANAGE_HAS_DRAFT), DesktopBootstrapColors.warning)
    Box {
        DesktopIconAction(t(DesktopUiText.MANAGE_MORE_ACTIONS), DesktopAppIcons.More,
            enabled = !state.busy && !transfer.busy) {
            if (!state.busy && !transfer.busy && transfer.pendingConflict == null) open = !open
        }
        if (open) Popup(alignment = Alignment.BottomEnd, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(IntrinsicSize.Max).widthIn(max = 360.dp).background(DesktopBootstrapColors.card, RoundedCornerShape(8.dp))
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(4.dp)) {
                @Composable fun item(label: DesktopUiText, destructive: Boolean = false, action: () -> Unit) {
                    Box(Modifier.fillMaxWidth().clickable(enabled = !state.busy && !transfer.busy) { open = false; action() }.padding(10.dp)) {
                        StatusText(t(label), if (destructive) DesktopBootstrapColors.destructive else DesktopBootstrapColors.foreground)
                    }
                }
                item(DesktopUiText.DUPLICATE) { scope.launch { controller.duplicate(kind, id) } }
                item(if (kind == DesktopTransferKind.WORLD_BOOK) DesktopUiText.EXPORT_CHATBAR_JSON else DesktopUiText.EXPORT_JSON) {
                    scope.launch { controller.export(kind, id) }
                }
                if (kind != DesktopTransferKind.FORMAT) item(if (kind == DesktopTransferKind.CHARACTER) DesktopUiText.EXPORT_CCB_PNG else DesktopUiText.EXPORT_SILLYTAVERN_JSON) {
                    scope.launch { controller.export(kind, id, alternate = true) }
                }
                if (draft != null) {
                    item(DesktopUiText.MANAGE_OPEN_DRAFT) { scope.launch { controller.openDraft(draft) } }
                    item(DesktopUiText.DISCARD_DRAFT, true) { controller.requestDiscard(draft) }
                }
                item(DesktopUiText.DELETE, true) { controller.requestDelete(kind, id, name) }
            }
        }
    }
}

@Composable
internal fun DesktopManagementOverlays(controller: DesktopManagementController, showTransferConflict: Boolean) {
    val state by controller.state.collectAsState()
    val transfer by controller.transfer.state.collectAsState()
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val pending = state.pendingDeletion
    val conflict = transfer.pendingConflict.takeIf { showTransferConflict }
    if (pending != null || conflict != null) DesktopModalSurface { Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim)
        .clickable { /* Modal surface consumes clicks; underlying rows must not receive them. */ }.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pending != null) {
                StatusText("${t(if (pending is DesktopManagementDeletion.Draft) DesktopUiText.DISCARD_DRAFT else DesktopUiText.DELETE)}: ${pending.name}")
                StatusText(t(DesktopUiText.MANAGE_DELETE_CONFIRM))
                ActionRow {
                    BootstrapButton(t(DesktopUiText.DELETE), enabled = !state.busy, variant = DesktopActionVariant.DESTRUCTIVE) { scope.launch { controller.confirmDeletion() } }
                    BootstrapButton(t(DesktopUiText.CANCEL), enabled = !state.busy, secondary = true, onClick = controller::cancelDeletion)
                }
            } else if (conflict != null) {
                StatusText("${t(DesktopUiText.NAME_CONFLICT)}: ${conflict.existingName} ← ${conflict.incomingName}")
                transfer.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                ActionRow {
                    BootstrapButton(t(DesktopUiText.OVERWRITE), enabled = !state.busy && !transfer.busy && transfer.unresolvedTransfer == null &&
                        (conflict as? DesktopPendingTransferConflict.Character)?.overwriteAllowed != false) { scope.launch { controller.resolveConflict(DesktopTransferConflictAction.OVERWRITE) } }
                    BootstrapButton(t(DesktopUiText.IMPORT_AS_NEW), enabled = !state.busy && !transfer.busy && transfer.unresolvedTransfer == null) { scope.launch { controller.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW) } }
                    BootstrapButton(t(DesktopUiText.CANCEL), enabled = !state.busy && !transfer.busy, secondary = true) { scope.launch { controller.resolveConflict(DesktopTransferConflictAction.CANCEL) } }
                }
            }
        }
    } }
}
