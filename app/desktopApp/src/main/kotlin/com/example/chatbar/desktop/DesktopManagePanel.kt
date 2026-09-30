package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.OutputTokenParameter
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.appearance.ThemeColorHsv
import kotlinx.coroutines.launch

private enum class ManageSection { TRANSFER, CHARACTERS, FORMATS, WORLD_BOOKS, MODELS, SETTINGS }
private enum class ManageSettingsEditor { CHAT_DEFAULTS, PLAYER }

@Composable
internal fun DesktopManagePanel(
    transferController: DesktopTypedTransferController,
    modelSettingsController: DesktopModelSettingsController,
    characterEditorController: DesktopCharacterEditorController,
    formatCardEditorController: DesktopFormatCardEditorController,
    worldBookEditorController: DesktopWorldBookEditorController,
    uiLanguageController: DesktopUiLanguageController,
    appearanceController: DesktopAppearanceController,
    formatPresetController: DesktopFormatPresetController,
    connectionTestController: DesktopConnectionTestController,
) {
    val t = LocalDesktopUiStrings.current
    val uiLanguage by uiLanguageController.language.collectAsState()
    val uiLanguageError by uiLanguageController.error.collectAsState()
    val appearance by appearanceController.state.collectAsState()
    val languageSaved by uiLanguageController.saved.collectAsState()
    var section by remember { mutableStateOf(ManageSection.TRANSFER) }
    var settingsEditor by remember { mutableStateOf<ManageSettingsEditor?>(null) }
    var confirmClearCredential by remember { mutableStateOf(false) }
    val state by modelSettingsController.state.collectAsState()
    val characterState by characterEditorController.state.collectAsState()
    val formatState by formatCardEditorController.state.collectAsState()
    val worldState by worldBookEditorController.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(section, modelSettingsController) {
        when (section) {
            ManageSection.TRANSFER -> Unit
            ManageSection.CHARACTERS -> characterEditorController.load()
            ManageSection.FORMATS -> formatCardEditorController.load()
            ManageSection.WORLD_BOOKS -> worldBookEditorController.load()
            ManageSection.MODELS -> {
                modelSettingsController.loadModels()
                formatPresetController.load()
            }
            ManageSection.SETTINGS -> modelSettingsController.loadSettings()
        }
    }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize()
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(14.dp))
            .background(DesktopBootstrapColors.card, RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ManageHeading(t(DesktopUiText.MANAGE))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManageSection.entries.forEach { choice ->
                BootstrapButton(t(when (choice) {
                    ManageSection.TRANSFER -> DesktopUiText.TRANSFER
                    ManageSection.CHARACTERS -> DesktopUiText.CHARACTERS
                    ManageSection.FORMATS -> DesktopUiText.FORMATS
                    ManageSection.WORLD_BOOKS -> DesktopUiText.WORLD_BOOKS
                    ManageSection.MODELS -> DesktopUiText.MODELS
                    ManageSection.SETTINGS -> DesktopUiText.SETTINGS
                }), secondary = section != choice) {
                    if (section == ManageSection.WORLD_BOOKS && choice != ManageSection.WORLD_BOOKS) {
                        worldBookEditorController.requestLeave { section = choice }
                    } else if (section == ManageSection.FORMATS && choice != ManageSection.FORMATS) {
                        formatCardEditorController.requestLeave { section = choice }
                    } else if (section == ManageSection.CHARACTERS && choice != ManageSection.CHARACTERS) {
                        characterEditorController.requestLeave { section = choice }
                    } else if (section == ManageSection.MODELS && choice != ManageSection.MODELS) {
                        scope.launch { modelSettingsController.requestLeave { section = choice } }
                    } else section = choice
                }
            }
        }
        if (state.busy) StatusText(t(DesktopUiText.WORKING))
        state.status?.let { StatusText(t.status(it)) }
        state.error?.let { StatusText(t.status(it), DesktopBootstrapColors.destructive) }
        when (section) {
            ManageSection.TRANSFER -> DesktopTypedTransferPanel(transferController)
            ManageSection.CHARACTERS -> DesktopCharacterManagementPanel(characterEditorController)
            ManageSection.FORMATS -> DesktopFormatCardManagementPanel(formatCardEditorController)
            ManageSection.WORLD_BOOKS -> DesktopWorldBookManagementPanel(worldBookEditorController)
            ManageSection.MODELS -> DesktopModelsPanel(state, modelSettingsController, formatPresetController) { action -> scope.launch { action() } }
            ManageSection.SETTINGS -> {
                ManageHeading(t(DesktopUiText.LANGUAGE))
                ActionRow {
                    SelectChip(t(DesktopUiText.CHINESE), uiLanguage == DesktopUiLanguage.ZH_CN) {
                        scope.launch { uiLanguageController.select(DesktopUiLanguage.ZH_CN) }
                    }
                    SelectChip(t(DesktopUiText.ENGLISH), uiLanguage == DesktopUiLanguage.EN) {
                        scope.launch { uiLanguageController.select(DesktopUiLanguage.EN) }
                    }
                }
                uiLanguageError?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                if (languageSaved) StatusText(t(DesktopUiText.SAVED))
                DesktopAppearanceSettings(appearance, appearanceController) { action -> scope.launch { action() } }
                DesktopCoreSettingsPanel(
                    state = state,
                    onChatDefaults = { settingsEditor = ManageSettingsEditor.CHAT_DEFAULTS },
                    onPlayer = { settingsEditor = ManageSettingsEditor.PLAYER },
                    onCredential = modelSettingsController::openCredentialEditor,
                    onClearCredential = { confirmClearCredential = true },
                    connectionTestController = connectionTestController,
                )
            }
        }
    }
    if (section == ManageSection.MODELS && state.editor != null) {
        DesktopModelEditorOverlay(state, modelSettingsController, formatPresetController) { action -> scope.launch { action() } }
    }
    if (section == ManageSection.CHARACTERS && characterState.card != null) {
        DesktopCharacterEditorOverlay(characterEditorController, characterState)
    }
    if (section == ManageSection.FORMATS && formatState.card != null) {
        DesktopFormatCardEditorOverlay(formatCardEditorController)
    }
    if (section == ManageSection.WORLD_BOOKS && worldState.book != null) {
        DesktopWorldBookEditorOverlay(worldBookEditorController)
    }
    DesktopCharacterLeavePrompt(characterEditorController)
    DesktopFormatCardLeavePrompt(formatCardEditorController)
    DesktopWorldBookLeavePrompt(worldBookEditorController)
    settingsEditor?.let { active ->
        DesktopSettingsDraftOverlay(active, state, modelSettingsController,
            onClose = { scope.launch { modelSettingsController.requestLeave { settingsEditor = null } } },
            onSaved = { settingsEditor = null },
            launch = { action -> scope.launch { action() } })
    }
    if (state.credentialEditorOpen) {
        DesktopCredentialOverlay(state, modelSettingsController,
            onClose = { scope.launch { modelSettingsController.requestLeave { modelSettingsController.discardCredentialEditor() } } },
            launch = { action -> scope.launch { action() } })
    }
    if (confirmClearCredential) {
        DesktopConfirmationOverlay(t(DesktopUiText.CLEAR),
            onConfirm = {
                confirmClearCredential = false
                scope.launch { modelSettingsController.clearFallbackCredentialImmediately() }
            }, onCancel = { confirmClearCredential = false })
    }
    if (state.leavePrompt) {
        DesktopLeavePrompt(
            onSave = { scope.launch { modelSettingsController.resolveLeave(save = true) } },
            onDiscard = { scope.launch { modelSettingsController.resolveLeave(save = false) } },
            onContinue = modelSettingsController::continueEditing,
        )
    }
    }
}

