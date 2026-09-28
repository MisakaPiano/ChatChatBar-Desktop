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
import androidx.compose.ui.graphics.Color
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

private enum class ManageSection { TRANSFER, MODELS, SETTINGS }

@Composable
internal fun DesktopManagePanel(
    transferController: DesktopTypedTransferController,
    modelSettingsController: DesktopModelSettingsController,
    uiLanguageController: DesktopUiLanguageController,
    appearanceController: DesktopAppearanceController,
) {
    val t = LocalDesktopUiStrings.current
    val uiLanguage by uiLanguageController.language.collectAsState()
    val uiLanguageError by uiLanguageController.error.collectAsState()
    val appearance by appearanceController.state.collectAsState()
    var section by remember { mutableStateOf(ManageSection.TRANSFER) }
    val state by modelSettingsController.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(section, modelSettingsController) {
        when (section) {
            ManageSection.TRANSFER -> Unit
            ManageSection.MODELS -> modelSettingsController.loadModels()
            ManageSection.SETTINGS -> Unit
        }
    }
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
                    ManageSection.MODELS -> DesktopUiText.MODELS
                    ManageSection.SETTINGS -> DesktopUiText.SETTINGS
                }), secondary = section != choice) {
                    section = choice
                }
            }
        }
        if (state.busy) StatusText(t(DesktopUiText.WORKING))
        state.status?.let { StatusText(t.status(it)) }
        state.error?.let { StatusText(t.status(it), DesktopBootstrapColors.destructive) }
        when (section) {
            ManageSection.TRANSFER -> DesktopTypedTransferPanel(transferController)
            ManageSection.MODELS -> DesktopModelsPanel(state, modelSettingsController) { action -> scope.launch { action() } }
            ManageSection.SETTINGS -> {
                ManageHeading(t(DesktopUiText.LANGUAGE))
                ActionRow {
                    BootstrapButton(t(DesktopUiText.CHINESE), secondary = uiLanguage != DesktopUiLanguage.ZH_CN) {
                        scope.launch { uiLanguageController.select(DesktopUiLanguage.ZH_CN) }
                    }
                    BootstrapButton(t(DesktopUiText.ENGLISH), secondary = uiLanguage != DesktopUiLanguage.EN) {
                        scope.launch { uiLanguageController.select(DesktopUiLanguage.EN) }
                    }
                }
                uiLanguageError?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                DesktopAppearanceSettings(appearance, appearanceController) { action -> scope.launch { action() } }
                DesktopCoreSettingsPanel(state, modelSettingsController) { action -> scope.launch { action() } }
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
            BootstrapButton(label, secondary = state.themeMode != mode) { launch { controller.setMode(mode) } }
        }
    }
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
    state.error?.let { StatusText(it, colors.destructive) }
}

@Composable
private fun DesktopModelsPanel(
    state: DesktopModelSettingsState,
    controller: DesktopModelSettingsController,
    launch: (suspend () -> Unit) -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    val bundled = state.bundledCatalog
    ManageHeading(t(DesktopUiText.CHAT_MODELS))
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
        BootstrapButton(t(DesktopUiText.CREATE_MODEL)) { controller.startCreate() }
        BootstrapButton(t(DesktopUiText.REFRESH), secondary = true) { launch { controller.loadModels() } }
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
            StatusText(model.baseUrl)
            ActionRow {
                BootstrapButton(t(DesktopUiText.EDIT), secondary = true) { launch { controller.startEdit(model.id) } }
                BootstrapButton(t(DesktopUiText.DUPLICATE), secondary = true) { launch { controller.duplicateModel(model.id) } }
                BootstrapButton(t(DesktopUiText.DELETE), secondary = true) { pendingDeleteId = model.id }
            }
            if (pendingDeleteId == model.id) {
                StatusText(t(DesktopUiText.DELETE_MODEL_CONFIRM))
                ActionRow {
                    BootstrapButton(t(DesktopUiText.CONFIRM_DELETE)) {
                        pendingDeleteId = null
                        launch { controller.deleteModel(model.id) }
                    }
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { pendingDeleteId = null }
                }
            }
        }
    }
    state.editor?.let { draft ->
        ManageHeading(if (draft.id == null) t(DesktopUiText.NEW_MODEL) else "${t(DesktopUiText.EDIT_MODEL)} · ${draft.id}")
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
        state.discoveryError?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        if (state.discoveredModelIds.isNotEmpty()) {
            var query by remember { mutableStateOf("") }
            LabeledField(t(DesktopUiText.FILTER_IDS), query) { query = it }
            StatusText("${state.discoveredModelIds.size} Model ID · ${t(DesktopUiText.DISCOVERY_NOTE)}")
            state.discoveredModelIds.filter { it.contains(query, ignoreCase = true) }.take(50).forEach { id ->
                BootstrapButton(id, secondary = true) { controller.selectDiscoveredModel(id) }
            }
        }
        ChoiceField(t(DesktopUiText.TEMPLATE), ModelTemplate.entries, draft.templateType) { controller.applyTemplate(it) }
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
        ActionRow {
            BootstrapButton(t(DesktopUiText.SAVE_MODEL), enabled = !state.busy) { launch { controller.saveModel() } }
            BootstrapButton(t(DesktopUiText.CLOSE_EDITOR), secondary = true) { controller.closeEditor() }
        }
    }
}

