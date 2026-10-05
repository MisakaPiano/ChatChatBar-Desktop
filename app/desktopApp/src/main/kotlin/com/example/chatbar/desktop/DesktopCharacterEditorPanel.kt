package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.domain.card.CharacterSectionImportPolicy
import com.example.chatbar.domain.card.CharacterSectionSelection
import com.example.chatbar.domain.card.CharacterTextSection
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage

@Composable
internal fun DesktopCharacterManagementPanel(controller: DesktopCharacterEditorController, management: DesktopManagementController) {
    val t = LocalDesktopUiStrings.current
    val state by controller.state.collectAsState()
    val managementState by management.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.load() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EditorHeading(t(DesktopUiText.CHARACTER_MANAGEMENT))
        if (state.card == null) state.problem?.let { StatusText(t(it.uiText()), DesktopBootstrapColors.destructive) }
        EditorField(t(DesktopUiText.SEARCH_CHARACTERS), state.query, onChange = controller::search)
        BootstrapButton(t(DesktopUiText.NEW_CHARACTER), icon = DesktopAppIcons.Add,
            enabled = !managementState.busy) { scope.launch { controller.openNew() } }
        DesktopManagementImportAndDrafts(DesktopTransferKind.CHARACTER, state.characters.map { it.id }.toSet(), management)
        DesktopManagementPresets(DesktopTransferKind.CHARACTER,
            state.characters.map { it.sourcePresetKey to it.sourcePresetVersion }, management)
        if (state.visibleCharacters.isEmpty()) StatusText(t(if (state.query.isBlank()) DesktopUiText.NO_CHARACTERS
            else DesktopUiText.NO_MATCHING_CHARACTERS))
        state.visibleCharacters.forEach { card ->
            Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .clickable(enabled = !managementState.busy) { scope.launch { controller.openExisting(card.id) } }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                key(card) { CharacterManagementAvatar(card, controller) }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(card.name, style = TextStyle(color = DesktopBootstrapColors.foreground))
                    if (management.hasPresetUpdate(DesktopTransferKind.CHARACTER, card.sourcePresetKey, card.sourcePresetVersion))
                        StatusText(t(DesktopUiText.PRESET_UPDATE_AVAILABLE), DesktopBootstrapColors.warning)
                    StatusText(t(DesktopUiText.CHARACTER_LIST_COUNTS)
                        .replace("{characters}", card.characters.size.toString())
                        .replace("{documents}", card.customDocuments.size.toString()))
                }
                DesktopIconAction(t(DesktopUiText.EDIT), DesktopAppIcons.Edit,
                    enabled = !managementState.busy) { scope.launch { controller.openExisting(card.id) } }
                DesktopManagementItemActions(DesktopTransferKind.CHARACTER, card.id, card.name, management)
            }
        }
    }
}

@Composable
private fun CharacterManagementAvatar(card: CharacterCard, controller: DesktopCharacterEditorController) {
    val presentation by produceState<DesktopCharacterManagementPresentation?>(null, card, controller) {
        value = withContext(Dispatchers.IO) { desktopCharacterManagementPresentation(card, controller::imageBytes) }
    }
    val avatar = remember(presentation) { presentation?.avatar?.toComposeImageBitmap() }
    Box(Modifier.size(42.dp).clip(CircleShape).background(DesktopBootstrapColors.muted), contentAlignment = Alignment.Center) {
        if (avatar != null) Image(avatar, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else StatusText(presentation?.fallbackInitial ?: card.name.trim().take(1).ifEmpty { "?" })
    }
}

@Composable
internal fun DesktopCharacterLeavePrompt(controller: DesktopCharacterEditorController) {
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    if (state.leavePrompt) {
        DesktopModalSurface { Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp))
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorHeading(t(DesktopUiText.UNSAVED_CHANGES))
                EditorActions {
                    BootstrapButton(t(DesktopUiText.SAVE_AND_LEAVE)) { scope.launch { controller.saveAndLeave() } }
                    BootstrapButton(t(DesktopUiText.DISCARD_CHANGES)) { scope.launch { controller.discard() } }
                    BootstrapButton(t(DesktopUiText.CONTINUE_EDITING)) { controller.continueEditing() }
                }
            }
        } }
    }
}