@Composable
private fun DesktopModelEditorOverlay(
    state: DesktopModelSettingsState,
    controller: DesktopModelSettingsController,
    formatPresetController: DesktopFormatPresetController,
    launch: (suspend () -> Unit) -> Unit,
) {
    val draft = state.editor ?: return
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    Column(
        Modifier.fillMaxSize().background(colors.overlay)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (desktopModelEditorCommand(event.key, event.isCtrlPressed)) {
                    DesktopModelEditorCommand.SAVE -> {
                        if (state.editorDirty && !state.busy) launch { controller.saveModel() }
                        true
                    }
                    DesktopModelEditorCommand.CLOSE -> { controller.closeEditor(); true }
                    else -> false
                }
            }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            ManageHeading(if (draft.id == null) t(DesktopUiText.NEW_MODEL)
                else "${t(DesktopUiText.EDIT_MODEL)}: ${draft.displayName}")
            BootstrapButton(t(DesktopUiText.CLOSE), variant = DesktopActionVariant.GHOST) { controller.closeEditor() }
        }
        if (state.editorDirty) StatusText("● ${t(DesktopUiText.UNSAVED_CHANGES)}", colors.warning)
        state.status?.let { StatusText(t.status(it)) }
        state.error?.let { StatusText(t.status(it), colors.destructive) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DesktopModelsPanel(state, controller, formatPresetController, editorOnly = true, launch = launch)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BootstrapButton(t(DesktopUiText.CANCEL), variant = DesktopActionVariant.GHOST) { controller.closeEditor() }
            BootstrapButton(t(DesktopUiText.SAVE_CHANGES), enabled = state.editorDirty && !state.busy) {
                launch { controller.saveModel() }
            }
        }
    }
}

