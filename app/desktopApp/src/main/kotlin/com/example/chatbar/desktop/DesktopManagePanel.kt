package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.OutputTokenParameter
import kotlinx.coroutines.launch

private enum class ManageSection { TRANSFER, MODELS, SETTINGS }

@Composable
internal fun DesktopManagePanel(
    transferController: DesktopTypedTransferController,
    modelSettingsController: DesktopModelSettingsController,
) {
    var section by remember { mutableStateOf(ManageSection.TRANSFER) }
    val state by modelSettingsController.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(section, modelSettingsController) {
        when (section) {
            ManageSection.TRANSFER -> Unit
            ManageSection.MODELS -> modelSettingsController.loadModels()
            ManageSection.SETTINGS -> modelSettingsController.loadSettings()
        }
    }
    Column(
        Modifier.fillMaxSize()
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(14.dp))
            .background(DesktopBootstrapColors.card, RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ManageHeading("Manage")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManageSection.entries.forEach { choice ->
                BootstrapButton(choice.name.lowercase().replaceFirstChar(Char::uppercase), secondary = section != choice) {
                    section = choice
                }
            }
        }
        if (state.busy) StatusText("Working…")
        state.status?.let { StatusText(it) }
        state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        when (section) {
            ManageSection.TRANSFER -> DesktopTypedTransferPanel(transferController)
            ManageSection.MODELS -> DesktopModelsPanel(state, modelSettingsController) { action -> scope.launch { action() } }
            ManageSection.SETTINGS -> DesktopCoreSettingsPanel(state, modelSettingsController) { action -> scope.launch { action() } }
        }
    }
}

