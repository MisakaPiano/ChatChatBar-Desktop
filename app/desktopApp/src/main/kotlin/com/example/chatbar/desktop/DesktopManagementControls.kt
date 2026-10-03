package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun DesktopManagementImportAndDrafts(kind: DesktopTransferKind, ids: Set<String>, controller: DesktopManagementController) {
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val transfer by controller.transfer.state.collectAsState()
    val drafts by controller.drafts.collectAsState(emptyList())
    BootstrapButton(t(when (kind) {
        DesktopTransferKind.CHARACTER -> DesktopUiText.IMPORT_CHARACTER
        DesktopTransferKind.FORMAT -> DesktopUiText.IMPORT_FORMAT
        DesktopTransferKind.WORLD_BOOK -> DesktopUiText.IMPORT_WORLD_BOOK
    }), secondary = true, enabled = !state.busy && !transfer.busy && transfer.pendingConflict == null) {
        scope.launch { controller.import(kind) }
    }
    state.warning?.let { StatusText(t(it), DesktopBootstrapColors.warning) }
    if (state.characterReferences.isNotEmpty()) StatusText("${t(DesktopUiText.CHARACTERS)}: ${state.characterReferences.joinToString()}")
    if (state.sessionReferences.isNotEmpty()) StatusText("${t(DesktopUiText.SESSIONS)}: ${state.sessionReferences.joinToString()}")
    state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
    transfer.status?.let { StatusText(it) }
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
        DesktopIconAction(t(DesktopUiText.MANAGE_MORE_ACTIONS), DesktopAppIcons.More) {
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
    if (pending != null || conflict != null) Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim)
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
                    BootstrapButton(t(DesktopUiText.OVERWRITE), enabled = !state.busy && !transfer.busy &&
                        (conflict as? DesktopPendingTransferConflict.Character)?.overwriteAllowed != false) { scope.launch { controller.resolveConflict(DesktopTransferConflictAction.OVERWRITE) } }
                    BootstrapButton(t(DesktopUiText.IMPORT_AS_NEW), enabled = !state.busy && !transfer.busy) { scope.launch { controller.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW) } }
                    BootstrapButton(t(DesktopUiText.CANCEL), enabled = !state.busy && !transfer.busy, secondary = true) { scope.launch { controller.resolveConflict(DesktopTransferConflictAction.CANCEL) } }
                }
            }
        }
    }
}
