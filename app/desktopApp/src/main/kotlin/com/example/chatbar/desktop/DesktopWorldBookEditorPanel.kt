package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
import com.example.chatbar.domain.draft.WorldBookEntryModalState
import kotlinx.coroutines.launch

@Composable
internal fun DesktopWorldBookManagementPanel(controller: DesktopWorldBookEditorController, management: DesktopManagementController) {
    val t = LocalDesktopUiStrings.current
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.load() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WBHeading(t(DesktopUiText.WORLD_MANAGEMENT))
        WBField(t(DesktopUiText.WORLD_SEARCH), state.query, onChange = controller::search)
        BootstrapButton(t(DesktopUiText.WORLD_NEW)) { scope.launch { controller.openNew() } }
        DesktopManagementImportAndDrafts(DesktopTransferKind.WORLD_BOOK, state.books.map { it.id }.toSet(), management)
        DesktopManagementPresets(DesktopTransferKind.WORLD_BOOK,
            state.books.map { it.sourcePresetKey to it.sourcePresetVersion }, management)
        if (state.visibleBooks.isEmpty()) StatusText(t(DesktopUiText.WORLD_NONE))
        state.visibleBooks.forEach { book ->
            Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .clickable { scope.launch { controller.openExisting(book.id) } }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    StatusText(book.name)
                    if (management.hasPresetUpdate(DesktopTransferKind.WORLD_BOOK, book.sourcePresetKey, book.sourcePresetVersion))
                        StatusText(t(DesktopUiText.PRESET_UPDATE_AVAILABLE), DesktopBootstrapColors.warning)
                }
                DesktopIconAction(t(DesktopUiText.EDIT), DesktopAppIcons.Edit) { scope.launch { controller.openExisting(book.id) } }
                DesktopManagementItemActions(DesktopTransferKind.WORLD_BOOK, book.id, book.name, management)
            }
        }
    }
}