@Composable
private fun DesktopModelsPanel(
    state: DesktopModelSettingsState,
    controller: DesktopModelSettingsController,
    launch: (suspend () -> Unit) -> Unit,
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    ManageHeading("Chat models")
    ActionRow {
        BootstrapButton("Create model") { controller.startCreate() }
        BootstrapButton("Refresh", secondary = true) { launch { controller.loadModels() } }
    }
    if (state.models.isEmpty()) StatusText("No saved models")
    state.models.forEach { model ->
        Column(
            Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ManageHeading(model.displayName)
            StatusText("${model.modelName} · ${if (model.preset) "preset" else "custom"} · ${model.id}")
            StatusText(model.baseUrl)
            ActionRow {
                BootstrapButton("Edit", secondary = true) { launch { controller.startEdit(model.id) } }
                BootstrapButton("Duplicate", secondary = true) { launch { controller.duplicateModel(model.id) } }
                BootstrapButton("Delete", secondary = true) { pendingDeleteId = model.id }
            }
            if (pendingDeleteId == model.id) {
                StatusText("Delete this model? Its secure credential is removed by the repository.")
                ActionRow {
                    BootstrapButton("Confirm delete") {
                        pendingDeleteId = null
                        launch { controller.deleteModel(model.id) }
                    }
                    BootstrapButton("Cancel", secondary = true) { pendingDeleteId = null }
                }
            }
        }
    }
    state.editor?.let { draft ->
        ManageHeading(if (draft.id == null) "New model" else "Edit model · ${draft.id}")
        LabeledField("Display name", draft.displayName) { value ->
            controller.editModel { it.copy(displayName = value) }
        }
        LabeledField("Base URL", draft.baseUrl) { value -> controller.editModel { it.copy(baseUrl = value) } }
        LabeledField("Model ID (manual entry)", draft.modelName) { value ->
            controller.editModel { it.copy(modelName = value) }
        }
        CredentialField(
            label = "Model API key",
            hasSavedValue = draft.hasSavedCredential,
            edit = draft.credentialEdit,
            onKeep = controller::keepModelCredential,
            onReplace = controller::replaceModelCredential,
            onClear = controller::clearModelCredential,
        )
        BootstrapButton("Discover model IDs", secondary = true, enabled = !state.discovering) {
            launch { controller.discoverModels() }
        }
        if (state.discovering) StatusText("Discovering…")
        state.discoveryError?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        if (state.discoveredModelIds.isNotEmpty()) {
            var query by remember { mutableStateOf("") }
            LabeledField("Filter discovered IDs", query) { query = it }
            StatusText("${state.discoveredModelIds.size} model IDs returned; selection changes only Model ID")
            state.discoveredModelIds.filter { it.contains(query, ignoreCase = true) }.take(50).forEach { id ->
                BootstrapButton(id, secondary = true) { controller.selectDiscoveredModel(id) }
            }
        }
        ChoiceField("Template", ModelTemplate.entries, draft.templateType) { controller.applyTemplate(it) }
        ToggleField("Selectable for chat", draft.selectableForChat) {
            controller.editModel { it.copy(selectableForChat = !it.selectableForChat) }
        }
        ToggleField("Multimodal", draft.isMultimodal) {
            controller.editModel { it.copy(isMultimodal = !it.isMultimodal) }
        }
        if (draft.isMultimodal) {
            StatusText("Vision model ID is cleared for multimodal models")
        } else {
            LabeledField("Vision model ID", draft.visionModelId) { value ->
                controller.editModel { it.copy(visionModelId = value) }
            }
        }
        ChoiceField("Thinking", listOf<Boolean?>(null, true, false), draft.enableThinking,
            labelFor = { it?.toString() ?: "Default" }) { value ->
            controller.editModel { it.copy(enableThinking = value) }
        }
        LabeledField("Reasoning effort (blank = default)", draft.reasoningEffort) { value ->
            controller.editModel { it.copy(reasoningEffort = value) }
        }
        LabeledField("Max output tokens (blank = unset)", draft.maxOutputTokens) { value ->
            controller.editModel { it.copy(maxOutputTokens = value.filter(Char::isDigit)) }
        }
        ChoiceField("Output token parameter", OutputTokenParameter.entries, draft.outputTokenParameter) { value ->
            controller.editModel { it.copy(outputTokenParameter = value) }
        }
        ChoiceField("Format prompt position", FormatPromptPosition.entries, draft.formatPromptPosition) { value ->
            controller.editModel { it.copy(formatPromptPosition = value) }
        }
        ToggleField("Supports JSON mode", draft.supportsJsonMode) {
            controller.editModel { it.copy(supportsJsonMode = !it.supportsJsonMode) }
        }
        ToggleField("Supports disable-thinking", draft.supportsDisableThinking) {
            controller.editModel { it.copy(supportsDisableThinking = !it.supportsDisableThinking) }
        }
        ManageHeading("Custom parameters")
        draft.customParams.forEachIndexed { index, param ->
            Column(
                Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                LabeledField("Parameter name", param.key) { value ->
                    controller.editParameter(index) { it.copy(key = value) }
                }
                ChoiceField("Type", DesktopParameterKind.entries, param.kind) { value ->
                    controller.editParameter(index) { it.copy(kind = value) }
                }
                LabeledField("Value", param.value) { value ->
                    controller.editParameter(index) { it.copy(value = value) }
                }
                BootstrapButton("Remove parameter", secondary = true) { controller.removeParameter(index) }
            }
        }
        BootstrapButton("Add parameter", secondary = true) { controller.addParameter() }
        ActionRow {
            BootstrapButton("Save model", enabled = !state.busy) { launch { controller.saveModel() } }
            BootstrapButton("Close editor", secondary = true) { controller.closeEditor() }
        }
    }
}