@Composable
private fun DesktopLeavePrompt(onSave: () -> Unit, onDiscard: () -> Unit, onContinue: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    Box(Modifier.fillMaxSize().background(colors.dim).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManageHeading(t(DesktopUiText.UNSAVED_CHANGES))
            ActionRow {
                BootstrapButton(t(DesktopUiText.SAVE_AND_LEAVE), onClick = onSave)
                BootstrapButton(t(DesktopUiText.DISCARD_CHANGES), variant = DesktopActionVariant.DESTRUCTIVE, onClick = onDiscard)
                BootstrapButton(t(DesktopUiText.CONTINUE_EDITING), variant = DesktopActionVariant.GHOST, onClick = onContinue)
            }
        }
    }
}

@Composable
private fun DesktopSettingsDraftOverlay(
    kind: ManageSettingsEditor,
    state: DesktopModelSettingsState,
    controller: DesktopModelSettingsController,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    launch: (suspend () -> Unit) -> Unit,
) {
    val draft = state.settings ?: return
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    val dirty = if (kind == ManageSettingsEditor.CHAT_DEFAULTS) state.chatDefaultsDirty else state.playerDirty
    Column(Modifier.fillMaxSize().background(colors.overlay).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ManageHeading(t(if (kind == ManageSettingsEditor.CHAT_DEFAULTS) DesktopUiText.CHAT_DEFAULTS
                else DesktopUiText.PLAYER_SETTING))
            BootstrapButton(t(DesktopUiText.CLOSE), variant = DesktopActionVariant.GHOST, onClick = onClose)
        }
        if (dirty) StatusText("● ${t(DesktopUiText.UNSAVED_CHANGES)}", colors.warning)
        state.error?.let { StatusText(t.status(it), colors.destructive) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (kind == ManageSettingsEditor.CHAT_DEFAULTS) {
                ToggleField(t(DesktopUiText.ALLOW_CLEARTEXT), draft.allowCleartextModelApi) {
                    controller.editSettings { it.copy(allowCleartextModelApi = !it.allowCleartextModelApi) }
                }
                StatusText(t(DesktopUiText.CLEARTEXT_NOTE))
                LabeledField(t(DesktopUiText.CONTEXT_WINDOW), draft.defaultContextWindowSize) { value ->
                    controller.editSettings { it.copy(defaultContextWindowSize = value.filter(Char::isDigit)) }
                }
                StatusText(t(DesktopUiText.DEFAULT_FORMAT_CARD))
                SelectChip(t(DesktopUiText.NONE), draft.defaultFormatCardId == null) {
                    controller.editSettings { it.copy(defaultFormatCardId = null) }
                }
                state.formatCards.forEach { (id, name) ->
                    SelectChip(name, draft.defaultFormatCardId == id) {
                        controller.editSettings { it.copy(defaultFormatCardId = id) }
                    }
                }
                if (draft.defaultFormatCardId != null && state.formatCards.none { it.first == draft.defaultFormatCardId }) {
                    StatusText("${t(DesktopUiText.SELECTED_UNAVAILABLE)}: ${draft.defaultFormatCardId}", colors.warning)
                }
                ToggleField(t(DesktopUiText.SEGMENTED_BUBBLES), draft.assistantSegmentedBubblesEnabled) {
                    controller.editSettings { it.copy(assistantSegmentedBubblesEnabled = !it.assistantSegmentedBubblesEnabled) }
                }
            } else {
                LabeledField(t(DesktopUiText.PLAYER_NAME), draft.playerName) { value ->
                    controller.editSettings { it.copy(playerName = value) }
                }
                LabeledField(t(DesktopUiText.PLAYER_PERSONA), draft.playerPersona, multiline = true) { value ->
                    controller.editSettings { it.copy(playerPersona = value) }
                }
            }
        }
        ActionRow {
            BootstrapButton(t(DesktopUiText.CANCEL), variant = DesktopActionVariant.GHOST, onClick = onClose)
            BootstrapButton(t(DesktopUiText.SAVE_CHANGES), enabled = dirty && !state.busy) {
                launch {
                    if (kind == ManageSettingsEditor.CHAT_DEFAULTS) controller.saveAppSettings()
                    else controller.savePlayerSetting()
                    if (controller.state.value.error == null) onSaved()
                }
            }
        }
    }
}