@Composable
internal fun DesktopWorldBookEditorOverlay(controller: DesktopWorldBookEditorController) {
    val state by controller.state.collectAsState()
    val book = state.book ?: return
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val colors = DesktopBootstrapColors
    var helpOpen by remember(book.id) { mutableStateOf(false) }
    var importOpen by remember(book.id) { mutableStateOf(false) }
    var deleteIndex by remember(book.id) { mutableStateOf<Int?>(null) }
    Box(Modifier.fillMaxSize().background(colors.overlay)) {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                WBHeading(t(if (state.targetId == null) DesktopUiText.WORLD_NEW else DesktopUiText.WORLD_EDIT))
                BootstrapButton(t(DesktopUiText.WORLD_HELP), secondary = true) { helpOpen = true }
                BootstrapButton(t(DesktopUiText.CLOSE), secondary = true) { controller.requestLeave(controller::closeClean) }
            }
            if (state.dirty) StatusText(t(if (state.draftPersisted) DesktopUiText.RECOVERED_DRAFT
                else DesktopUiText.DRAFT_SAVING), colors.warning)
            state.problem?.let { problem ->
                StatusText(t(problem.uiText()), colors.destructive)
                if (problem == WorldBookEditorProblem.SOURCE_CHANGED) {
                    BootstrapButton(t(DesktopUiText.OVERWRITE)) { scope.launch { controller.save(forceOverwrite = true) } }
                }
                if (problem == WorldBookEditorProblem.SOURCE_CHANGED || problem == WorldBookEditorProblem.SOURCE_DELETED) {
                    BootstrapButton(t(DesktopUiText.WORLD_SAVE_AS_NEW)) { scope.launch { controller.saveAsNew() } }
                }
                if (problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING ||
                    problem == WorldBookEditorProblem.CLEAN_DRAFT_WARNING) {
                    BootstrapButton(t(DesktopUiText.WORLD_RETRY_CLEANUP)) { scope.launch { controller.retryCleanup() } }
                }
            }
            WBField(t(DesktopUiText.WORLD_NAME), book.name) { value -> controller.edit { it.copy(name = value) } }
            WBField(t(DesktopUiText.WORLD_DESCRIPTION), book.description, multiline = true) { value ->
                controller.edit { it.copy(description = value) }
            }
            WBField(t(DesktopUiText.WORLD_SCAN_DEPTH), state.scanDepthInput, onChange = controller::editScanDepth)
            WBField(t(DesktopUiText.WORLD_TOKEN_BUDGET), state.tokenBudgetInput, onChange = controller::editTokenBudget)
            WBBoolean(t(DesktopUiText.WORLD_RECURSIVE), book.recursiveScanning) {
                controller.edit { it.copy(recursiveScanning = !it.recursiveScanning) }
            }
            WBBoolean(t(DesktopUiText.WORLD_CASE), book.caseSensitive) {
                controller.edit { it.copy(caseSensitive = !it.caseSensitive) }
            }
            WBBoolean(t(DesktopUiText.WORLD_WHOLE), book.matchWholeWords) {
                controller.edit { it.copy(matchWholeWords = !it.matchWholeWords) }
            }
            WBHeading("${t(DesktopUiText.WORLD_ENTRIES)} (${book.entries.size})")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton(t(DesktopUiText.WORLD_ADD_ENTRY)) { controller.openEntry(null) }
                BootstrapButton(t(DesktopUiText.WORLD_IMPORT_CHARACTER), secondary = true) { importOpen = true }
            }
            book.entries.forEachIndexed { index, entry ->
                Column(Modifier.fillMaxWidth().border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    StatusText("${index + 1}. ${entry.name.ifBlank { entry.keys.joinToString(", ") }}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BootstrapButton(t(DesktopUiText.EDIT), secondary = true) { controller.openEntry(index) }
                        BootstrapButton(t(if (entry.enabled) DesktopUiText.ON else DesktopUiText.OFF), secondary = true) {
                            controller.toggleEntry(index)
                        }
                        BootstrapButton(t(DesktopUiText.DELETE), secondary = true) { deleteIndex = index }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton(t(DesktopUiText.SAVE)) { scope.launch { if (controller.save()) controller.closeClean() } }
                BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { controller.requestLeave(controller::closeClean) }
                BootstrapButton(t(DesktopUiText.DISCARD_DRAFT), secondary = true) { scope.launch { controller.discard() } }
            }
        }
        if (state.modal != null) WBEntryEditor(controller, state.modal!!)
        if (importOpen) WBCharacterImport(controller, state) { importOpen = false }
        if (helpOpen) WBHelp { helpOpen = false }
        if (state.pendingClearCardId != null) {
            WBDialog(t(DesktopUiText.WORLD_IMPORT_CHARACTER)) {
                StatusText(t(DesktopUiText.WORLD_CLEAR_SOURCE_CONFIRM))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BootstrapButton(t(DesktopUiText.WORLD_KEEP_SOURCE), secondary = true) {
                        controller.keepSourceCharacters()
                    }
                    BootstrapButton(t(DesktopUiText.WORLD_CLEAR_SOURCE)) {
                        scope.launch { controller.confirmClearSourceCharacters() }
                    }
                }
            }
        }
        deleteIndex?.let { index ->
            WBDialog(t(DesktopUiText.WORLD_DELETE_ENTRY_CONFIRM)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BootstrapButton(t(DesktopUiText.DELETE)) { controller.deleteEntry(index); deleteIndex = null }
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { deleteIndex = null }
                }
            }
        }
    }
}