@Composable
internal fun DesktopCharacterEditorOverlay(controller: DesktopCharacterEditorController, state: DesktopCharacterEditorState) {
    val card = state.card ?: return
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    var selectedEntry by remember(card.id) { mutableStateOf<String?>(null) }
    var selectedDocument by remember(card.id) { mutableStateOf<String?>(null) }
    var confirmClearDocuments by remember { mutableStateOf(false) }
    var importOpen by remember(card.id) { mutableStateOf(false) }
    var convertConfirm by remember(card.id) { mutableStateOf(false) }
    var importResult by remember(card.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    val presentation = desktopCharacterEditorPresentation(state)
    val communityReadOnly = presentation.communityReadOnly
    val readOnly = presentation.readOnly
    DesktopModalSurface { Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.overlay).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            EditorHeading("${t(DesktopUiText.EDIT_CHARACTER)} · ${card.name.ifBlank { t(DesktopUiText.NEW_CHARACTER) }}")
            BootstrapButton(t(DesktopUiText.CLOSE)) { controller.requestLeave { controller.closeClean() } }
        }
        if (state.dirty) StatusText(if (state.draftPersisted) t(DesktopUiText.RECOVERED_DRAFT)
            else t(DesktopUiText.DRAFT_SAVING), DesktopBootstrapColors.warning)
        importResult?.let { (created, updated) -> StatusText(t(DesktopUiText.CHARACTER_IMPORT_RESULT)
            .replace("{created}", created.toString()).replace("{updated}", updated.toString())) }
        state.problem?.let { problem ->
            StatusText(t(problem.uiText()), DesktopBootstrapColors.destructive)
            if (problem == CharacterEditorProblem.SOURCE_CHANGED || problem == CharacterEditorProblem.SOURCE_DELETED ||
                problem == CharacterEditorProblem.COMMUNITY_READ_ONLY) {
                BootstrapButton(t(if (problem == CharacterEditorProblem.COMMUNITY_READ_ONLY) DesktopUiText.COPY_AS_NEW
                    else DesktopUiText.SAVE_AS_NEW)) { scope.launch { controller.saveAsNew() } }
            }
        }
        if (presentation.showOrdinaryCleanupRetry || presentation.showCommittedCleanupRetry) {
            BootstrapButton(t(DesktopUiText.CHARACTER_RETRY_CLEANUP)) {
                scope.launch { controller.retryCleanup() }
            }
        }
        if (readOnly) {
            StatusText(t(if (communityReadOnly) DesktopUiText.COMMUNITY_READ_ONLY
                else DesktopUiText.CHARACTER_SAVE_COMMITTED_WARNING))
            StatusText(card.name)
        } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EditorField(t(DesktopUiText.CHARACTER_NAME), card.name) { value -> controller.edit { it.copy(name = value) } }
            EditorField(t(DesktopUiText.BOT_NAME), card.botName) { value -> controller.edit { it.copy(botName = value) } }
            EditorField(t(DesktopUiText.GREETING), card.greeting, multiline = true) { value -> controller.edit { it.copy(greeting = value) } }
            EditorSection(t(DesktopUiText.ALTERNATE_GREETINGS)) {
                card.alternateGreetings.forEachIndexed { index, greeting ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            EditorField("${index + 1}", greeting, multiline = true) { controller.setGreeting(index, it) }
                        }
                        BootstrapButton(t(DesktopUiText.REMOVE)) { controller.removeGreeting(index) }
                    }
                }
                BootstrapButton(t(DesktopUiText.ADD_GREETING)) { controller.addGreeting() }
            }
            EditorSection(t(DesktopUiText.CHARACTER_INFOS)) {
                EditorActions {
                    BootstrapButton(t(DesktopUiText.STRUCTURED_MODE), secondary = card.editMode != CharacterEditMode.STRUCTURED) {
                        controller.switchMode(CharacterEditMode.STRUCTURED)
                    }
                    BootstrapButton(t(DesktopUiText.FREEFORM_MODE), secondary = card.editMode != CharacterEditMode.FREEFORM) {
                        controller.switchMode(CharacterEditMode.FREEFORM)
                    }
                }
                StatusText(t(DesktopUiText.MODE_SWITCH_NOTE))
                if (card.editMode == CharacterEditMode.STRUCTURED) {
                    EditorActions {
                        BootstrapButton(t(DesktopUiText.CHARACTER_IMPORT_DATA), secondary = true) { importOpen = true }
                        BootstrapButton(t(DesktopUiText.CHARACTER_CONVERT_FREEFORM), secondary = true,
                            enabled = controller.canConvertStructuredToFreeform) { convertConfirm = true }
                    }
                } else EditorField(t(DesktopUiText.FREEFORM_BODY), card.freeformCharacterText, multiline = true) { value ->
                    controller.edit { it.copy(freeformCharacterText = value) }
                }
                CharacterPersonWorkspace(card, selectedEntry, controller) { selectedEntry = it }
            }
            EditorSection(t(DesktopUiText.BASIC_SETTING)) {
                EditorField(t(DesktopUiText.BASIC_SETTING), card.basicSetting, true) { value -> controller.edit { it.copy(basicSetting = value) } }
                EditorField(t(DesktopUiText.IMAGE_PROMPT), card.defaultImagePrompt, true) { value -> controller.edit { it.copy(defaultImagePrompt = value) } }
                EditorField(t(DesktopUiText.NEGATIVE_IMAGE_PROMPT), card.defaultImageNegativePrompt, true) { value -> controller.edit { it.copy(defaultImageNegativePrompt = value) } }
                EditorField(t(DesktopUiText.SYSTEM_PROMPT_FIELD), card.systemPrompt, true) { value -> controller.edit { it.copy(systemPrompt = value) } }
                EditorField(t(DesktopUiText.POST_HISTORY_FIELD), card.postHistoryInstructions, true) { value -> controller.edit { it.copy(postHistoryInstructions = value) } }
                EditorField(t(DesktopUiText.MESSAGE_EXAMPLE), card.mesExample, true) { value -> controller.edit { it.copy(mesExample = value) } }
                EditorField(t(DesktopUiText.CREATOR_NOTES), card.creatorNotes, true) { value -> controller.edit { it.copy(creatorNotes = value) } }
            }
            EditorSection(t(DesktopUiText.CHARACTER_WORLD_BOOKS)) {
                val known = state.worldBooks.map { it.id }.toSet()
                state.worldBooks.forEach { world ->
                    BootstrapButton("${if (world.id in card.worldBookIds) "✓ " else "○ "}${world.name}",
                        secondary = world.id !in card.worldBookIds) { controller.toggleWorldBook(world.id) }
                }
                card.worldBookIds.filterNot(known::contains).forEach { id ->
                    BootstrapButton("${t(DesktopUiText.UNAVAILABLE_BINDING)} · $id") { controller.toggleWorldBook(id) }
                }
            }
            EditorSection(t(DesktopUiText.CHARACTER_DEFAULT_FORMAT)) {
                BootstrapButton(t(DesktopUiText.FOLLOW_DEFAULT), secondary = card.defaultFormatCardId != null) {
                    controller.setDefaultFormatCard(null)
                }
                state.formatCards.forEach { format ->
                    BootstrapButton(format.name, secondary = card.defaultFormatCardId != format.id) {
                        controller.setDefaultFormatCard(format.id)
                    }
                }
                card.defaultFormatCardId?.takeIf { id -> state.formatCards.none { it.id == id } }?.let { id ->
                    StatusText("${t(DesktopUiText.UNAVAILABLE_BINDING)} · $id")
                }
            }
            EditorSection(t(DesktopUiText.CHARACTER_IMAGES)) {
                ImageSlot(t(DesktopUiText.AVATAR_IMAGE), card.avatar,
                    { controller.chooseAvatar(t(DesktopUiText.CHOOSE_IMAGE)) }, controller::clearAvatar, controller)
                ImageSlot(t(DesktopUiText.CHAT_BACKGROUND_IMAGE), card.chatBackground,
                    { controller.chooseBackground(t(DesktopUiText.CHOOSE_IMAGE)) }, controller::clearBackground, controller)
            }
            EditorSection(t(DesktopUiText.REFERENCE_DOCUMENTS)) {
                BootstrapButton(t(DesktopUiText.IMPORT_TEXT_DOCUMENT)) {
                    controller.addDocumentFromPicker(t(DesktopUiText.IMPORT_TEXT_DOCUMENT))
                }
                var newName by remember(card.id) { mutableStateOf("") }
                var newBody by remember(card.id) { mutableStateOf("") }
                EditorField(t(DesktopUiText.DOCUMENT_NAME), newName) { newName = it }
                EditorField(t(DesktopUiText.DOCUMENT_BODY), newBody, true) { newBody = it }
                BootstrapButton(t(DesktopUiText.NEW_TEXT_DOCUMENT)) {
                    controller.addTextDocument(newName, newBody); newName = ""; newBody = ""
                }
                card.customDocuments.forEach { doc ->
                    BootstrapButton(doc.fileName, secondary = selectedDocument != doc.id) {
                        selectedDocument = if (selectedDocument == doc.id) null else doc.id
                    }
                    if (selectedDocument == doc.id) {
                        var name by remember(doc.id, doc.filePath) { mutableStateOf(doc.fileName) }
                        val loaded = remember(doc.id, doc.filePath) { controller.documentText(doc.id) }
                        var body by remember(doc.id, doc.filePath) { mutableStateOf(loaded?.getOrNull().orEmpty()) }
                        EditorField(t(DesktopUiText.DOCUMENT_NAME), name) { name = it }
                        if (loaded?.isFailure == true) StatusText(t(DesktopUiText.CHARACTER_DOCUMENT_READ_FAILED),
                            DesktopBootstrapColors.destructive)
                        else EditorField(t(DesktopUiText.DOCUMENT_BODY), body, true) { body = it }
                        EditorActions {
                            BootstrapButton(t(DesktopUiText.SAVE_DOCUMENT_EDIT), enabled = loaded?.isSuccess == true) {
                                controller.editDocument(doc.id, name, body)
                            }
                            BootstrapButton(t(DesktopUiText.REMOVE)) { controller.removeDocument(doc.id); selectedDocument = null }
                        }
                    }
                }
                if (card.customDocuments.isNotEmpty()) BootstrapButton(t(DesktopUiText.CLEAR_DOCUMENTS)) {
                    confirmClearDocuments = true
                }
                if (confirmClearDocuments) {
                    StatusText(t(DesktopUiText.CLEAR_DOCUMENTS_CONFIRM))
                    EditorActions {
                        BootstrapButton(t(DesktopUiText.CONFIRM)) { controller.clearDocuments(); confirmClearDocuments = false }
                        BootstrapButton(t(DesktopUiText.CANCEL)) { confirmClearDocuments = false }
                    }
                }
            }
        }
        EditorActions {
            BootstrapButton(t(DesktopUiText.SAVE), enabled = !state.busy && !readOnly) { scope.launch { controller.save() } }
            BootstrapButton(t(DesktopUiText.DISCARD_DRAFT), enabled = !readOnly) { scope.launch { controller.discard() } }
            BootstrapButton(t(DesktopUiText.CANCEL)) { controller.requestLeave { controller.closeClean() } }
        }
    } }
    if (importOpen) CharacterImportDialog(controller, onClose = { importOpen = false }) { created, updated ->
        importResult = created to updated
        importOpen = false
    }
    if (convertConfirm) CharacterEditorConfirmation(t(DesktopUiText.CHARACTER_CONVERT_FREEFORM),
        t(DesktopUiText.CHARACTER_CONVERT_WARNING),
        t(DesktopUiText.CHARACTER_CONVERT_CONFIRM), onCancel = { convertConfirm = false }) {
        controller.convertStructuredToFreeform()
        convertConfirm = false
    }
}