@Composable
private fun DesktopCoreSettingsPanel(
    state: DesktopModelSettingsState,
    controller: DesktopModelSettingsController,
    launch: (suspend () -> Unit) -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val draft = state.settings ?: run {
        StatusText(t(DesktopUiText.SETTINGS_NOT_LOADED))
        BootstrapButton(t(DesktopUiText.LOAD_SETTINGS)) { launch { controller.loadSettings() } }
        return
    }
    ManageHeading(t(DesktopUiText.CORE_CHAT_SETTINGS))
    StatusText(t(DesktopUiText.DEFAULT_CHAT_MODEL))
    BootstrapButton(t(DesktopUiText.AUTOMATIC_FIRST), secondary = draft.defaultModelId != null) {
        controller.editSettings { it.copy(defaultModelId = null) }
    }
    state.availableChatModels.forEach { model ->
        BootstrapButton("${model.displayName} · ${model.modelName}", secondary = draft.defaultModelId != model.id) {
            controller.editSettings { it.copy(defaultModelId = model.id) }
        }
    }
    if (draft.defaultModelId != null && state.availableChatModels.none { it.id == draft.defaultModelId }) {
        StatusText("${t(DesktopUiText.SELECTED_UNAVAILABLE)}: ${draft.defaultModelId}", DesktopBootstrapColors.warning)
    }
    ToggleField(t(DesktopUiText.ALLOW_CLEARTEXT), draft.allowCleartextModelApi) {
        controller.editSettings { it.copy(allowCleartextModelApi = !it.allowCleartextModelApi) }
    }
    StatusText(t(DesktopUiText.CLEARTEXT_NOTE))
    LabeledField(t(DesktopUiText.CONTEXT_WINDOW), draft.defaultContextWindowSize) { value ->
        controller.editSettings { it.copy(defaultContextWindowSize = value.filter(Char::isDigit)) }
    }
    StatusText(t(DesktopUiText.DEFAULT_FORMAT_CARD))
    BootstrapButton(t(DesktopUiText.NONE), secondary = draft.defaultFormatCardId != null) {
        controller.editSettings { it.copy(defaultFormatCardId = null) }
    }
    state.formatCards.forEach { (id, name) ->
        BootstrapButton(name, secondary = draft.defaultFormatCardId != id) {
            controller.editSettings { it.copy(defaultFormatCardId = id) }
        }
    }
    if (draft.defaultFormatCardId != null && state.formatCards.none { it.first == draft.defaultFormatCardId }) {
        StatusText("${t(DesktopUiText.SELECTED_UNAVAILABLE)}: ${draft.defaultFormatCardId}", DesktopBootstrapColors.warning)
    }
    ToggleField(t(DesktopUiText.SEGMENTED_BUBBLES), draft.assistantSegmentedBubblesEnabled) {
        controller.editSettings { it.copy(assistantSegmentedBubblesEnabled = !it.assistantSegmentedBubblesEnabled) }
    }
    ManageHeading(t(DesktopUiText.MODEL_CONNECTION))
    state.bundledCatalog?.let { StatusText("${it.provider} · ${it.baseUrl}") }
    CredentialField(
        label = t(DesktopUiText.GLOBAL_API_KEY),
        hasSavedValue = draft.hasSavedFallbackCredential,
        edit = draft.fallbackCredentialEdit,
        onKeep = controller::keepFallbackCredential,
        onReplace = controller::replaceFallbackCredential,
        onClear = controller::clearFallbackCredential,
    )
    StatusText(t(DesktopUiText.KEY_USAGE_NOTE))
    BootstrapButton(t(DesktopUiText.SAVE_CHAT_SETTINGS), enabled = !state.busy) { launch { controller.saveAppSettings() } }
    ManageHeading(t(DesktopUiText.PLAYER))
    LabeledField(t(DesktopUiText.PLAYER_NAME), draft.playerName) { value ->
        controller.editSettings { it.copy(playerName = value) }
    }
    LabeledField(t(DesktopUiText.PLAYER_PERSONA), draft.playerPersona, multiline = true) { value ->
        controller.editSettings { it.copy(playerPersona = value) }
    }
    BootstrapButton(t(DesktopUiText.SAVE_PLAYER), enabled = !state.busy) { launch { controller.savePlayerSetting() } }
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
    BootstrapButton("$label: ${t(if (value) DesktopUiText.ON else DesktopUiText.OFF)}", secondary = !value, onClick = onToggle)
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
            BootstrapButton(labelFor(choice), secondary = choice != selected) { onSelect(choice) }
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