@Composable
private fun WBEntryEditor(controller: DesktopWorldBookEditorController, modal: WorldBookEntryModalState) {
    val t = LocalDesktopUiStrings.current
    val state by controller.state.collectAsState()
    WBDialog(t(if (modal.editingIndex == null) DesktopUiText.WORLD_ADD_ENTRY else DesktopUiText.WORLD_EDIT_ENTRY)) {
        state.problem?.takeIf { it == WorldBookEditorProblem.ENTRY_REQUIRED ||
            it == WorldBookEditorProblem.ENTRY_CHANGED || it == WorldBookEditorProblem.ENTRY_MODAL_OPEN }
            ?.let { StatusText(t(it.uiText()), DesktopBootstrapColors.destructive) }
        WBField(t(DesktopUiText.WORLD_ENTRY_NAME), modal.name) { value ->
            controller.updateEntryModal { it.copy(name = value) }
        }
        // Raw comma-separated keys and numeric strings stay in modal draft until shared materialization.
        WBField(t(DesktopUiText.WORLD_PRIMARY_KEYS), modal.keys) { value -> controller.updateEntryModal { it.copy(keys = value) } }
        WBField(t(DesktopUiText.WORLD_SECONDARY_KEYS), modal.secondary) { value -> controller.updateEntryModal { it.copy(secondary = value) } }
        WBHeading(t(DesktopUiText.WORLD_LOGIC))
        WBChoices(listOf(
            WorldBookSelectiveLogic.AND_ANY.value to t(DesktopUiText.WORLD_AND_ANY),
            WorldBookSelectiveLogic.NOT_ALL.value to t(DesktopUiText.WORLD_NOT_ALL),
            WorldBookSelectiveLogic.NOT_ANY.value to t(DesktopUiText.WORLD_NOT_ANY),
            WorldBookSelectiveLogic.AND_ALL.value to t(DesktopUiText.WORLD_AND_ALL),
        ), modal.logic) { value -> controller.updateEntryModal { it.copy(logic = value) } }
        WBHeading(t(DesktopUiText.WORLD_POSITION))
        WBChoices(listOf(
            WorldBookPosition.BEFORE_CHAR to t(DesktopUiText.WORLD_BEFORE),
            WorldBookPosition.AFTER_CHAR to t(DesktopUiText.WORLD_AFTER),
            WorldBookPosition.OUTLET to t(DesktopUiText.WORLD_OUTLET),
        ), modal.position) { value -> controller.updateEntryModal { it.copy(position = value) } }
        if (modal.position == WorldBookPosition.OUTLET) WBField(t(DesktopUiText.WORLD_OUTLET_NAME), modal.outlet) { value ->
            controller.updateEntryModal { it.copy(outlet = value) }
        }
        WBField(t(DesktopUiText.WORLD_ENTRY_CONTENT), modal.content, multiline = true) { value ->
            controller.updateEntryModal { it.copy(content = value) }
        }
        WBField(t(DesktopUiText.WORLD_INSERTION_ORDER), modal.order) { value -> controller.updateEntryModal { it.copy(order = value) } }
        WBField(t(DesktopUiText.WORLD_PROBABILITY), modal.probability) { value -> controller.updateEntryModal { it.copy(probability = value) } }
        WBField(t(DesktopUiText.WORLD_GROUP), modal.group) { value -> controller.updateEntryModal { it.copy(group = value) } }
        WBField(t(DesktopUiText.WORLD_GROUP_WEIGHT), modal.groupWeight) { value -> controller.updateEntryModal { it.copy(groupWeight = value) } }
        WBField(t(DesktopUiText.WORLD_ENTRY_SCAN_DEPTH), modal.scanDepth) { value -> controller.updateEntryModal { it.copy(scanDepth = value) } }
        WBField(t(DesktopUiText.WORLD_STICKY), modal.sticky) { value -> controller.updateEntryModal { it.copy(sticky = value) } }
        WBField(t(DesktopUiText.WORLD_COOLDOWN), modal.cooldown) { value -> controller.updateEntryModal { it.copy(cooldown = value) } }
        WBField(t(DesktopUiText.WORLD_DELAY), modal.delay) { value -> controller.updateEntryModal { it.copy(delay = value) } }
        WBBoolean(t(DesktopUiText.WORLD_ENABLED), modal.enabled) { controller.updateEntryModal { it.copy(enabled = !it.enabled) } }
        WBBoolean(t(DesktopUiText.WORLD_CONSTANT), modal.constant) { controller.updateEntryModal { it.copy(constant = !it.constant) } }
        WBBoolean(t(DesktopUiText.WORLD_REGEX), modal.useRegex) { controller.updateEntryModal { it.copy(useRegex = !it.useRegex) } }
        WBTriState(t(DesktopUiText.WORLD_WHOLE), modal.wholeWords) { controller.updateEntryModal { state -> state.copy(wholeWords = it) } }
        WBTriState(t(DesktopUiText.WORLD_CASE), modal.caseSensitive) { controller.updateEntryModal { state -> state.copy(caseSensitive = it) } }
        WBBoolean(t(DesktopUiText.WORLD_MATCH_DESCRIPTION), modal.matchCharacterDescription) { controller.updateEntryModal { it.copy(matchCharacterDescription = !it.matchCharacterDescription) } }
        WBBoolean(t(DesktopUiText.WORLD_MATCH_PERSONALITY), modal.matchCharacterPersonality) { controller.updateEntryModal { it.copy(matchCharacterPersonality = !it.matchCharacterPersonality) } }
        WBBoolean(t(DesktopUiText.WORLD_MATCH_SCENARIO), modal.matchScenario) { controller.updateEntryModal { it.copy(matchScenario = !it.matchScenario) } }
        WBBoolean(t(DesktopUiText.WORLD_MATCH_CREATOR), modal.matchCreatorNotes) { controller.updateEntryModal { it.copy(matchCreatorNotes = !it.matchCreatorNotes) } }
        WBBoolean(t(DesktopUiText.WORLD_MATCH_PERSONA), modal.matchPersonaDescription) { controller.updateEntryModal { it.copy(matchPersonaDescription = !it.matchPersonaDescription) } }
        WBBoolean(t(DesktopUiText.WORLD_IGNORE_BUDGET), modal.ignoreBudget) { controller.updateEntryModal { it.copy(ignoreBudget = !it.ignoreBudget) } }
        WBBoolean(t(DesktopUiText.WORLD_EXCLUDE_RECURSION), modal.excludeRecursion) { controller.updateEntryModal { it.copy(excludeRecursion = !it.excludeRecursion) } }
        WBBoolean(t(DesktopUiText.WORLD_PREVENT_RECURSION), modal.preventRecursion) { controller.updateEntryModal { it.copy(preventRecursion = !it.preventRecursion) } }
        WBBoolean(t(DesktopUiText.WORLD_DELAY_RECURSION), modal.delayUntilRecursion) { controller.updateEntryModal { it.copy(delayUntilRecursion = !it.delayUntilRecursion) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BootstrapButton(t(DesktopUiText.WORLD_SAVE_ENTRY)) { controller.saveEntry() }
            BootstrapButton(t(DesktopUiText.WORLD_DISMISS_ENTRY), secondary = true) { controller.dismissEntry() }
        }
    }
}

@Composable
private fun WBCharacterImport(controller: DesktopWorldBookEditorController,
    state: DesktopWorldBookEditorState, onClose: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    var cardId by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    WBDialog(t(DesktopUiText.WORLD_IMPORT_CHARACTER)) {
        if (state.eligibleCharacters.isEmpty()) StatusText(t(DesktopUiText.WORLD_NO_STRUCTURED_CHARACTERS))
        WBHeading(t(DesktopUiText.WORLD_IMPORT_SELECT_CARD))
        state.eligibleCharacters.forEach { card ->
            BootstrapButton(card.name, secondary = cardId != card.id) { cardId = card.id; selectedIds = emptySet() }
        }
        val card = state.eligibleCharacters.firstOrNull { it.id == cardId }
        if (card != null) {
            WBHeading(t(DesktopUiText.WORLD_IMPORT_SELECT_CHARACTERS))
            card.characters.filter { it.name.isNotBlank() }.forEach { character ->
                BootstrapButton(character.name, secondary = character.id !in selectedIds) {
                    selectedIds = if (character.id in selectedIds) selectedIds - character.id else selectedIds + character.id
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BootstrapButton(t(DesktopUiText.WORLD_IMPORT_ACTION)) {
                if (cardId != null && selectedIds.isNotEmpty() &&
                    controller.importCharacters(cardId!!, selectedIds) != null) onClose()
            }
            BootstrapButton(t(DesktopUiText.CANCEL), secondary = true, onClick = onClose)
        }
    }
}

@Composable
private fun WBHelp(onClose: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    WBDialog(t(DesktopUiText.WORLD_HELP)) {
        listOf(DesktopUiText.WORLD_HELP_BOOK, DesktopUiText.WORLD_HELP_TRIGGERS,
            DesktopUiText.WORLD_HELP_TIMING, DesktopUiText.WORLD_HELP_MATCHING).forEach { StatusText(t(it)) }
        BootstrapButton(t(DesktopUiText.CLOSE), secondary = true, onClick = onClose)
    }
}

@Composable
internal fun DesktopWorldBookLeavePrompt(controller: DesktopWorldBookEditorController) {
    val state by controller.state.collectAsState()
    if (!state.leavePrompt) return
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    WBDialog(t(DesktopUiText.UNSAVED_CHANGES)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BootstrapButton(t(DesktopUiText.SAVE_AND_LEAVE)) { scope.launch { controller.saveAndLeave() } }
            BootstrapButton(t(DesktopUiText.WORLD_KEEP_DRAFT_AND_LEAVE)) { scope.launch { controller.keepDraftAndLeave() } }
            BootstrapButton(t(DesktopUiText.DISCARD_CHANGES)) { scope.launch { controller.discard() } }
            BootstrapButton(t(DesktopUiText.CONTINUE_EDITING), secondary = true) { controller.continueEditing() }
        }
    }
}

@Composable
private fun WBDialog(title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp)
            .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp))
            .padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WBHeading(title)
            content()
        }
    }
}

