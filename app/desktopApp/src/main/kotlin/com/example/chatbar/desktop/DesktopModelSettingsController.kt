package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.OutputTokenParameter
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.ModelDiscoveryService
import com.example.chatbar.domain.model.resolveEffectiveModelApiKey
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Secret replacement is explicit. The hydrated value is never placed in editor field state. */
internal sealed interface DesktopCredentialEdit {
    data object Unchanged : DesktopCredentialEdit
    data class Replace(val value: String) : DesktopCredentialEdit
    data object Clear : DesktopCredentialEdit
}

internal enum class DesktopParameterKind { NUMBER, BOOLEAN, STRING }

internal data class DesktopParameterDraft(
    val key: String = "",
    val kind: DesktopParameterKind = DesktopParameterKind.STRING,
    val value: String = "",
)

internal data class DesktopModelItem(
    val id: String,
    val displayName: String,
    val modelName: String,
    val baseUrl: String,
    val preset: Boolean,
    val selectableForChat: Boolean,
)

internal data class DesktopModelEditorDraft(
    val id: String? = null,
    val displayName: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val modelName: String = "gpt-4o-mini",
    val selectableForChat: Boolean = true,
    val isMultimodal: Boolean = false,
    val visionModelId: String = "",
    val templateType: ModelTemplate = ModelTemplate.OPENAI,
    val reasoningEffort: String = "",
    val enableThinking: Boolean? = null,
    val maxOutputTokens: String = "",
    val outputTokenParameter: OutputTokenParameter = OutputTokenParameter.MAX_TOKENS,
    val formatPromptPosition: FormatPromptPosition = FormatPromptPosition.BOTH,
    val supportsJsonMode: Boolean = false,
    val supportsDisableThinking: Boolean = false,
    val customParams: List<DesktopParameterDraft> = listOf(
        DesktopParameterDraft("temperature", DesktopParameterKind.NUMBER, "1.0"),
    ),
    val hasSavedCredential: Boolean = false,
    val credentialEdit: DesktopCredentialEdit = DesktopCredentialEdit.Unchanged,
)

internal data class DesktopSettingsDraft(
    val defaultModelId: String? = null,
    val allowCleartextModelApi: Boolean = false,
    val defaultContextWindowSize: String = "20",
    val defaultFormatCardId: String? = null,
    val assistantSegmentedBubblesEnabled: Boolean = true,
    val hasSavedFallbackCredential: Boolean = false,
    val fallbackCredentialEdit: DesktopCredentialEdit = DesktopCredentialEdit.Unchanged,
    val playerName: String = "",
    val playerPersona: String = "",
)