@Composable
private fun CharacterImportDialog(controller: DesktopCharacterEditorController,
    onClose: () -> Unit, onImported: (Int, Int) -> Unit) {
    val t = LocalDesktopUiStrings.current
    val cards = controller.availableImportCards
    var selectedCardId by remember { mutableStateOf(cards.firstOrNull()?.id) }
    val card = cards.firstOrNull { it.id == selectedCardId }
    val available = card?.characters.orEmpty().filter { it.name.isNotBlank() }.associate { person ->
        person.id to CharacterSectionImportPolicy.transferableSections.filter { section ->
            CharacterSectionImportPolicy.sectionValue(person, section).isNotBlank()
        }.toSet()
    }
    var selected by remember(selectedCardId) { mutableStateOf(available) }
    DesktopModalSurface { Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp)
            .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp))
            .padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EditorHeading(t(DesktopUiText.CHARACTER_IMPORT_DATA))
            if (cards.isEmpty()) StatusText(t(DesktopUiText.CHARACTER_IMPORT_EMPTY))
            StatusText(t(DesktopUiText.CHARACTER_IMPORT_SOURCE))
            cards.forEach { source ->
                BootstrapButton(source.name, secondary = selectedCardId != source.id) { selectedCardId = source.id }
            }
            card?.characters.orEmpty().filter { it.name.isNotBlank() && available[it.id].orEmpty().isNotEmpty() }
                .forEach { person ->
                    val options = available[person.id].orEmpty()
                    val chosen = selected[person.id].orEmpty()
                    StatusText("${t(DesktopUiText.CHARACTER_IMPORT_PERSON)} · ${person.name}")
                    BootstrapButton(t(DesktopUiText.CHARACTER_IMPORT_SECTIONS), secondary = !chosen.containsAll(options)) {
                        selected = selected + (person.id to if (chosen.containsAll(options)) emptySet() else options)
                    }
                    options.forEach { section ->
                        BootstrapButton(t(section.uiText()), secondary = section !in chosen) {
                            selected = selected + (person.id to
                                if (section in chosen) chosen - section else chosen + section)
                        }
                    }
                }
            EditorActions {
                BootstrapButton(t(DesktopUiText.CHARACTER_IMPORT_ACTION),
                    enabled = selected.values.any { it.isNotEmpty() }) {
                    val result = selectedCardId?.let { id -> controller.importCharacters(id,
                        selected.mapNotNull { (personId, sections) ->
                            sections.takeIf { it.isNotEmpty() }?.let { CharacterSectionSelection(personId, it) }
                        }) }
                    if (result != null) onImported(result.createdCount, result.updatedCount)
                }
                BootstrapButton(t(DesktopUiText.CANCEL), secondary = true, onClick = onClose)
            }
        }
    } }
}