@Composable
private fun DesktopCredentialOverlay(
    state: DesktopModelSettingsState,
    controller: DesktopModelSettingsController,
    onClose: () -> Unit,
    launch: (suspend () -> Unit) -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(colors.overlay).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ManageHeading(t(DesktopUiText.GLOBAL_API_KEY))
        state.error?.let { StatusText(t.status(it), colors.destructive) }
        LabeledField(t(DesktopUiText.NEW_KEY), input, secret = true) {
            input = it
            controller.editCredentialDraft(it)
        }
        ActionRow {
            BootstrapButton(t(DesktopUiText.CANCEL), variant = DesktopActionVariant.GHOST, onClick = onClose)
            BootstrapButton(t(DesktopUiText.SAVE_KEY), enabled = state.credentialDirty && !state.busy) {
                launch { controller.saveCredentialDraft() }
            }
        }
    }
}

@Composable
private fun DesktopConfirmationOverlay(label: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    Box(Modifier.fillMaxSize().background(colors.dim).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(12.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManageHeading(label)
            ActionRow {
                BootstrapButton(t(DesktopUiText.CONFIRM), variant = DesktopActionVariant.DESTRUCTIVE, onClick = onConfirm)
                BootstrapButton(t(DesktopUiText.CANCEL), variant = DesktopActionVariant.GHOST, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun DesktopAppearanceSettings(
    state: DesktopAppearanceState,
    controller: DesktopAppearanceController,
    launch: (suspend () -> Unit) -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    ManageHeading(t(DesktopUiText.APPEARANCE_DISPLAY))
    StatusText(t(DesktopUiText.THEME_MODE))
    ActionRow {
        ThemeMode.entries.forEach { mode ->
            val label = t(when (mode) {
                ThemeMode.SYSTEM -> DesktopUiText.FOLLOW_SYSTEM
                ThemeMode.LIGHT -> DesktopUiText.LIGHT
                ThemeMode.DARK -> DesktopUiText.DARK
            })
            SelectChip(label, state.themeMode == mode) { launch { controller.setMode(mode) } }
        }
    }
    StatusText(t(DesktopUiText.COLOR_STYLE))
    ActionRow {
        DesktopColorStyle.entries.forEach { style ->
            SelectChip(t(when (style) {
                DesktopColorStyle.NEUTRAL -> DesktopUiText.COLOR_NEUTRAL
                DesktopColorStyle.CCB_NATIVE -> DesktopUiText.COLOR_CCB_NATIVE
                DesktopColorStyle.CUSTOM_ACCENT -> DesktopUiText.COLOR_CUSTOM_ACCENT
            }), state.colorStyle == style) { launch { controller.setColorStyle(style) } }
        }
    }
    if (state.colorStyle == DesktopColorStyle.CUSTOM_ACCENT) {
    StatusText(t(DesktopUiText.THEME_COLOR))
    val presets = listOf(
        state.themeColor,
        DefaultThemeColorHsv,
        ThemeColorHsv.fromRgb(0.23f, 0.40f, 0.90f),
        ThemeColorHsv.fromRgb(0.51f, 0.33f, 0.82f),
        ThemeColorHsv.fromRgb(0.82f, 0.34f, 0.28f),
        ThemeColorHsv.fromRgb(0.85f, 0.58f, 0.17f),
    ).distinctBy(ThemeColorHsv::rgbKey)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { swatch ->
            Box(
                Modifier.size(36.dp)
                    .background(Color(swatch.toOpaqueArgb()), RoundedCornerShape(8.dp))
                    .border(2.dp, if (swatch.rgbKey() == state.themeColor.rgbKey()) colors.foreground else colors.border,
                        RoundedCornerShape(8.dp))
                    .clickable { launch { controller.setColor(swatch) } },
            )
        }
    }
    BootstrapButton(t(DesktopUiText.RESTORE_DEFAULT_COLOR), secondary = true) {
        launch { controller.setColor(DefaultThemeColorHsv) }
    }
    }
    if (state.saved) StatusText(t(DesktopUiText.SAVED))
    state.error?.let { StatusText(t.status(it), colors.destructive) }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DesktopBootstrapColors
    Box(Modifier.background(if (selected) colors.accent else colors.card, RoundedCornerShape(8.dp))
        .border(1.dp, if (selected) colors.primary else colors.border, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        BasicText(label, style = TextStyle(color = colors.foreground, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal))
    }
}

@Composable
private fun DesktopModelsPanel(
    state: DesktopModelSettingsState,
    controller: DesktopModelSettingsController,
    formatPresetController: DesktopFormatPresetController,
    editorOnly: Boolean = false,
    launch: (suspend () -> Unit) -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    var showFormatPresets by remember { mutableStateOf(false) }
    var showTemplateChoices by remember { mutableStateOf(false) }
    val formatState by formatPresetController.state.collectAsState()
    val bundled = state.bundledCatalog
    if (!editorOnly) {
    ManageHeading(t(DesktopUiText.CHAT_MODELS))
    ManageHeading(t(DesktopUiText.CURRENT_DEFAULT_MODEL))
    DesktopModelEvidence(state.defaultDiagnostic, session = false)
    BootstrapButton(t(DesktopUiText.USE_AUTOMATIC), variant = DesktopActionVariant.SECONDARY,
        enabled = state.defaultDiagnostic?.configuredId != null && !state.busy) {
        launch { controller.setDefaultModel(null) }
    }
    BootstrapButton("${if (showPresets) "▾" else "▸"} ${t(DesktopUiText.BUILT_IN_MODELS)}" +
        (bundled?.let { " · ${it.provider} · ${it.chatModels.size} ${t(DesktopUiText.CHAT_MODEL_COUNT)}" } ?: ""),
        secondary = true) { showPresets = !showPresets }
    if (showPresets && bundled != null) {
        StatusText("${bundled.provider} · ${bundled.baseUrl}")
        bundled.chatModels.forEach { model -> StatusText("${model.displayName} · ${model.modelName}") }
        bundled.embeddingModelName?.let { StatusText("${t(DesktopUiText.EMBEDDING_MODEL)} · $it") }
        StatusText(t(DesktopUiText.PRESET_RESTORE_NOTE))
        BootstrapButton(t(DesktopUiText.RESTORE_BUILT_IN), enabled = !state.busy) {
            launch { controller.restoreBundledModels() }
        }
    }
    ActionRow {
        BootstrapButton(t(DesktopUiText.ADD_MODEL)) { showTemplateChoices = !showTemplateChoices }
        BootstrapButton(t(DesktopUiText.REFRESH), secondary = true) { launch { controller.loadModels() } }
    }
    if (showTemplateChoices) {
        StatusText(t(DesktopUiText.TEMPLATE))
        ActionRow {
            ModelTemplate.entries.forEach { template ->
                BootstrapButton(t(template.uiText()), variant = DesktopActionVariant.SECONDARY) {
                    showTemplateChoices = false
                    controller.startCreate(template)
                }
            }
        }
    }
    if (state.models.isEmpty()) StatusText(t(DesktopUiText.NO_SAVED_MODELS))
    state.models.forEach { model ->
        Column(
            Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ManageHeading(model.displayName)
            StatusText("${model.modelName} · ${t(if (model.preset) DesktopUiText.PRESET else DesktopUiText.CUSTOM)} · ${model.id}")
            if (state.defaultDiagnostic?.effectiveId == model.id) {
                StatusText(t(if (state.defaultDiagnostic.selection == DesktopModelSelection.EXPLICIT)
                    DesktopUiText.DEFAULT_CHAT_MODEL else DesktopUiText.CURRENT_EFFECTIVE))
            }
            StatusText(t(model.templateType.uiText()))
            StatusText(model.baseUrl)
            ActionRow {
                BootstrapButton(t(DesktopUiText.EDIT), secondary = true) { launch { controller.startEdit(model.id) } }
                BootstrapButton(t(DesktopUiText.SET_DEFAULT), variant = DesktopActionVariant.SECONDARY,
                    enabled = model.selectableForChat && state.defaultDiagnostic?.configuredId != model.id && !state.busy) {
                    launch { controller.setDefaultModel(model.id) }
                }
                BootstrapButton(t(DesktopUiText.DUPLICATE), secondary = true) { launch { controller.duplicateModel(model.id) } }
                BootstrapButton(t(DesktopUiText.DELETE), variant = DesktopActionVariant.DESTRUCTIVE) { pendingDeleteId = model.id }
            }
            if (pendingDeleteId == model.id) {
                StatusText(t(DesktopUiText.DELETE_MODEL_CONFIRM))
                ActionRow {
                    BootstrapButton(t(DesktopUiText.CONFIRM_DELETE), variant = DesktopActionVariant.DESTRUCTIVE) {
                        pendingDeleteId = null
                        launch { controller.deleteModel(model.id) }
                    }
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { pendingDeleteId = null }
                }
            }
        }
    }
    BootstrapButton("${if (showFormatPresets) "▾" else "▸"} ${t(DesktopUiText.BUILT_IN_FORMATS)}",
        variant = DesktopActionVariant.GHOST) { showFormatPresets = !showFormatPresets }
    if (showFormatPresets) {
        formatState.entries.forEach { entry ->
            StatusText("${entry.displayName} · ${t(DesktopUiText.PRESET_VERSION)} ${entry.version}")
            BootstrapButton(t(DesktopUiText.IMPORT_RESTORE), variant = DesktopActionVariant.SECONDARY) {
                launch { formatPresetController.recover(entry); controller.refreshFormatChoices() }
            }
        }
        formatState.status?.let { StatusText(t.status(it)) }
        formatState.error?.let { StatusText(t.status(it), DesktopBootstrapColors.destructive) }
    }
    formatState.pendingEntry?.let { pending ->
        StatusText("${t(DesktopUiText.NAME_CONFLICT)}: ${pending.displayName}")
        ActionRow {
            BootstrapButton(t(DesktopUiText.OVERWRITE), variant = DesktopActionVariant.DESTRUCTIVE) {
                launch { formatPresetController.resolveConflict(overwrite = true); controller.refreshFormatChoices() }
            }
            BootstrapButton(t(DesktopUiText.IMPORT_AS_NEW), variant = DesktopActionVariant.SECONDARY) {
                launch { formatPresetController.resolveConflict(overwrite = false); controller.refreshFormatChoices() }
            }
            BootstrapButton(t(DesktopUiText.CANCEL), variant = DesktopActionVariant.GHOST) {
                formatPresetController.cancelConflict()
            }
        }
    }
    }
    if (editorOnly) state.editor?.let { draft ->
        LabeledField(t(DesktopUiText.DISPLAY_NAME), draft.displayName) { value ->
            controller.editModel { it.copy(displayName = value) }
        }
        LabeledField(t(DesktopUiText.BASE_URL), draft.baseUrl) { value -> controller.editModel { it.copy(baseUrl = value) } }
        LabeledField(t(DesktopUiText.MODEL_ID), draft.modelName) { value ->
            controller.editModel { it.copy(modelName = value) }
        }
        CredentialField(
            label = t(DesktopUiText.MODEL_API_KEY),
            hasSavedValue = draft.hasSavedCredential,
            edit = draft.credentialEdit,
            onKeep = controller::keepModelCredential,
            onReplace = controller::replaceModelCredential,
            onClear = controller::clearModelCredential,
        )
        BootstrapButton(t(DesktopUiText.DISCOVER_IDS), secondary = true, enabled = !state.discovering) {
            launch { controller.discoverModels() }
        }
        if (state.discovering) StatusText(t(DesktopUiText.DISCOVERING))
        state.discoveryError?.let { StatusText(t.status(it), DesktopBootstrapColors.destructive) }
        if (state.discoveredModelIds.isNotEmpty()) {
            var query by remember { mutableStateOf("") }
            LabeledField(t(DesktopUiText.FILTER_IDS), query) { query = it }
            StatusText("${state.discoveredModelIds.size} Model ID · ${t(DesktopUiText.DISCOVERY_NOTE)}")
            state.discoveredModelIds.filter { it.contains(query, ignoreCase = true) }.take(50).forEach { id ->
                BootstrapButton(id, secondary = true) { controller.selectDiscoveredModel(id) }
            }
        }
        ChoiceField(t(DesktopUiText.TEMPLATE), ModelTemplate.entries, draft.templateType,
            labelFor = { t(it.uiText()) }) { controller.applyTemplate(it) }
        ToggleField(t(DesktopUiText.SELECTABLE_CHAT), draft.selectableForChat) {
            controller.editModel { it.copy(selectableForChat = !it.selectableForChat) }
        }
        ToggleField(t(DesktopUiText.MULTIMODAL), draft.isMultimodal) {
            controller.editModel { it.copy(isMultimodal = !it.isMultimodal) }
        }
        if (draft.isMultimodal) {
            StatusText(t(DesktopUiText.VISION_CLEARED))
        } else {
            LabeledField(t(DesktopUiText.VISION_MODEL_ID), draft.visionModelId) { value ->
                controller.editModel { it.copy(visionModelId = value) }
            }
        }
        ChoiceField(t(DesktopUiText.THINKING), listOf<Boolean?>(null, true, false), draft.enableThinking,
            labelFor = { when (it) { null -> t(DesktopUiText.DEFAULT); true -> t(DesktopUiText.ON); false -> t(DesktopUiText.OFF) } }) { value ->
            controller.editModel { it.copy(enableThinking = value) }
        }
        LabeledField(t(DesktopUiText.REASONING_EFFORT), draft.reasoningEffort) { value ->
            controller.editModel { it.copy(reasoningEffort = value) }
        }
        LabeledField(t(DesktopUiText.MAX_OUTPUT_TOKENS), draft.maxOutputTokens) { value ->
            controller.editModel { it.copy(maxOutputTokens = value.filter(Char::isDigit)) }
        }
        ChoiceField(t(DesktopUiText.OUTPUT_TOKEN_PARAMETER), OutputTokenParameter.entries, draft.outputTokenParameter) { value ->
            controller.editModel { it.copy(outputTokenParameter = value) }
        }
        ChoiceField(t(DesktopUiText.FORMAT_PROMPT_POSITION), FormatPromptPosition.entries, draft.formatPromptPosition) { value ->
            controller.editModel { it.copy(formatPromptPosition = value) }
        }
        ToggleField(t(DesktopUiText.SUPPORTS_JSON), draft.supportsJsonMode) {
            controller.editModel { it.copy(supportsJsonMode = !it.supportsJsonMode) }
        }
        ToggleField(t(DesktopUiText.SUPPORTS_DISABLE_THINKING), draft.supportsDisableThinking) {
            controller.editModel { it.copy(supportsDisableThinking = !it.supportsDisableThinking) }
        }
        ManageHeading(t(DesktopUiText.CUSTOM_PARAMETERS))
        draft.customParams.forEachIndexed { index, param ->
            Column(
                Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                LabeledField(t(DesktopUiText.PARAMETER_NAME), param.key) { value ->
                    controller.editParameter(index) { it.copy(key = value) }
                }
                ChoiceField(t(DesktopUiText.TYPE), DesktopParameterKind.entries, param.kind) { value ->
                    controller.editParameter(index) { it.copy(kind = value) }
                }
                LabeledField(t(DesktopUiText.VALUE), param.value) { value ->
                    controller.editParameter(index) { it.copy(value = value) }
                }
                BootstrapButton(t(DesktopUiText.REMOVE_PARAMETER), secondary = true) { controller.removeParameter(index) }
            }
        }
        BootstrapButton(t(DesktopUiText.ADD_PARAMETER), secondary = true) { controller.addParameter() }
    }
}

@Composable
private fun DesktopCoreSettingsPanel(
    state: DesktopModelSettingsState,
    onChatDefaults: () -> Unit,
    onPlayer: () -> Unit,
    onCredential: () -> Unit,
    onClearCredential: () -> Unit,
    connectionTestController: DesktopConnectionTestController,
) {
    val t = LocalDesktopUiStrings.current
    val draft = state.settings ?: run {
        StatusText(t(DesktopUiText.SETTINGS_NOT_LOADED))
        return
    }
    ManageHeading(t(DesktopUiText.MODEL_CONNECTION))
    state.bundledCatalog?.let { StatusText("${it.provider} · ${it.baseUrl}") }
    StatusText("${t(DesktopUiText.GLOBAL_API_KEY)}: ${t(if (draft.hasSavedFallbackCredential)
        DesktopUiText.STORED_SECURELY else DesktopUiText.NOT_CONFIGURED)}")
    StatusText(t(DesktopUiText.KEY_USAGE_NOTE))
    ActionRow {
        BootstrapButton(t(DesktopUiText.REPLACE), variant = DesktopActionVariant.SECONDARY, onClick = onCredential)
        if (draft.hasSavedFallbackCredential) BootstrapButton(t(DesktopUiText.CLEAR),
            variant = DesktopActionVariant.DESTRUCTIVE, onClick = onClearCredential)
    }
    DesktopConnectionTestPanel(connectionTestController, state.credentialDirty)
    ManageHeading(t(DesktopUiText.CHAT_DEFAULTS))
    StatusText("${t(DesktopUiText.CONTEXT_WINDOW)}: ${draft.defaultContextWindowSize}")
    BootstrapButton(t(DesktopUiText.EDIT), variant = DesktopActionVariant.SECONDARY, onClick = onChatDefaults)
    ManageHeading(t(DesktopUiText.PLAYER_SETTING))
    StatusText(draft.playerName.ifBlank { t(DesktopUiText.NOT_CONFIGURED) })
    BootstrapButton(t(DesktopUiText.EDIT), variant = DesktopActionVariant.SECONDARY, onClick = onPlayer)
    state.status?.let { StatusText(t.status(it)) }
    state.error?.let { StatusText(t.status(it), DesktopBootstrapColors.destructive) }
}

@Composable
private fun CredentialField(
    label: String,
    hasSavedValue: Boolean,
    edit: DesktopCredentialEdit,
    onKeep: () -> Unit,
    onReplace: (String) -> Unit,
    onClear: () -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    StatusText("$label: ${t(if (hasSavedValue) DesktopUiText.STORED_SECURELY else DesktopUiText.NOT_CONFIGURED)}")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BootstrapButton(t(DesktopUiText.KEEP), secondary = edit !is DesktopCredentialEdit.Unchanged, onClick = onKeep)
        BootstrapButton(t(DesktopUiText.REPLACE), secondary = edit !is DesktopCredentialEdit.Replace) { onReplace("") }
        BootstrapButton(t(DesktopUiText.CLEAR), secondary = edit !is DesktopCredentialEdit.Clear, onClick = onClear)
    }
    if (edit is DesktopCredentialEdit.Replace) {
        LabeledField("${t(DesktopUiText.NEW_KEY)} · $label", edit.value, secret = true, onChange = onReplace)
    }
}

@Composable
private fun ToggleField(label: String, value: Boolean, onToggle: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    SelectChip("$label: ${t(if (value) DesktopUiText.ON else DesktopUiText.OFF)}", value, onToggle)
}

@Composable
private fun <T> ChoiceField(
    label: String,
    choices: List<T>,
    selected: T,
    labelFor: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    StatusText(label)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        choices.forEach { choice ->
            SelectChip(labelFor(choice), choice == selected) { onSelect(choice) }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    secret: Boolean = false,
    multiline: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        StatusText(label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp))
                .padding(10.dp),
            textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
            singleLine = !multiline,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

@Composable
private fun ManageHeading(text: String) {
    BasicText(
        text,
        style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    )
}

private fun ModelTemplate.uiText(): DesktopUiText = when (this) {
    ModelTemplate.OPENAI -> DesktopUiText.TEMPLATE_OPENAI
    ModelTemplate.CLAUDE -> DesktopUiText.TEMPLATE_CLAUDE
    ModelTemplate.GEMINI -> DesktopUiText.TEMPLATE_GEMINI
    ModelTemplate.CUSTOM -> DesktopUiText.TEMPLATE_CUSTOM
}
