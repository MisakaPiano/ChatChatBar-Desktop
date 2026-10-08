package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.OutputTokenParameter
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.data.local.entity.PRESET_MODEL_ID_PREFIX
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Secret replacement is explicit. The hydrated value is never placed in editor field state. */
internal sealed interface DesktopCredentialEdit {
    data object Unchanged : DesktopCredentialEdit
    data class Replace(val value: String) : DesktopCredentialEdit {
        override fun toString(): String = "Replace([REDACTED])"
    }
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
    val templateType: ModelTemplate,
)

internal data class DesktopBundledChatModel(val key: String, val displayName: String, val modelName: String)

internal data class DesktopBundledModelCatalog(
    val provider: String,
    val baseUrl: String,
    val version: Int,
    val chatModels: List<DesktopBundledChatModel>,
    val embeddingModelName: String?,
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
    val excludeAssistantStatusFromHistory: Boolean = true,
    val hasSavedFallbackCredential: Boolean = false,
    val fallbackCredentialEdit: DesktopCredentialEdit = DesktopCredentialEdit.Unchanged,
    val playerName: String = "",
    val playerPersona: String = "",
)

internal data class DesktopModelSettingsState(
    val models: List<DesktopModelItem> = emptyList(),
    val bundledCatalog: DesktopBundledModelCatalog? = null,
    val editor: DesktopModelEditorDraft? = null,
    val editorDirty: Boolean = false,
    val leavePrompt: Boolean = false,
    val settings: DesktopSettingsDraft? = null,
    val chatDefaultsDirty: Boolean = false,
    val chatBubbleFontScale: Float = 1.0f,
    val bubbleFontScaleSaving: Boolean = false,
    val bubbleFontScaleError: Boolean = false,
    val playerDirty: Boolean = false,
    val credentialEditorOpen: Boolean = false,
    val credentialDirty: Boolean = false,
    val availableChatModels: List<DesktopModelItem> = emptyList(),
    val defaultDiagnostic: DesktopModelDiagnostic? = null,
    val effectiveModels: DesktopEffectiveModelPresentation = DesktopEffectiveModelPresentation(),
    val formatCards: List<Pair<String, String>> = emptyList(),
    val globalDefaultFormatCardId: String? = null,
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
    private val presets: DesktopPresetModelCatalogSource,
) {
    private val mutableState = MutableStateFlow(DesktopModelSettingsState())
    val state: StateFlow<DesktopModelSettingsState> = mutableState.asStateFlow()
    private var settingsBaseline: AppSettings? = null
    private val settingsLock = Mutex()
    private val fontScaleGeneration = AtomicLong()
    private var playerBaseline: Pair<String, String>? = null
    private var credentialDraft = ""
    private var editorBaseline: DesktopModelEditorDraft? = null
    private var pendingLeaveAction: (suspend () -> Unit)? = null
    private val discoveryGeneration = AtomicLong()
    @Volatile
    private var discoveryJob: Job? = null

    suspend fun loadModels() = perform("Unable to load models") {
        loadBundledCatalog()
        refreshModelList()
    }

    suspend fun restoreBundledModels() = perform("Unable to restore built-in models") {
        val catalog = presets.catalog
        val version = presets.modelCatalogVersion ?: catalog.schemaVersion
        models.restorePresetChatModels(catalog, version)
        models.restorePresetEmbeddingModel(catalog)
        val current = settings.getAppSettings()
        val effectiveSettings = if (current.defaultModelId == null && current.presetDefaultModelKey != null) {
            val migrated = current.copy(
                defaultModelId = PRESET_MODEL_ID_PREFIX + current.presetDefaultModelKey,
                presetDefaultModelKey = null,
            )
            settings.saveAppSettings(migrated)
            migrated
        } else current
        refreshModelList()
        val available = resolver.availableChatModels(effectiveSettings).map(ModelConfig::toItem)
        if (settingsBaseline != null) settingsBaseline = effectiveSettings
        mutableState.update {
            it.copy(
                availableChatModels = available,
                settings = it.settings?.copy(defaultModelId = effectiveSettings.defaultModelId),
                status = "Built-in models restored",
            )
        }
    }

    suspend fun loadSettings() = settingsLock.withLock { perform("Unable to load settings") {
        loadBundledCatalog()
        val app = settings.getAppSettings()
        val player = settings.getPlayerSetting()
        val available = resolver.availableChatModels(app).map(ModelConfig::toItem)
        val formatCards = formats.getAll().map { it.id to it.name }
        settingsBaseline = app
        playerBaseline = player.playerName to player.globalPersona
        mutableState.update { current ->
            current.copy(
                settings = DesktopSettingsDraft(
                    defaultModelId = app.defaultModelId,
                    allowCleartextModelApi = app.allowCleartextModelApi,
                    defaultContextWindowSize = app.defaultContextWindowSize.toString(),
                    defaultFormatCardId = app.defaultFormatCardId,
                    assistantSegmentedBubblesEnabled = app.assistantSegmentedBubblesEnabled,
                    excludeAssistantStatusFromHistory = app.excludeAssistantStatusFromHistory,
                    hasSavedFallbackCredential = app.siliconFlowApiKey.isNotBlank(),
                    playerName = player.playerName,
                    playerPersona = player.globalPersona,
                ),
                availableChatModels = available,
                formatCards = formatCards,
                globalDefaultFormatCardId = app.defaultFormatCardId,
                chatDefaultsDirty = false,
                chatBubbleFontScale = desktopSafeBubbleFontScale(app.chatBubbleFontScale),
                playerDirty = false,
            )
        }
        refreshDefaultDiagnostic(app)
    } }

    /** Immediate appearance setting, independent of ordinary settings drafts and generic busy state. */
    suspend fun updateBubbleFontScale(value: Float) {
        val scale = desktopBubbleFontScaleStep(value)
        val generation = fontScaleGeneration.incrementAndGet()
        mutableState.update { it.copy(chatBubbleFontScale = scale,
            bubbleFontScaleSaving = true, bubbleFontScaleError = false) }
        try {
            settingsLock.withLock {
                // Skip queued obsolete events. An in-flight write finishes before the latest write.
                if (generation != fontScaleGeneration.get()) return@withLock
                val saved = settings.updateAppSettings { it.copy(chatBubbleFontScale = scale) }
                settingsBaseline = settingsBaseline?.copy(chatBubbleFontScale = saved.chatBubbleFontScale)
                if (generation == fontScaleGeneration.get()) {
                    mutableState.update { it.copy(chatBubbleFontScale = scale) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation == fontScaleGeneration.get()) {
                mutableState.update { it.copy(bubbleFontScaleError = true) }
            }
        } finally {
            if (generation == fontScaleGeneration.get()) {
                mutableState.update { it.copy(bubbleFontScaleSaving = false) }
            }
        }
    }

    suspend fun refreshFormatChoices() {
        val choices = formats.getAll().map { it.id to it.name }
        mutableState.update { it.copy(formatCards = choices) }
    }

    /** Management reads persisted authority without replacing any open settings draft. */
    suspend fun loadFormatManagement() = perform("Unable to load settings") {
        val app = settings.getAppSettings()
        refreshFormatChoices()
        mutableState.update { it.copy(globalDefaultFormatCardId = app.defaultFormatCardId) }
    }

    suspend fun setDefaultFormatCard(id: String): Boolean {
        var saved = false
        perform("Unable to save global FormatCard default") {
            if (mutableState.value.chatDefaultsDirty) {
                throw DesktopEditorValidationException("Save or discard Chat Defaults before changing the global FormatCard")
            }
            if (formats.getById(id) == null) throw DesktopEditorValidationException("FormatCard no longer exists")
            // Match baseline ManageViewModel: entity flag first, then runtime settings authority.
            formats.setDefault(id)
            val app = settings.updateAppSettings { it.copy(defaultFormatCardId = id) }
            settingsBaseline = settingsBaseline?.copy(defaultFormatCardId = app.defaultFormatCardId)
            refreshFormatChoices()
            mutableState.update { current -> current.copy(
                globalDefaultFormatCardId = app.defaultFormatCardId,
                settings = current.settings?.copy(defaultFormatCardId = app.defaultFormatCardId),
                status = "Global FormatCard default saved",
            ) }
            saved = true
        }
        return saved
    }

    suspend fun setDefaultModel(id: String?) = perform("Unable to save default model") {
        val saved = settings.updateAppSettings { it.copy(defaultModelId = id, presetDefaultModelKey = null) }
        settingsBaseline = saved
        mutableState.update { current ->
            current.copy(settings = current.settings?.copy(defaultModelId = id), status = "Default model saved")
        }
        refreshDefaultDiagnostic(saved)
    }

    fun startCreate(template: ModelTemplate = ModelTemplate.OPENAI) {
        if (hasPendingDraft()) {
            pendingLeaveAction = { openCreate(template) }
            mutableState.update { it.copy(leavePrompt = true) }
            return
        }
        openCreate(template)
    }

    private fun openCreate(template: ModelTemplate) {
        invalidateDiscovery()
        val draft = DesktopModelEditorDraft().withTemplate(template)
        editorBaseline = draft
        mutableState.update { it.copy(editor = draft, editorDirty = false, error = null, status = null) }
    }

    suspend fun startEdit(id: String) {
        if (hasPendingDraft()) {
            pendingLeaveAction = { openEdit(id) }
            mutableState.update { it.copy(leavePrompt = true) }
        } else openEdit(id)
    }

    private suspend fun openEdit(id: String) = perform("Unable to open model") {
        val model = models.getModel(id) ?: error("Model no longer exists")
        invalidateDiscovery()
        val draft = model.toEditor()
        editorBaseline = draft
        mutableState.update { it.copy(editor = draft, editorDirty = false, error = null, status = null) }
    }

    fun closeEditor() {
        if (hasPendingDraft()) {
            pendingLeaveAction = { forceCloseEditor() }
            mutableState.update { it.copy(leavePrompt = true) }
            return
        }
        forceCloseEditor()
    }

    private fun forceCloseEditor() {
        invalidateDiscovery()
        editorBaseline = null
        mutableState.update { it.copy(editor = null, editorDirty = false, leavePrompt = false, error = null) }
    }

    suspend fun requestLeave(action: suspend () -> Unit) {
        // Finish previously submitted immediate appearance writes before disposing their UI scope.
        settingsLock.withLock { }
        if (hasPendingDraft()) {
            pendingLeaveAction = { forceCloseEditor(); action() }
            mutableState.update { it.copy(leavePrompt = true) }
        } else {
            forceCloseEditor()
            action()
        }
    }

    fun continueEditing() {
        pendingLeaveAction = null
        mutableState.update { it.copy(leavePrompt = false) }
    }

    suspend fun resolveLeave(save: Boolean) {
        val action = pendingLeaveAction ?: return
        if (save) {
            if (mutableState.value.editorDirty) {
                saveModel()
                if (mutableState.value.error != null) return
            }
            if (mutableState.value.chatDefaultsDirty) {
                saveAppSettings()
                if (mutableState.value.error != null) return
            }
            if (mutableState.value.playerDirty) {
                savePlayerSetting()
                if (mutableState.value.error != null) return
            }
            if (mutableState.value.credentialDirty) {
                saveCredentialDraft()
                if (mutableState.value.error != null) return
            }
            if (mutableState.value.error != null || hasPendingDraft()) return
        } else {
            discardChatDefaults()
            discardPlayerSetting()
            discardCredentialEditor()
        }
        pendingLeaveAction = null
        mutableState.update { it.copy(leavePrompt = false) }
        action()
    }

    private fun hasPendingDraft(): Boolean = mutableState.value.let {
        it.editorDirty || it.chatDefaultsDirty || it.playerDirty || it.credentialDirty
    }

    fun editModel(change: (DesktopModelEditorDraft) -> DesktopModelEditorDraft) {
        val previous = mutableState.value.editor ?: return
        val changed = change(previous)
        val next = if (changed.isMultimodal) changed.copy(visionModelId = "") else changed
        if (next.baseUrl != previous.baseUrl || next.credentialEdit != previous.credentialEdit) {
            invalidateDiscovery()
        }
        mutableState.update { it.copy(editor = next, editorDirty = next != editorBaseline, error = null) }
    }

    fun replaceModelCredential(value: String) = editModel {
        it.copy(credentialEdit = DesktopCredentialEdit.Replace(value))
    }

    fun clearModelCredential() = editModel { it.copy(credentialEdit = DesktopCredentialEdit.Clear) }

    fun keepModelCredential() = editModel { it.copy(credentialEdit = DesktopCredentialEdit.Unchanged) }

    fun applyTemplate(template: ModelTemplate) = editModel { it.withTemplate(template) }

    private fun DesktopModelEditorDraft.withTemplate(template: ModelTemplate): DesktopModelEditorDraft {
        val (url, name) = when (template) {
            ModelTemplate.OPENAI -> "https://api.openai.com/v1" to "gpt-4o-mini"
            ModelTemplate.CLAUDE -> "https://api.anthropic.com/v1" to "claude-3-5-sonnet-latest"
            ModelTemplate.GEMINI -> "https://generativelanguage.googleapis.com/v1beta" to "gemini-2.5-flash"
            ModelTemplate.CUSTOM -> "" to ""
        }
        return copy(
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
        val clean = saved.toEditor()
        editorBaseline = clean
        mutableState.update { it.copy(editor = clean, editorDirty = false, status = "Model saved") }
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
            val next = current.settings?.let(change)
            val baseline = settingsBaseline
            val player = playerBaseline
            current.copy(
                settings = next,
                chatDefaultsDirty = next != null && baseline != null && (
                    next.defaultModelId != baseline.defaultModelId ||
                        next.allowCleartextModelApi != baseline.allowCleartextModelApi ||
                        next.defaultContextWindowSize != baseline.defaultContextWindowSize.toString() ||
                        next.defaultFormatCardId != baseline.defaultFormatCardId ||
                        next.assistantSegmentedBubblesEnabled != baseline.assistantSegmentedBubblesEnabled ||
                        next.excludeAssistantStatusFromHistory != baseline.excludeAssistantStatusFromHistory
                    ),
                playerDirty = next != null && player != null &&
                    (next.playerName != player.first || next.playerPersona != player.second),
                error = null,
            )
        }
    }

    fun discardChatDefaults() {
        val baseline = settingsBaseline ?: return
        mutableState.update { current ->
            current.copy(settings = current.settings?.copy(
                defaultModelId = baseline.defaultModelId,
                allowCleartextModelApi = baseline.allowCleartextModelApi,
                defaultContextWindowSize = baseline.defaultContextWindowSize.toString(),
                defaultFormatCardId = baseline.defaultFormatCardId,
                assistantSegmentedBubblesEnabled = baseline.assistantSegmentedBubblesEnabled,
                excludeAssistantStatusFromHistory = baseline.excludeAssistantStatusFromHistory,
                fallbackCredentialEdit = DesktopCredentialEdit.Unchanged,
            ), chatDefaultsDirty = false)
        }
    }

    fun discardPlayerSetting() {
        val baseline = playerBaseline ?: return
        mutableState.update { current ->
            current.copy(settings = current.settings?.copy(playerName = baseline.first,
                playerPersona = baseline.second), playerDirty = false)
        }
    }

    fun openCredentialEditor() {
        credentialDraft = ""
        mutableState.update { it.copy(credentialEditorOpen = true, credentialDirty = false) }
    }

    fun editCredentialDraft(value: String) {
        credentialDraft = value
        mutableState.update { it.copy(credentialDirty = value.isNotEmpty(), error = null) }
    }

    fun discardCredentialEditor() {
        credentialDraft = ""
        mutableState.update { it.copy(credentialEditorOpen = false, credentialDirty = false) }
    }

    suspend fun saveCredentialDraft() {
        saveFallbackCredential(credentialDraft)
        if (mutableState.value.error == null) discardCredentialEditor()
    }

    suspend fun saveFallbackCredential(value: String) = perform("Unable to save credential") {
        val key = value.trim().takeIf(String::isNotEmpty)
            ?: throw DesktopEditorValidationException("Enter a key or choose Clear")
        val saved = settings.updateAppSettings { it.copy(siliconFlowApiKey = key) }
        settingsBaseline = saved
        mutableState.update { current -> current.copy(
            settings = current.settings?.copy(hasSavedFallbackCredential = true,
                fallbackCredentialEdit = DesktopCredentialEdit.Unchanged),
            status = "Credential saved securely",
        ) }
    }

    suspend fun clearFallbackCredentialImmediately() = perform("Unable to clear credential") {
        val saved = settings.updateAppSettings { it.copy(siliconFlowApiKey = "") }
        settingsBaseline = saved
        mutableState.update { current -> current.copy(
            settings = current.settings?.copy(hasSavedFallbackCredential = false,
                fallbackCredentialEdit = DesktopCredentialEdit.Unchanged),
            status = "Credential cleared",
        ) }
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

    suspend fun saveAppSettings() = settingsLock.withLock { perform("Unable to save settings") {
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
            excludeAssistantStatusFromHistory = draft.excludeAssistantStatusFromHistory,
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
                    excludeAssistantStatusFromHistory = saved.excludeAssistantStatusFromHistory,
                    hasSavedFallbackCredential = saved.siliconFlowApiKey.isNotBlank(),
                    fallbackCredentialEdit = DesktopCredentialEdit.Unchanged,
                ),
                status = "Settings saved",
                globalDefaultFormatCardId = saved.defaultFormatCardId,
                chatDefaultsDirty = false,
            )
        }
    } }

    suspend fun savePlayerSetting() = perform("Unable to save player setting") {
        val draft = mutableState.value.settings ?: return@perform
        val latest = settings.getPlayerSetting()
        settings.savePlayerSetting(latest.copy(
            playerName = draft.playerName,
            globalPersona = draft.playerPersona,
        ))
        playerBaseline = draft.playerName to draft.playerPersona
        mutableState.update { it.copy(status = "Player setting saved", playerDirty = false) }
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
        refreshDefaultDiagnostic(settings.readExistingAppSettings() ?: AppSettings())
    }

    private suspend fun refreshDefaultDiagnostic(app: AppSettings) {
        val effectiveModels = desktopEffectiveModels(resolver, app)
        val effective = resolver.defaultChatModel(app)
        val raw = effective?.id?.let { models.getModel(it) }
        val diagnostic = desktopModelDiagnostic(
            configuredId = app.configuredDefaultChatModelId(),
            effective = effective,
            appSettings = app,
            rawEffective = raw,
            catalogProvider = presets.catalog.takeIf { catalog ->
                effective?.sourcePresetKey != null && catalog.chatModels.any {
                    it.modelKey == effective.sourcePresetKey
                }
            }?.provider,
        )
        mutableState.update { it.copy(defaultDiagnostic = diagnostic, effectiveModels = effectiveModels) }
    }

    suspend fun currentEffectiveModels(): DesktopEffectiveModelPresentation =
        desktopEffectiveModels(resolver, settings.getAppSettings())

    private fun loadBundledCatalog() {
        val catalog = presets.catalog
        mutableState.update {
            it.copy(bundledCatalog = DesktopBundledModelCatalog(
                provider = catalog.provider,
                baseUrl = catalog.baseUrl,
                version = presets.modelCatalogVersion ?: catalog.schemaVersion,
                chatModels = catalog.chatModels.map { model ->
                    DesktopBundledChatModel(model.modelKey, model.displayName, model.modelName)
                },
                embeddingModelName = catalog.embeddingModel?.displayName,
            ))
        }
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
    templateType = templateType,
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