@Composable
private fun CharacterEditorConfirmation(title: String, message: String, confirm: String,
    onCancel: () -> Unit, onConfirm: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    DesktopModalSurface { Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp))
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorHeading(title)
            StatusText(message)
            EditorActions {
                BootstrapButton(confirm, onClick = onConfirm)
                BootstrapButton(t(DesktopUiText.CANCEL), secondary = true, onClick = onCancel)
            }
        }
    } }
}

private fun CharacterTextSection.uiText(): DesktopUiText = when (this) {
    CharacterTextSection.PROFILE -> DesktopUiText.PROFILE
    CharacterTextSection.APPEARANCE -> DesktopUiText.APPEARANCE
    CharacterTextSection.CLOTHING -> DesktopUiText.CLOTHING
    CharacterTextSection.ABILITIES -> DesktopUiText.ABILITIES
    CharacterTextSection.HABITS -> DesktopUiText.HABITS
    CharacterTextSection.BACKGROUND -> DesktopUiText.BACKGROUND_FIELD
    CharacterTextSection.RELATIONSHIPS -> DesktopUiText.RELATIONSHIPS
    CharacterTextSection.SPEAKING_STYLE -> DesktopUiText.SPEAKING_STYLE
    CharacterTextSection.IMAGE_PROMPT -> DesktopUiText.IMAGE_PROMPT
}