@Composable private fun WBHeading(value: String) { StatusText(value) }

@Composable
private fun WBField(label: String, value: String, multiline: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = if (multiline) 140.dp else 36.dp)
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
            .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp)).padding(9.dp),
            textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp), singleLine = !multiline)
    }
}

@Composable
private fun WBBoolean(label: String, value: Boolean, onToggle: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusText(label)
        BootstrapButton(t(if (value) DesktopUiText.ON else DesktopUiText.OFF), secondary = !value, onClick = onToggle)
    }
}

@Composable
private fun <T> WBChoices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            BootstrapButton(label, secondary = value != selected) { onSelect(value) }
        }
    }
}

@Composable
private fun WBTriState(label: String, selected: Boolean?, onSelect: (Boolean?) -> Unit) {
    val t = LocalDesktopUiStrings.current
    StatusText(label)
    WBChoices(listOf(null to t(DesktopUiText.WORLD_FOLLOW_BOOK),
        true to t(DesktopUiText.WORLD_YES_MATCH), false to t(DesktopUiText.WORLD_NO_MATCH)), selected, onSelect)
}

private fun WorldBookEditorProblem.uiText(): DesktopUiText = when (this) {
    WorldBookEditorProblem.NAME_REQUIRED -> DesktopUiText.WORLD_NAME_REQUIRED
    WorldBookEditorProblem.DUPLICATE_NAME -> DesktopUiText.WORLD_DUPLICATE_NAME
    WorldBookEditorProblem.SCAN_DEPTH_INVALID -> DesktopUiText.WORLD_SCAN_DEPTH_INVALID
    WorldBookEditorProblem.TOKEN_BUDGET_INVALID -> DesktopUiText.WORLD_TOKEN_BUDGET_INVALID
    WorldBookEditorProblem.ENTRY_REQUIRED -> DesktopUiText.WORLD_ENTRY_REQUIRED
    WorldBookEditorProblem.ENTRY_CHANGED -> DesktopUiText.WORLD_ENTRY_CHANGED
    WorldBookEditorProblem.ENTRY_MODAL_OPEN -> DesktopUiText.WORLD_ENTRY_MODAL_OPEN
    WorldBookEditorProblem.SOURCE_CHANGED -> DesktopUiText.WORLD_SOURCE_CHANGED
    WorldBookEditorProblem.SOURCE_DELETED -> DesktopUiText.WORLD_SOURCE_DELETED
    WorldBookEditorProblem.NEW_DRAFT_EXISTS -> DesktopUiText.WORLD_NEW_DRAFT_EXISTS
    WorldBookEditorProblem.DRAFT_FAILED -> DesktopUiText.WORLD_DRAFT_FAILED
    WorldBookEditorProblem.SAVE_FAILED -> DesktopUiText.WORLD_SAVE_FAILED
    WorldBookEditorProblem.SAVE_COMMITTED_WARNING -> DesktopUiText.WORLD_SAVE_WARNING
    WorldBookEditorProblem.CLEAN_DRAFT_WARNING -> DesktopUiText.WORLD_CLEAN_DRAFT_WARNING
    WorldBookEditorProblem.CHARACTER_IMPORT_FAILED -> DesktopUiText.WORLD_IMPORT_FAILED
    WorldBookEditorProblem.CHARACTER_CLEAR_FAILED -> DesktopUiText.WORLD_CLEAR_FAILED
}