@Composable
private fun DesktopCoreSettingsPanel(
    state: DesktopModelSettingsState,
    controller: DesktopModelSettingsController,
    launch: (suspend () -> Unit) -> Unit,
) {
    val draft = state.settings ?: run {
        StatusText("Settings not loaded")
        BootstrapButton("Load settings") { launch { controller.loadSettings() } }
        return
    }
    ManageHeading("Core chat settings")
    StatusText("Default chat model")
    BootstrapButton("Automatic (first available)", secondary = draft.defaultModelId != null) {
        controller.editSettings { it.copy(defaultModelId = null) }
    }
    state.availableChatModels.forEach { model ->
        BootstrapButton("${model.displayName} · ${model.modelName}", secondary = draft.defaultModelId != model.id) {
            controller.editSettings { it.copy(defaultModelId = model.id) }
        }
    }
    if (draft.defaultModelId != null && state.availableChatModels.none { it.id == draft.defaultModelId }) {
        StatusText("Selected model unavailable: ${draft.defaultModelId}", DesktopBootstrapColors.warning)
    }
    ToggleField("Allow explicit cleartext HTTP model endpoints", draft.allowCleartextModelApi) {
        controller.editSettings { it.copy(allowCleartextModelApi = !it.allowCleartextModelApi) }
    }
    StatusText("Enabling this permits explicitly configured http:// model APIs. A blank local-model key sends no Authorization header.")
    LabeledField("Default context-window size", draft.defaultContextWindowSize) { value ->
        controller.editSettings { it.copy(defaultContextWindowSize = value.filter(Char::isDigit)) }
    }
    StatusText("Default FormatCard")
    BootstrapButton("None", secondary = draft.defaultFormatCardId != null) {
        controller.editSettings { it.copy(defaultFormatCardId = null) }
    }
    state.formatCards.forEach { (id, name) ->
        BootstrapButton(name, secondary = draft.defaultFormatCardId != id) {
            controller.editSettings { it.copy(defaultFormatCardId = id) }
        }
    }
    if (draft.defaultFormatCardId != null && state.formatCards.none { it.first == draft.defaultFormatCardId }) {
        StatusText("Selected FormatCard unavailable: ${draft.defaultFormatCardId}", DesktopBootstrapColors.warning)
    }
    ToggleField("Segmented assistant bubbles", draft.assistantSegmentedBubblesEnabled) {
        controller.editSettings { it.copy(assistantSegmentedBubblesEnabled = !it.assistantSegmentedBubblesEnabled) }
    }
    CredentialField(
        label = "Global SiliconFlow / fallback API key",
        hasSavedValue = draft.hasSavedFallbackCredential,
        edit = draft.fallbackCredentialEdit,
        onKeep = controller::keepFallbackCredential,
        onReplace = controller::replaceFallbackCredential,
        onClear = controller::clearFallbackCredential,
    )
    BootstrapButton("Save chat settings", enabled = !state.busy) { launch { controller.saveAppSettings() } }
    ManageHeading("Player")
    LabeledField("Player name", draft.playerName) { value ->
        controller.editSettings { it.copy(playerName = value) }
    }
    LabeledField("Global persona", draft.playerPersona, multiline = true) { value ->
        controller.editSettings { it.copy(playerPersona = value) }
    }
    BootstrapButton("Save player setting", enabled = !state.busy) { launch { controller.savePlayerSetting() } }
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
    StatusText("$label: ${if (hasSavedValue) "stored securely" else "not set"}")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BootstrapButton("Keep", secondary = edit !is DesktopCredentialEdit.Unchanged, onClick = onKeep)
        BootstrapButton("Replace", secondary = edit !is DesktopCredentialEdit.Replace) { onReplace("") }
        BootstrapButton("Clear", secondary = edit !is DesktopCredentialEdit.Clear, onClick = onClear)
    }
    if (edit is DesktopCredentialEdit.Replace) {
        LabeledField("New $label", edit.value, secret = true, onChange = onReplace)
    }
}

@Composable
private fun ToggleField(label: String, value: Boolean, onToggle: () -> Unit) {
    BootstrapButton("$label: ${if (value) "On" else "Off"}", secondary = !value, onClick = onToggle)
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
                .background(DesktopBootstrapColors.background, RoundedCornerShape(8.dp))
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