@Composable
private fun CharacterPersonWorkspace(
    card: CharacterCard, selectedId: String?, controller: DesktopCharacterEditorController, onSelect: (String?) -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val workspace = desktopPersonWorkspace(card, selectedId)
    if (workspace.avatarBook) EditorHeading(t(DesktopUiText.PERSON_AVATAR_BOOK))
    BootstrapButton(t(DesktopUiText.ADD_CHARACTER_INFO), icon = DesktopAppIcons.Add) {
        controller.addCharacter()
        onSelect(controller.state.value.card?.characters?.lastOrNull()?.id)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val master: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                workspace.rows.forEach { person ->
                    key(person.id, person.avatarReference) {
                        CharacterPersonRow(person, workspace.selected?.id == person.id, controller,
                            onSelect = { onSelect(person.id) },
                            onDelete = { controller.removeCharacter(person.id); if (selectedId == person.id) onSelect(null) })
                    }
                }
            }
        }
        val detail: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val selected = workspace.selected
                if (selected == null) StatusText(t(DesktopUiText.SELECT_PERSON))
                else key(selected.id, workspace.detailKind) {
                    when (workspace.detailKind) {
                        DesktopPersonDetailKind.STRUCTURED_FIELDS -> CharacterEntryFields(selected, controller)
                        DesktopPersonDetailKind.NAME_AND_AVATAR -> CharacterAvatarBookFields(selected, controller)
                    }
                }
            }
        }
        if (DesktopPersonLayout.sideBySide(maxWidth.value)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.width(320.dp)) { master() }
                Box(Modifier.weight(1f)) { detail() }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { master(); detail() }
    }
}