internal data class DesktopModelSettingsState(
    val models: List<DesktopModelItem> = emptyList(),
    val editor: DesktopModelEditorDraft? = null,
    val settings: DesktopSettingsDraft? = null,
    val availableChatModels: List<DesktopModelItem> = emptyList(),
    val formatCards: List<Pair<String, String>> = emptyList(),
    val discoveredModelIds: List<String> = emptyList(),
    val discovering: Boolean = false,
    val discoveryError: String? = null,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

/** Desktop-only orchestration over shared model/settings authorities; no constructor I/O. */
internal class DesktopModelSettingsController(
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val formats: FormatCardRepository,
    private val resolver: EffectiveModelResolver,
    private val discovery: ModelDiscoveryService,
) {
    private val mutableState = MutableStateFlow(DesktopModelSettingsState())
    val state: StateFlow<DesktopModelSettingsState> = mutableState.asStateFlow()
    private var settingsBaseline: AppSettings? = null
    private val discoveryGeneration = AtomicLong()
    @Volatile
    private var discoveryJob: Job? = null

    suspend fun loadModels() = perform("Unable to load models") {
        refreshModelList()
    }

    suspend fun loadSettings() = perform("Unable to load settings") {
        val app = settings.getAppSettings()
        val player = settings.getPlayerSetting()
        val available = resolver.availableChatModels(app).map(ModelConfig::toItem)
        val formatCards = formats.getAll().map { it.id to it.name }
        settingsBaseline = app
        mutableState.update { current ->
            current.copy(
                settings = DesktopSettingsDraft(
                    defaultModelId = app.defaultModelId,
                    allowCleartextModelApi = app.allowCleartextModelApi,
                    defaultContextWindowSize = app.defaultContextWindowSize.toString(),
                    defaultFormatCardId = app.defaultFormatCardId,
                    assistantSegmentedBubblesEnabled = app.assistantSegmentedBubblesEnabled,
                    hasSavedFallbackCredential = app.siliconFlowApiKey.isNotBlank(),
                    playerName = player.playerName,
                    playerPersona = player.globalPersona,
                ),
                availableChatModels = available,
                formatCards = formatCards,
            )
        }
    }

    fun startCreate() {
        invalidateDiscovery()
        mutableState.update { it.copy(editor = DesktopModelEditorDraft(), error = null, status = null) }
    }

    suspend fun startEdit(id: String) = perform("Unable to open model") {
        val model = models.getModel(id) ?: error("Model no longer exists")
        invalidateDiscovery()
        mutableState.update { it.copy(editor = model.toEditor(), error = null, status = null) }
    }

    fun closeEditor() {
        invalidateDiscovery()
        mutableState.update { it.copy(editor = null, error = null) }
    }

    fun editModel(change: (DesktopModelEditorDraft) -> DesktopModelEditorDraft) {
        val previous = mutableState.value.editor ?: return
        val changed = change(previous)
        val next = if (changed.isMultimodal) changed.copy(visionModelId = "") else changed
        if (next.baseUrl != previous.baseUrl || next.credentialEdit != previous.credentialEdit) {
            invalidateDiscovery()
        }
        mutableState.update { it.copy(editor = next, error = null) }
    }

    fun replaceModelCredential(value: String) = editModel {
        it.copy(credentialEdit = DesktopCredentialEdit.Replace(value))
    }

    fun clearModelCredential() = editModel { it.copy(credentialEdit = DesktopCredentialEdit.Clear) }

    fun keepModelCredential() = editModel { it.copy(credentialEdit = DesktopCredentialEdit.Unchanged) }

    fun applyTemplate(template: ModelTemplate) = editModel { draft ->
        val (url, name) = when (template) {
            ModelTemplate.OPENAI -> "https://api.openai.com/v1" to "gpt-4o-mini"
            ModelTemplate.CLAUDE -> "https://api.anthropic.com/v1" to "claude-3-5-sonnet-latest"
            ModelTemplate.GEMINI -> "https://generativelanguage.googleapis.com/v1beta" to "gemini-2.5-flash"
            ModelTemplate.CUSTOM -> "" to ""
        }
        draft.copy(
            templateType = template,
            baseUrl = url,
            modelName = name,
            customParams = listOf(DesktopParameterDraft("temperature", DesktopParameterKind.NUMBER, "1.0")),
        )
    }

    fun addParameter() = editModel { it.copy(customParams = it.customParams + DesktopParameterDraft()) }

    fun editParameter(index: Int, change: (DesktopParameterDraft) -> DesktopParameterDraft) = editModel {
        if (index !in it.customParams.indices) it
        else it.copy(customParams = it.customParams.mapIndexed { i, param -> if (i == index) change(param) else param })
    }

    fun removeParameter(index: Int) = editModel {
        it.copy(customParams = it.customParams.filterIndexed { i, _ -> i != index })
    }

    suspend fun saveModel() = perform("Unable to save model") {
        val draft = mutableState.value.editor ?: return@perform
        val original = draft.id?.let { models.getModel(it) ?: error("Model no longer exists") }
        val config = draft.toModelConfig(original)
        models.saveModel(config)
        refreshModelList()
        val saved = models.getModel(config.id) ?: error("Saved model is unavailable")
        mutableState.update { it.copy(editor = saved.toEditor(), status = "Model saved") }
    }

    suspend fun duplicateModel(id: String) = perform("Unable to duplicate model") {
        models.duplicateModel(id)
        refreshModelList()
        mutableState.update { it.copy(status = "Model duplicated") }
    }

    suspend fun deleteModel(id: String) = perform("Unable to delete model") {
        models.deleteModel(id)
        refreshModelList()
        if (mutableState.value.editor?.id == id) closeEditor()
        mutableState.update { it.copy(status = "Model deleted") }
    }

    fun editSettings(change: (DesktopSettingsDraft) -> DesktopSettingsDraft) {
        mutableState.update { current ->
            current.copy(settings = current.settings?.let(change), error = null)
        }
    }

    fun replaceFallbackCredential(value: String) = editSettings {
        it.copy(fallbackCredentialEdit = DesktopCredentialEdit.Replace(value))
    }

    fun clearFallbackCredential() = editSettings {
        it.copy(fallbackCredentialEdit = DesktopCredentialEdit.Clear)
    }

    fun keepFallbackCredential() = editSettings {
        it.copy(fallbackCredentialEdit = DesktopCredentialEdit.Unchanged)
    }

    suspend fun saveAppSettings() = perform("Unable to save settings") {
        val baseline = settingsBaseline ?: error("Open settings before saving")
        val draft = mutableState.value.settings ?: return@perform
        val contextSize = draft.defaultContextWindowSize.toIntOrNull()
            ?.takeIf { it > 0 } ?: throw DesktopEditorValidationException("Context window size must be positive")
        val edited = baseline.copy(
            defaultModelId = draft.defaultModelId,
            allowCleartextModelApi = draft.allowCleartextModelApi,
            defaultContextWindowSize = contextSize,
            defaultFormatCardId = draft.defaultFormatCardId,
            assistantSegmentedBubblesEnabled = draft.assistantSegmentedBubblesEnabled,
            siliconFlowApiKey = draft.fallbackCredentialEdit.applyTo(baseline.siliconFlowApiKey),
        )
        val saved = settings.saveAppSettingsDraft(baseline, edited)
        settingsBaseline = saved
        mutableState.update { current ->
            current.copy(
                settings = draft.copy(
                    defaultModelId = saved.defaultModelId,
                    allowCleartextModelApi = saved.allowCleartextModelApi,
                    defaultContextWindowSize = saved.defaultContextWindowSize.toString(),
                    defaultFormatCardId = saved.defaultFormatCardId,
                    assistantSegmentedBubblesEnabled = saved.assistantSegmentedBubblesEnabled,
                    hasSavedFallbackCredential = saved.siliconFlowApiKey.isNotBlank(),
                    fallbackCredentialEdit = DesktopCredentialEdit.Unchanged,
                ),
                status = "Settings saved",
            )
        }
    }

    suspend fun savePlayerSetting() = perform("Unable to save player setting") {
        val draft = mutableState.value.settings ?: return@perform
        val latest = settings.getPlayerSetting()
        settings.savePlayerSetting(latest.copy(
            playerName = draft.playerName,
            globalPersona = draft.playerPersona,
        ))
        mutableState.update { it.copy(status = "Player setting saved") }
    }

    /** Called from a UI-owned coroutine; URL/key edits cancel and invalidate its result. */
    suspend fun discoverModels() {
        val draft = mutableState.value.editor ?: return
        discoveryJob?.cancel()
        val generation = discoveryGeneration.incrementAndGet()
        discoveryJob = currentCoroutineContext()[Job]
        mutableState.update { it.copy(discovering = true, discoveryError = null, discoveredModelIds = emptyList()) }
        try {
            val app = settings.getAppSettings()
            val key = draft.credentialEdit.applyTo(draft.id?.let { models.getModel(it)?.apiKey }.orEmpty())
            val effectiveKey = resolveEffectiveModelApiKey(key, draft.baseUrl, app)
            val ids = discovery.fetch(draft.baseUrl, effectiveKey, app.allowCleartextModelApi)
            if (generation == discoveryGeneration.get()) {
                mutableState.update { it.copy(discoveredModelIds = ids) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation == discoveryGeneration.get()) {
                mutableState.update { it.copy(discoveryError = failure.message ?: "Model discovery failed") }
            }
        } finally {
            if (generation == discoveryGeneration.get()) {
                discoveryJob = null
                mutableState.update { it.copy(discovering = false) }
            }
        }
    }

    fun selectDiscoveredModel(id: String) {
        if (id !in mutableState.value.discoveredModelIds) return
        editModel { it.copy(modelName = id) }
    }

    private fun invalidateDiscovery() {
        discoveryGeneration.incrementAndGet()
        discoveryJob?.cancel()
        discoveryJob = null
        mutableState.update {
            it.copy(discoveredModelIds = emptyList(), discovering = false, discoveryError = null)
        }
    }

    private suspend fun refreshModelList() {
        val listed = models.getAllModels().map(ModelConfig::toItem)
        mutableState.update { it.copy(models = listed) }
    }

    private suspend fun perform(fallbackError: String, action: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true, error = null, status = null) }
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (validation: DesktopEditorValidationException) {
            mutableState.update { it.copy(error = validation.message ?: fallbackError) }
        } catch (_: Exception) {
            // Repository/storage exceptions may include provider or credential material.
            mutableState.update { it.copy(error = fallbackError) }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }
}

private fun DesktopCredentialEdit.applyTo(existing: String): String = when (this) {
    DesktopCredentialEdit.Unchanged -> existing
    is DesktopCredentialEdit.Replace -> value.trim().takeIf(String::isNotEmpty)
        ?: throw DesktopEditorValidationException("Enter a key or choose Clear")
    DesktopCredentialEdit.Clear -> ""
}

private class DesktopEditorValidationException(message: String) : IllegalArgumentException(message)

private fun ModelConfig.toItem() = DesktopModelItem(
    id = id,
    displayName = displayName,
    modelName = modelName,
    baseUrl = baseUrl,
    preset = sourcePresetKey != null,
    selectableForChat = selectableForChat,
)

private fun ModelConfig.toEditor() = DesktopModelEditorDraft(
    id = id,
    displayName = displayName,
    baseUrl = baseUrl,
    modelName = modelName,
    selectableForChat = selectableForChat,
    isMultimodal = isMultimodal,
    visionModelId = visionModelId.orEmpty(),
    templateType = templateType,
    reasoningEffort = reasoningEffort.orEmpty(),
    enableThinking = enableThinking,
    maxOutputTokens = maxOutputTokens?.toString().orEmpty(),
    outputTokenParameter = outputTokenParameter,
    formatPromptPosition = formatPromptPosition,
    supportsJsonMode = supportsJsonMode,
    supportsDisableThinking = supportsDisableThinking,
    customParams = customParams.map { (key, value) -> when (value) {
        is ParamValue.NumberValue -> DesktopParameterDraft(key, DesktopParameterKind.NUMBER, value.value.toString())
        is ParamValue.BooleanValue -> DesktopParameterDraft(key, DesktopParameterKind.BOOLEAN, value.value.toString())
        is ParamValue.StringValue -> DesktopParameterDraft(key, DesktopParameterKind.STRING, value.value)
    } },
    hasSavedCredential = apiKey.isNotBlank(),
)

private fun DesktopModelEditorDraft.toModelConfig(original: ModelConfig?): ModelConfig {
    if (displayName.isBlank()) throw DesktopEditorValidationException("Display name is required")
    if (baseUrl.isBlank()) throw DesktopEditorValidationException("Base URL is required")
    if (modelName.isBlank()) throw DesktopEditorValidationException("Model ID is required")
    val outputLimit = maxOutputTokens.trim().takeIf(String::isNotEmpty)?.let {
        it.toIntOrNull()?.takeIf { value -> value > 0 }
            ?: throw DesktopEditorValidationException("Max output tokens must be positive")
    }
    val params = linkedMapOf<String, ParamValue>()
    customParams.forEach { param ->
        val key = param.key.trim()
        if (key.isEmpty()) throw DesktopEditorValidationException("Custom parameter name is required")
        if (key in params) throw DesktopEditorValidationException("Custom parameter names must be unique")
        params[key] = when (param.kind) {
            DesktopParameterKind.NUMBER -> ParamValue.NumberValue(
                param.value.toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: throw DesktopEditorValidationException("Custom number must be finite"),
            )
            DesktopParameterKind.BOOLEAN -> ParamValue.BooleanValue(
                param.value.toBooleanStrictOrNull()
                    ?: throw DesktopEditorValidationException("Custom boolean must be true or false"),
            )
            DesktopParameterKind.STRING -> ParamValue.StringValue(param.value)
        }
    }
    val base = original ?: ModelConfig.create(displayName.trim(), baseUrl.trim(), "", modelName.trim())
    return base.copy(
        displayName = displayName.trim(),
        baseUrl = baseUrl.trim(),
        apiKey = credentialEdit.applyTo(base.apiKey),
        modelName = modelName.trim(),
        selectableForChat = selectableForChat,
        isMultimodal = isMultimodal,
        visionModelId = visionModelId.trim().takeIf { !isMultimodal && it.isNotEmpty() },
        templateType = templateType,
        reasoningEffort = reasoningEffort.trim().takeIf(String::isNotEmpty),
        enableThinking = enableThinking,
        maxOutputTokens = outputLimit,
        outputTokenParameter = outputTokenParameter,
        formatPromptPosition = formatPromptPosition,
        supportsJsonMode = supportsJsonMode,
        supportsDisableThinking = supportsDisableThinking,
        customParams = params,
    )
}