@Composable
private fun CharacterPersonRow(
    person: DesktopPersonRowPresentation, selected: Boolean, controller: DesktopCharacterEditorController,
    onSelect: () -> Unit, onDelete: () -> Unit,
) {
    val colors = DesktopBootstrapColors
    val t = LocalDesktopUiStrings.current
    val avatar by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, person.avatarReference, controller) {
        value = withContext(Dispatchers.IO) { desktopPersonAvatar(person, controller::imageBytes) }
    }
    Row(Modifier.fillMaxWidth().background(if (selected) colors.secondary else colors.card, RoundedCornerShape(8.dp))
        .selectable(selected, role = Role.Tab, onClick = onSelect).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(colors.muted), contentAlignment = Alignment.Center) {
            val image = avatar
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else StatusText(desktopPersonInitial(person))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(person.name.ifBlank { t(DesktopUiText.NEW_CHARACTER) }, style = TextStyle(color = colors.foreground))
            person.profile?.let { BasicText(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.mutedForeground, fontSize = 12.sp)) }
        }
        DesktopIconAction(t(DesktopUiText.EDIT), DesktopAppIcons.Edit, onClick = onSelect)
        DesktopIconAction(t(DesktopUiText.DELETE), DesktopAppIcons.Delete, destructive = true, onClick = onDelete)
    }
}

@Composable
private fun CharacterAvatarBookFields(entry: CharacterInfo, controller: DesktopCharacterEditorController) {
    val t = LocalDesktopUiStrings.current
    EditorField(t(DesktopUiText.CHARACTER_NAME), entry.name) { v -> controller.updateCharacter(entry.id) { it.copy(name = v) } }
    ImageSlot(t(DesktopUiText.APPEARANCE_IMAGE), entry.appearanceImage,
        { controller.chooseAppearance(entry.id, t(DesktopUiText.CHOOSE_IMAGE)) },
        { controller.clearAppearance(entry.id) }, controller)
}

@Composable
private fun CharacterEntryFields(entry: CharacterInfo, controller: DesktopCharacterEditorController) {
    val t = LocalDesktopUiStrings.current
    EditorField(t(DesktopUiText.CHARACTER_NAME), entry.name) { v -> controller.updateCharacter(entry.id) { it.copy(name = v) } }
    EditorField(t(DesktopUiText.PROFILE), entry.profile, true) { v -> controller.updateCharacter(entry.id) { it.copy(profile = v) } }
    EditorField(t(DesktopUiText.APPEARANCE), entry.appearance, true) { v -> controller.updateCharacter(entry.id) { it.copy(appearance = v) } }
    EditorField(t(DesktopUiText.CLOTHING), entry.clothing, true) { v -> controller.updateCharacter(entry.id) { it.copy(clothing = v) } }
    EditorField(t(DesktopUiText.ABILITIES), entry.abilities, true) { v -> controller.updateCharacter(entry.id) { it.copy(abilities = v) } }
    EditorField(t(DesktopUiText.HABITS), entry.habits, true) { v -> controller.updateCharacter(entry.id) { it.copy(habits = v) } }
    EditorField(t(DesktopUiText.BACKGROUND_FIELD), entry.background, true) { v -> controller.updateCharacter(entry.id) { it.copy(background = v) } }
    EditorField(t(DesktopUiText.RELATIONSHIPS), entry.relationships, true) { v -> controller.updateCharacter(entry.id) { it.copy(relationships = v) } }
    EditorField(t(DesktopUiText.SPEAKING_STYLE), entry.speakingStyle, true) { v -> controller.updateCharacter(entry.id) { it.copy(speakingStyle = v) } }
    EditorField(t(DesktopUiText.IMAGE_PROMPT), entry.imagePrompt, true) { v -> controller.updateCharacter(entry.id) { it.copy(imagePrompt = v) } }
    ImageSlot(t(DesktopUiText.APPEARANCE_IMAGE), entry.appearanceImage,
        { controller.chooseAppearance(entry.id, t(DesktopUiText.CHOOSE_IMAGE)) },
        { controller.clearAppearance(entry.id) }, controller)
}

@Composable
private fun ImageSlot(label: String, reference: String?, choose: () -> Unit, clear: () -> Unit,
    controller: DesktopCharacterEditorController) {
    val t = LocalDesktopUiStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusText(label)
        if (reference != null) {
            val bitmap = remember(reference) { controller.imageBytes(reference)?.let { bytes ->
                runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
            } }
            if (bitmap == null) StatusText(t(DesktopUiText.IMAGE_MISSING))
            else Image(bitmap, contentDescription = label, modifier = Modifier.size(96.dp))
        }
        EditorActions {
            BootstrapButton(t(DesktopUiText.CHOOSE_IMAGE), onClick = choose)
            if (reference != null) BootstrapButton(t(DesktopUiText.CLEAR_IMAGE), onClick = clear)
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorHeading(title)
        content()
    }
}

@Composable
private fun EditorActions(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun EditorField(label: String, value: String, multiline: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = if (multiline) 70.dp else 36.dp)
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
            .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp)).padding(9.dp),
            textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
            singleLine = !multiline)
    }
}

@Composable
private fun EditorHeading(text: String) {
    BasicText(text, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 18.sp))
}

private fun CharacterEditorProblem.uiText(): DesktopUiText = when (this) {
    CharacterEditorProblem.NAME_REQUIRED -> DesktopUiText.CHARACTER_NAME_REQUIRED
    CharacterEditorProblem.GREETING_REQUIRED -> DesktopUiText.CHARACTER_GREETING_REQUIRED
    CharacterEditorProblem.CHARACTER_NAME_REQUIRED -> DesktopUiText.CHARACTER_ENTRY_NAME_REQUIRED
    CharacterEditorProblem.DUPLICATE_CHARACTER_NAME -> DesktopUiText.CHARACTER_DUPLICATE_ENTRY
    CharacterEditorProblem.DUPLICATE_CARD_NAME -> DesktopUiText.CHARACTER_DUPLICATE_NAME
    CharacterEditorProblem.SOURCE_CHANGED -> DesktopUiText.CHARACTER_SOURCE_CHANGED
    CharacterEditorProblem.SOURCE_DELETED -> DesktopUiText.CHARACTER_SOURCE_DELETED
    CharacterEditorProblem.COMMUNITY_READ_ONLY -> DesktopUiText.COMMUNITY_READ_ONLY
    CharacterEditorProblem.SAVE_FAILED -> DesktopUiText.CHARACTER_SAVE_FAILED
    CharacterEditorProblem.DRAFT_FAILED -> DesktopUiText.CHARACTER_DRAFT_FAILED
    CharacterEditorProblem.RESOURCE_FAILED -> DesktopUiText.CHARACTER_RESOURCE_FAILED
    CharacterEditorProblem.NEW_DRAFT_EXISTS -> DesktopUiText.CHARACTER_NEW_DRAFT_EXISTS
    CharacterEditorProblem.DOCUMENT_READ_FAILED -> DesktopUiText.CHARACTER_DOCUMENT_READ_FAILED
    CharacterEditorProblem.SAVE_COMMITTED_WARNING -> DesktopUiText.CHARACTER_SAVE_COMMITTED_WARNING
    CharacterEditorProblem.CLEAN_DRAFT_WARNING -> DesktopUiText.CHARACTER_CLEAN_DRAFT_WARNING
}
