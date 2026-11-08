package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.ui.imageprompt.NovelAiAccountUiState
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class DesktopStudioUndoUnavailable : IllegalStateException()

internal data class DesktopNovelAiStudioState(
    val ready: Boolean = false,
    val credentialConfigured: Boolean = false,
    val applyingHistory: Boolean = false,
    val accountUi: NovelAiAccountUiState = NovelAiAccountUiState(loading = false),
    val status: String = "",
    val taskId: String? = null,
    val generationTaskId: String? = null,
    val postProcessTaskId: String? = null,
    val postProcessSourceSha256: String? = null,
    val postProcessError: String? = null,
    val preview: ByteArray? = null,
    val reverseCandidate: NovelAiPromptPlan? = null,
    val reverseSceneDescription: String? = null,
    val reverseTargetModel: NovelAiImageModel? = null,
    val reverseBusy: Boolean = false,
    val reverseProgress: DesktopReversePromptProgress = DesktopReversePromptProgress(),
    val designProgress: DesktopReversePromptProgress = DesktopReversePromptProgress(),
    val catalogUpdate: com.example.chatbar.domain.update.DanbooruCatalogUpdateInfo? = null,
    val results: List<String> = emptyList(),
    val selected: Set<NovelAiHistoryImageSelection> = emptySet(),
    val presetPreview: DesktopGenerationPresetPreview? = null,
    val presetOutcome: DesktopPresetApplyOutcome = DesktopPresetApplyOutcome.NONE,
    val fullPromptPreview: NovelAiFullPromptContent? = null,
) {
    val account: NovelAiAccountUsage? get() = accountUi.takeIf { it.error == null }?.effectiveUsage
}

internal data class DesktopGenerationPresetPreview(val fileName: String, val preset: NovelAiGenerationPreset)
internal enum class DesktopPresetApplyOutcome { NONE, APPLIED, APPLIED_CLEANUP_PENDING }

internal data class DesktopReversePromptProgress(val stage: String = "", val content: String = "", val reasoning: String = "") {
    fun content(value: String) = copy(content = value,
        stage = Regex("【([^】]+)】").findAll(value).lastOrNull()?.groupValues?.get(1) ?: "正在理解图片")
}

/** Each new opening has reference identity; reactivating that opening retains its local edits. */
internal class DesktopReverseSceneEdit(val revision: Long, val original: String)

/** Runtime-only input ownership; never serialized with the Studio draft or conversation. */
internal data class DesktopDesignComposerState(
    val initialized: Boolean = false,
    val composingNew: Boolean = true,
    val input: String = "",
    val attachStudioPrompt: Boolean = false,
    val revision: Long = 0,
)

/** UI and platform orchestration; all draft/Prompt/size/history transforms use shared authority. */
internal class DesktopNovelAiStudioController(
    private val storage: JsonFileStorage,
    val resources: DesktopCharacterResourceStore,
    val picker: DesktopFilePicker,
    private val coordinator: DesktopDataOperationCoordinator,
    private val cleanup: suspend (List<String>) -> Unit,
    private val runtime: DesktopNovelAiGenerationRuntime,
    private val guidance: DesktopNovelAiGuidance,
    private val tasks: DesktopTaskRuntime,
    val infrastructure: DesktopNovelAiInfrastructure,
    private val settings: SettingsRepository,
    private val resolver: EffectiveModelResolver,
    private val characters: CharacterRepository,
    private val account: suspend () -> NovelAiAccountUsage,
    private val credentialConfigured: suspend () -> Boolean,
    private val postProcessor: DesktopNovelAiPostProcessor? = null,
    private val credentialIdentity: () -> String = { "legacy" },
    private val freezeCredential: () -> kotlin.coroutines.CoroutineContext = { kotlin.coroutines.EmptyCoroutineContext },
    private val presetArchive: DesktopGenerationPresetArchive? = null,
    directoryPicker: DesktopDirectoryPicker = DesktopDirectoryPicker { null },
) {
    val repository = DesktopStudioHistoryRepository(storage)
    val historyExport = DesktopHistoryBatchExportController(DesktopHistoryBatchExport(storage, resources, coordinator, directoryPicker))
    val postProcessResults = DesktopPostProcessResults(storage, resources, coordinator)
    val textHistories = DesktopStudioTextHistories()
    val imageDiagnostics get() = tasks.diagnostics
    val designRepository = NovelAiDesignConversationRepository(storage)
    val draft = repository.draft
    val history = repository.history
    private val mutex = Mutex()
    private val accountMutex = Mutex()
    private data class PresetSelection(val prepared: DesktopPreparedGenerationPreset, val expected: NovelAiStudioDraft,
        var applied: Boolean = false)
    val generationPresetAvailable get() = presetArchive != null
    private var presetSelection: PresetSelection? = null
    private var presetClosed = false
    private var fullPromptSelection: Pair<NovelAiFullPromptContent, NovelAiStudioDraft>? = null
    private var accountIdentity: String? = null
    private data class ReverseSelection(val revision: Long, val draft: NovelAiStudioDraft)
    private val reverseLock = Any()
    private var reverseRevision = 0L
    private var reverseSelection: ReverseSelection? = null
    private var reverseTaskId: String? = null
    private val mutableState = MutableStateFlow(DesktopNovelAiStudioState())
    val state = mutableState.asStateFlow()
    val taskEntries = tasks.tasks
    private val composerMutex = Mutex()
    private val mutableDesignComposer = MutableStateFlow(DesktopDesignComposerState())
    val designComposer = mutableDesignComposer.asStateFlow()

    suspend fun initializeDesignComposer() = composerMutex.withLock {
        if (designComposer.value.initialized) return@withLock
        designRepository.initialize()
        val current = designRepository.currentConversation()
        mutableDesignComposer.value = DesktopDesignComposerState(initialized = true,
            composingNew = current == null, input = if (current == null) repository.loadDraft().imageDescription else "")
    }

    fun editDesignInput(input: String) { mutableDesignComposer.update { it.copy(input = input) } }
    fun attachDesignPrompt(attach: Boolean) { mutableDesignComposer.update { it.copy(attachStudioPrompt = attach) } }
    fun newDesignConversation() {
        mutableDesignComposer.update { it.copy(composingNew = true, input = "", attachStudioPrompt = false, revision = it.revision + 1) }
    }

    private fun openCurrentDesign() {
        mutableDesignComposer.update { it.copy(composingNew = designRepository.currentConversation() == null,
            input = "", attachStudioPrompt = false, revision = it.revision + 1) }
    }

    /** Actual tool exit only; internal settings/history navigation keeps the transient composer. */
    fun leaveDesignScreen() {
        if (!designComposer.value.composingNew) return
        openCurrentDesign()
        mutableState.update { it.copy(designProgress = DesktopReversePromptProgress(), status = "") }
    }

    suspend fun postProcess(bytes: ByteArray, tab: NovelAiPostProcessTab, source: NovelAiEnhanceSource?,
        options: NovelAiEnhanceOptions, onComplete: (ByteArray) -> Unit, onStatus: (String) -> Unit) = action {
        check(reloadPostProcessResults()) { "后处理记录尚未通过读取检查" }
        val processor = requireNotNull(postProcessor)
        val (identity, frozen) = studioCredentialRead { credentialIdentity() to freezeCredential() }
        val account = state.value.account.takeIf { accountIdentity == identity }
        val id = tasks.launchNovelAi(tab.label) { report -> withContext(frozen) {
            try {
                onStatus("正在请求 ${tab.label}")
                val result = processor.process(bytes, tab, source, options, account) { onStatus(it); report(it) }
                currentCoroutineContext().ensureActive()
                postProcessResults.persist(bytes, result, tab, options)
                // A departed Pane's presentation callback cannot discard the durable paid output.
                runCatching { onComplete(result); onStatus("处理完成并已保存；来源未改变") }
                report("处理完成并已保存")
                refreshAccount()
            } catch (cancelled: CancellationException) { onStatus("已停止；来源和上次结果保留"); throw cancelled }
            catch (_: Exception) { onStatus("图片处理未完成；来源和上次结果保留，请检查配置或服务状态"); throw DesktopNovelAiRequestException("图片处理未完成") }
        } }
        mutableState.update { it.copy(taskId = id, postProcessTaskId = id, postProcessSourceSha256 = DesktopPostProcessResults.digest(bytes)) }
    }

    fun stopPostProcess(taskId: String): Boolean =
        if (state.value.postProcessTaskId == taskId) tasks.requestUserStop(taskId) else false

    suspend fun reloadPostProcessResults(): Boolean = try {
        postProcessResults.load(); mutableState.update { it.copy(postProcessError = null) }; true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        mutableState.update { it.copy(postProcessError = "后处理记录读取失败；原记录和图片保留，普通 Studio 仍可使用。") }; false
    }

    suspend fun load() = action {
        reloadPostProcessResults()
        repository.initialize()
        designRepository.initialize()
        repository.loadDraft()
        repository.loadUndoDraft()
        resolveDefaultModel()
        repository.initializeHistory()
        val latest = history.first().firstOrNull()?.images.orEmpty().map { it.path }
        mutableState.update { it.copy(ready = true, results = latest) }
        refreshAccount()
        if (presetSelection == null && presetArchive?.hasPendingCustody() == true)
            status("有预设临时资源清理待核对；原草稿与归属记录保留，不会自动重新应用。")
    }

    private suspend fun resolveDefaultModel(): NovelAiStudioDraft {
        val current = repository.loadDraft()
        if (!current.followDefaultNovelAiImageModel) return current
        val card = current.importedCharacterCardId?.let { characters.getById(it) }
        val target = NovelAiImageModelResolution.resolve(null, card?.defaultNovelAiImageModel,
            settings.getAppSettings().novelAiImageModel)
        return repository.updateDraft { it.copy(selectedModel = target) }
    }

    suspend fun checkCatalog() = action {
        val id = tasks.launchNovelAi("检查 Danbooru 词库") { report ->
            val update = com.example.chatbar.domain.update.SharedDanbooruCatalogUpdateChecker(
                infrastructure.tags, "CCB-Desktop").checkLatestCatalog()
            mutableState.update { it.copy(catalogUpdate = update) }
            status(if (update == null) "词库已是最新版本" else "词库更新已就绪，下载 ${update.sizeBytes} 字节")
            report("词库检查完成")
        }
        mutableState.update { it.copy(taskId = id) }
    }
    suspend fun installCatalog() = action {
        val update = requireNotNull(state.value.catalogUpdate)
        val id = tasks.launchNovelAi("更新 Danbooru 词库") { report ->
            DesktopNovelAiCatalogUpdate(infrastructure.tags).install(update) { status(it); report(it) }
            mutableState.update { it.copy(catalogUpdate = null) }
        }
        mutableState.update { it.copy(taskId = id) }
    }
    private suspend fun designModel(id: String?, app: com.example.chatbar.data.local.entity.AppSettings? = null): com.example.chatbar.data.local.entity.ModelConfig {
        val hydratedSettings = app ?: settings.getAppSettings()
        val model = desktopExactDesignModel(resolver, hydratedSettings, id)
            ?: throw DesktopDesignException(DesktopDesignFailure.MODEL)
        if (!model.hasConfiguredAuthentication(hydratedSettings)) throw DesktopDesignException(DesktopDesignFailure.AUTH)
        return model
    }

    suspend fun designAuthentication(): DesktopDesignAuthentication = try {
        val app = settings.getAppSettings()
        desktopDesignAuthentication(desktopExactDesignModel(resolver, app, repository.loadDraft().aiDesignModelId), app)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { DesktopDesignAuthentication(status = "无法读取设计模型安全配置，请打开模型设置") }

    suspend fun refreshAccount() = accountMutex.withLock {
        val (identity, frozen) = try { studioCredentialRead { credentialIdentity() to freezeCredential() } }
            catch (_: DesktopStudioCredentialReadException) { invalidateAccount(STUDIO_ACCOUNT_READ_FAILURE); return@withLock }
        val configured = try {
            withContext(Dispatchers.IO) { credentialConfigured() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            invalidateAccount(STUDIO_ACCOUNT_READ_FAILURE)
            return@withLock
        }
        mutableState.update { it.copy(credentialConfigured = configured,
            accountUi = if (configured && it.credentialConfigured) it.accountUi.copy(loading = true)
                else if (configured) NovelAiAccountUiState(loading = true)
                else NovelAiAccountUiState(loading = false, error = "未配置 Token")) }
        if (!configured) return@withLock
        try {
            val latest = withContext(Dispatchers.IO + frozen) { account() }
            if (identity == studioCredentialRead(credentialIdentity)) {
                accountIdentity = identity
                mutableState.update { it.copy(accountUi = it.accountUi.reconcile(latest)) }
            } else invalidateAccount()
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(accountUi = it.accountUi.copy(loading = false)) }; throw cancelled
        } catch (failure: Exception) {
            if (failure is DesktopStudioCredentialReadException) {
                invalidateAccount(STUDIO_ACCOUNT_READ_FAILURE)
                return@withLock
            }
            val reason = if (failure is com.example.chatbar.domain.image.NovelAiAccountException)
                requireNotNull(failure.message) + "；免费资格未确认" else "账户信息获取失败；免费资格未确认"
            mutableState.update { it.copy(accountUi = it.accountUi.copy(loading = false, error = reason)) }
        }
    }

    fun invalidateAccount(error: String? = null) {
        accountIdentity = null
        mutableState.update { it.copy(accountUi = NovelAiAccountUiState(loading = false, error = error), credentialConfigured = false) }
    }

    internal suspend fun accountAfterSuccess(cost: NovelAiGenerationCost, count: Int) {
        mutableState.update { current -> current.copy(accountUi = current.accountUi
            .recordAnlasGeneration(cost.anlas.toLong()).let {
                if (cost.kind == NovelAiGenerationChargeKind.V5_ALLOWANCE) it.recordV5Generation(count) else it
            }) }
        refreshAccount()
    }

    suspend fun edit(change: (NovelAiStudioDraft) -> NovelAiStudioDraft) = editFromField(DesktopStudioTextBoundary(), change)

    suspend fun editFromField(boundary: DesktopStudioTextBoundary, change: (NovelAiStudioDraft) -> NovelAiStudioDraft) = action(inspectAssets = false) {
        val before = repository.loadDraft()
        if (change(before) == before) return@action
        repository.saveUndoDraft(before)
        val after = repository.updateFromField(boundary, transform = change)
        // The durable undo snapshot retains removed media through the existing reference cleaner.
        // Ordinary parameters keep every media reference. Only a removed draft reference can
        // become unowned; the cleaner still checks undo/checkpoint/history before unlinking it.
        cleanupAfterCommit((before.imageGuidance.ownedAssetPaths() - after.imageGuidance.ownedAssetPaths()).toList())
    }

    suspend fun replace(change: (NovelAiStudioDraft) -> NovelAiStudioDraft) = action {
        val before = repository.loadDraft()
        repository.saveUndoDraft(before)
        repository.updateDraft(resetPromptEditors = true, transform = change)
        // A same-text explicit import is still a request to refresh derived annotations.
        // Keep this independent from editor authority so selection/IME are not reset.
        textHistories.annotationsChanged()
    }

    suspend fun exportGenerationPreset(path: Path) = action(inspectAssets = false) {
        check(!presetClosed); requirePresetIdle()
        coordinator.withNormalOperation { requireNotNull(presetArchive).export(resolveDefaultModel(), path) }
        status("生成预设已导出：$path")
    }

    suspend fun copyFullPrompt(): String? {
        var text: String? = null
        action(inspectAssets = false) {
            check(!presetClosed); requirePresetIdle()
            coordinator.withNormalOperation { text = NovelAiStudioFullPromptClipboard.encode(resolveDefaultModel()) }
        }
        return text
    }

    suspend fun inspectFullPrompt(text: String) = action(inspectAssets = false) {
        check(!presetClosed); requirePresetIdle()
        fullPromptSelection = null
        mutableState.update { it.copy(fullPromptPreview = null) }
        coordinator.withNormalOperation {
            val content = NovelAiStudioFullPromptClipboard.decode(text)
            fullPromptSelection = content to resolveDefaultModel()
            mutableState.update { it.copy(fullPromptPreview = content) }
            status("完整 Prompt 已解析；确认后才替换提示词和角色控制（含位置应用开关），其他生成参数和参考资源保留。")
        }
    }

    suspend fun applyFullPrompt() = action(inspectAssets = false) {
        check(!presetClosed); requirePresetIdle()
        val (content, expected) = requireNotNull(fullPromptSelection) { "请先预览完整 Prompt" }
        coordinator.withNormalOperation {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                check(repository.loadDraft() == expected) { "当前生成草稿已变化，请重新读取完整 Prompt" }
                repository.saveUndoDraft(expected)
                repository.updateDraft(resetPromptEditors = true) {
                    check(it == expected) { "当前生成草稿已变化，请重新读取完整 Prompt" }
                    content.applyTo(it)
                }
                fullPromptSelection = null
                mutableState.update { it.copy(fullPromptPreview = null) }
                status("完整 Prompt 已应用，可撤销；其他生成参数和参考资源保留，未自动开始生成。")
            }
        }
    }

    suspend fun discardFullPrompt() = mutex.withLock {
        fullPromptSelection = null
        mutableState.update { it.copy(fullPromptPreview = null) }
    }

    suspend fun inspectGenerationPreset(path: Path) = action(inspectAssets = false) {
        check(!presetClosed); requirePresetIdle()
        coordinator.withNormalOperation {
            var previousCleanupPending = false
            try { presetSelection?.prepared?.close() } catch (_: Exception) { previousCleanupPending = true }
            presetSelection = null
            mutableState.update { it.copy(presetPreview = null, presetOutcome = DesktopPresetApplyOutcome.NONE) }
            val expected = resolveDefaultModel()
            val prepared = requireNotNull(presetArchive).inspect(path)
            // Transfer ownership before returning across a cancellable dispatcher boundary.
            presetSelection = PresetSelection(prepared, expected)
            mutableState.update { it.copy(presetPreview = DesktopGenerationPresetPreview(path.fileName.toString(), prepared.preset)) }
            status(if (previousCleanupPending) "预设已校验；上次临时清理仍待核对，归属记录保留。" else "预设已校验，确认后才替换生成草稿。")
        }
    }

    suspend fun applyGenerationPreset() = action(inspectAssets = false) {
        check(!presetClosed); requirePresetIdle()
        val selection = requireNotNull(presetSelection) { "请先选择生成预设" }
        check(!selection.applied) { "预设已应用；只需重试临时清理，不应重复导入" }
        val created = mutableListOf<String>()
        val payloadClaims = mutableSetOf<String>()
        var committed = false
        var cleanupPending = false
        try {
            coordinator.withNormalOperation {
                check(repository.loadDraft() == selection.expected) { "当前生成草稿已变化，请重新选择预设" }
                val materialized = requireNotNull(presetArchive).materialize(selection.prepared, created::add, payloadClaims::add)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    check(repository.loadDraft() == selection.expected) { "当前生成草稿已变化，请重新选择预设" }
                    repository.saveUndoDraft(selection.expected)
                    repository.updateDraft(resetPromptEditors = true) {
                        check(it == selection.expected) { "当前生成草稿已变化，请重新选择预设" }
                        selection.prepared.preset.applyTo(it, materialized)
                    }
                    committed = true; selection.applied = true
                    mutableState.update { it.copy(presetPreview = null, presetOutcome = DesktopPresetApplyOutcome.APPLIED) }
                    try { selection.prepared.markApplied() } catch (_: Exception) { cleanupPending = true }
                }
            }
        } finally { withContext(NonCancellable) {
            if (committed) try { selection.prepared.close(); presetSelection = null } catch (_: Exception) { cleanupPending = true }
            if (created.isNotEmpty()) try { cleanup(created) } catch (_: Exception) { cleanupPending = true }
            try {
                if (presetArchive?.finishPayloads(payloadClaims, cleanup, committed) == false) cleanupPending = true
            } catch (_: Exception) { cleanupPending = true }
            if (committed) {
                if (cleanupPending) mutableState.update { it.copy(presetOutcome = DesktopPresetApplyOutcome.APPLIED_CLEANUP_PENDING) }
                status(if (cleanupPending) "生成预设已应用，可撤销；临时资源清理待核对，归属记录保留，请勿重复导入。"
                    else "生成预设已应用，可撤销；未自动开始生成")
            } else if (cleanupPending) status("应用结果与预设资源清理待核对；原文件和归属记录保留。")
        } }
    }

    suspend fun discardGenerationPreset() = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val applied = presetSelection?.applied == true
            presetSelection?.prepared?.close(); presetSelection = null
            mutableState.update { it.copy(presetPreview = null,
                presetOutcome = if (applied) DesktopPresetApplyOutcome.APPLIED else it.presetOutcome) }
        }
    }

    suspend fun closePresetExchange() = mutex.withLock {
        presetClosed = true
        fullPromptSelection = null
        mutableState.update { it.copy(fullPromptPreview = null) }
        withContext(NonCancellable + Dispatchers.IO) {
            val applied = presetSelection?.applied == true
            try { presetSelection?.prepared?.close() }
            catch (_: Exception) {
                if (applied) mutableState.update { it.copy(presetOutcome = DesktopPresetApplyOutcome.APPLIED_CLEANUP_PENDING) }
                status(if (applied) "预设已应用；临时清理待核对，归属记录保留。" else "预设临时清理待核对；原草稿与归属记录保留。")
            }
            presetSelection = null
            mutableState.update { it.copy(presetPreview = null) }
        }
    }

    private fun requirePresetIdle() {
        check(tasks.tasks.value.none { it.kind == DesktopTaskKind.NOVELAI && it.status == DesktopTaskStatus.RUNNING }) {
            "请先等待或停止当前生图任务"
        }
    }

    suspend fun undo() = action {
        if (!repository.replay(redo = false)) throw DesktopStudioUndoUnavailable()
        textHistories.authorityChanged()
    }

    suspend fun redo() = action {
        if (!repository.replay(redo = true)) throw DesktopStudioUndoUnavailable()
        textHistories.authorityChanged()
    }

    suspend fun importCard(id: String) {
        val card = requireNotNull(characters.getById(id))
        val cardModel = card.defaultNovelAiImageModel
        val globalModel = settings.getAppSettings().novelAiImageModel
        replace { current ->
            val imported = current.importCharacterCardPromptSources(card.id, card.defaultImagePrompt, card.defaultImageNegativePrompt,
                card.characters.map { NovelAiCharacterPromptSource(it.name, it.imagePrompt) })
            val configured = if (cardModel != null)
                imported.copy(selectedModel = cardModel, followDefaultNovelAiImageModel = false)
            else if (current.followDefaultNovelAiImageModel) imported.copy(selectedModel = globalModel)
            else imported
            card.defaultImageGenerationSettings?.let { configured.withActiveSettings(it.applyTo(configured.activeSettings)) } ?: configured
        }
    }

    suspend fun clearPrompts() {
        val current = repository.loadDraft()
        val card = current.importedCharacterCardId?.let { characters.getById(it) }
        replace { it.clearPrompts(card?.defaultImageNegativePrompt) }
    }

    suspend fun availableCards() = characters.getAll()
    suspend fun availableModels() = readDesktopStudioModels { resolver.availableChatModels() }
    suspend fun vibeCacheMisses(draft: NovelAiStudioDraft) = withContext(Dispatchers.IO) { guidance.vibeCacheMisses(draft) }

    suspend fun designRequirement() = repository.loadDraft().extraRequirement
    suspend fun setDesignRequirement(value: String) = action {
        repository.updateDraft { it.copy(extraRequirement = value) }
    }

    fun historyReuseNeedsConfirmation(entry: NovelAiGenerationHistoryEntry, mode: NovelAiHistoryApplyMode) =
        entry.recipe.requiresImageGuidanceReuseWarning(mode)

    suspend fun applyHistory(entry: NovelAiGenerationHistoryEntry, image: NovelAiGenerationHistoryImage,
        mode: NovelAiHistoryApplyMode, warningConfirmed: Boolean = false): Boolean {
        if (!desktopHistoryApplyAvailable(entry.recipe, mode)) {
            status("缺少来源，无法完整复现；未应用。")
            return false
        }
        if (historyReuseNeedsConfirmation(entry, mode) && !warningConfirmed) {
            status("历史缺少原始图片引导；未应用。复用设置或 Seed 前需要确认无法准确复现。")
            return false
        }
        return action {
            mutableState.update { it.copy(applyingHistory = true) }
            try {
                repository.applyHistory(entry, image, mode)
                if (historyReuseNeedsConfirmation(entry, mode)) status("已确认载入可复用参数；非完整复现，原图引导需重新选择")
            } finally { mutableState.update { it.copy(applyingHistory = false) } }
        }
    }

    fun toggleSelection(selection: NovelAiHistoryImageSelection) = mutableState.update {
        it.copy(selected = if (selection in it.selected) it.selected - selection else it.selected + selection)
    }

    fun retainHistorySelection(visible: Set<NovelAiHistoryImageSelection>) = mutableState.update {
        it.copy(selected = it.selected.intersect(visible))
    }

    fun setHistorySelection(selection: Set<NovelAiHistoryImageSelection>) = mutableState.update { it.copy(selected = selection) }

    suspend fun deleteSelected(requested: Set<NovelAiHistoryImageSelection>? = null) = action {
        val selections = (requested ?: state.value.selected).toList()
        require(selections.isNotEmpty())
        val candidates = mutableListOf<String>()
        // Read all affected entries and replace once, before any reference-aware media cleanup.
        coordinator.withNormalOperation { withContext(NonCancellable) {
            storage.mutateEntitiesAtomically(DesktopNovelAiGenerationStore.KEY,
                selections.map { it.entryId }.toSet(), NovelAiGenerationHistoryEntry.serializer()) { reads ->
                val originals = reads.map { (id, read) ->
                    val entry = (read as? EntityReadResult.Valid)?.value ?: error("无法核实历史记录，未删除图片")
                    check(entry.id == id) { "历史记录身份不一致，未删除图片" }
                    entry
                }
                NovelAiHistoryDeletionPolicy.apply(originals, selections)
            }
            candidates += selections.map { it.imagePath }
        } }
        mutableState.update { it.copy(selected = emptySet(), results = it.results - candidates.toSet()) }
        cleanup(candidates)
    }

    suspend fun importMetadata(path: Path, selection: NovelAiStudioMetadataSelection) = action {
        require(Files.size(path) <= DesktopImageEditing.MAX_BYTES)
        val metadata = DesktopImageMetadata.readStudio(path.toString()) ?: error("图片未包含可读取的 NovelAI 元数据")
        coordinator.withNormalOperation { withContext(NonCancellable) {
            val before = repository.loadDraft()
            var next = before.applyImportedMetadata(metadata, selection)
            if (selection.imageGuidance) {
                val imported = metadata.imageGuidance
                fun asset(encoded: String?): NovelAiStudioAssetRef? = encoded?.let {
                    require(it.length <= DesktopImageEditing.MAX_BYTES * 2)
                    val bytes = Base64.getDecoder().decode(it)
                    DesktopImageEditing.requireStatic(bytes)
                    val bitmap = DesktopImageEditing.decode(bytes)
                    val normalized = DesktopImageEditing.png(bitmap)
                    val path = resources.materializeImage(PackagedImage("metadata.png", Base64.getEncoder().encodeToString(normalized)),
                        System.currentTimeMillis(), "p7metadata${UUID.randomUUID()}")
                    NovelAiStudioAssetRef(path = path, width = bitmap.width, height = bitmap.height,
                        sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(normalized).joinToString("") { byte -> "%02x".format(byte) })
                }
                val base = asset(imported.baseImageBase64)
                val mask = asset(imported.maskBase64)
                val precise = asset(imported.preciseImageBase64)
                if (imported.hasAny) next = next.copy(imageGuidance = next.imageGuidance.copy(baseImage = base, maskImage = mask,
                    preciseReference = next.imageGuidance.preciseReference.copy(asset = precise),
                    focusedInpaintRegion = null))
            }
            repository.saveUndoDraft(before)
            repository.updateDraft(resetPromptEditors = true) { next }
        } }
    }

    suspend fun importGuidance(path: Path, target: NovelAiImageUseTarget, replacingVibeId: String? = null,
        preserveMode: Boolean = false, expectedAsset: NovelAiStudioAssetRef? = null) = action {
        coordinator.withNormalOperation { withContext(NonCancellable) {
            fun checkSource(guidance: NovelAiImageGuidanceDraft) {
                if (!preserveMode) return
                check(when (target) {
                    NovelAiImageUseTarget.IMAGE_TO_IMAGE, NovelAiImageUseTarget.INPAINT -> guidance.baseImage == expectedAsset
                    NovelAiImageUseTarget.PRECISE_REFERENCE -> guidance.preciseReference.asset == expectedAsset
                    NovelAiImageUseTarget.VIBE_REFERENCE -> guidance.vibes.singleOrNull { it.id == replacingVibeId }?.let { it.asset == expectedAsset } == true
                }) { "原参考图片已变化，请重新选择" }
            }
            checkSource(repository.loadDraft().imageGuidance)
            if (replacingVibeId != null) {
                require(target == NovelAiImageUseTarget.VIBE_REFERENCE)
                require(repository.loadDraft().imageGuidance.vibes.any { it.id == replacingVibeId })
            }
            require(Files.size(path) <= DesktopImageEditing.MAX_BYTES)
            val bytes = Files.readAllBytes(path)
            DesktopImageEditing.requireStatic(bytes)
            val decoded = DesktopImageEditing.decode(bytes)
            val normalized = DesktopImageEditing.png(decoded)
            val reference = resources.materializeImage(PackagedImage("guidance.png", Base64.getEncoder().encodeToString(normalized)),
                System.currentTimeMillis(), "p7guidance${UUID.randomUUID()}")
            val asset = NovelAiStudioAssetRef(path = reference, width = decoded.width, height = decoded.height,
                sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(normalized).joinToString("") { "%02x".format(it) })
            // Unknown commit outcomes retain the newly created candidate. Undo retains previous sources.
            repository.saveUndoDraft(repository.loadDraft())
            repository.updateDraft { old ->
                checkSource(old.imageGuidance)
                val nextGuidance = when (target) {
                NovelAiImageUseTarget.IMAGE_TO_IMAGE -> old.imageGuidance.copy(action = NovelAiGenerationAction.IMAGE_TO_IMAGE, baseImage = asset, maskImage = null)
                NovelAiImageUseTarget.INPAINT -> old.imageGuidance.copy(action = NovelAiGenerationAction.INPAINT, baseImage = asset, maskImage = null,
                    focusedInpaintRegion = null)
                NovelAiImageUseTarget.PRECISE_REFERENCE -> old.imageGuidance.copy(referenceMode = NovelAiReferenceMode.PRECISE,
                    preciseReference = old.imageGuidance.preciseReference.copy(asset = asset))
                NovelAiImageUseTarget.VIBE_REFERENCE -> {
                    require(replacingVibeId != null || old.imageGuidance.vibes.size < NovelAiImageGuidanceDraft.MAX_VIBES)
                    old.imageGuidance.copy(referenceMode = NovelAiReferenceMode.VIBE,
                        vibes = if (replacingVibeId == null) old.imageGuidance.vibes + NovelAiVibeReferenceDraft(asset = asset)
                        else {
                            require(old.imageGuidance.vibes.any { it.id == replacingVibeId })
                            old.imageGuidance.vibes.map { if (it.id == replacingVibeId) it.copy(asset = asset, encodedVibe = null) else it }
                        })
                }
                }
                old.copy(imageGuidance = if (preserveMode) nextGuidance.copy(action = old.imageGuidance.action,
                    referenceMode = old.imageGuidance.referenceMode) else nextGuidance)
            }
        } }
    }

    suspend fun useHistoryImage(reference: String, target: NovelAiImageUseTarget): Boolean {
        resources.readBytes(reference) // Validate owned regular file before resolving.
        return importGuidance(resources.resolveOwnedReference(reference), target)
    }

    suspend fun applyGuidanceEdit(bytes: ByteArray, mask: ByteArray?, region: NovelAiFocusedInpaintRegion) = action {
        coordinator.withNormalOperation { withContext(NonCancellable) {
            fun materialize(value: ByteArray, prefix: String): NovelAiStudioAssetRef {
                DesktopImageEditing.requireStatic(value)
                val image = DesktopImageEditing.decode(value)
                val png = DesktopImageEditing.png(image)
                val path = resources.materializeImage(PackagedImage("edited.png", Base64.getEncoder().encodeToString(png)),
                    System.currentTimeMillis(), "p7${prefix}${UUID.randomUUID()}")
                return NovelAiStudioAssetRef(path = path, width = image.width, height = image.height,
                    sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) })
            }
            val base = materialize(bytes, "base")
            val painted = mask?.let { materialize(it, "mask") }
            val before = repository.loadDraft()
            val next = before.imageGuidance.copy(baseImage = base, maskImage = painted, focusedInpaintRegion = region)
            next.validationError(before.selectedModel)?.let { error(it) }
            repository.saveUndoDraft(before)
            repository.updateDraft { it.copy(imageGuidance = next) }
            repository.clearGuidanceCheckpoint()
        } }
    }

    suspend fun generate() = action {
        val (taskIdentity, frozen) = studioCredentialRead { credentialIdentity() to freezeCredential() }
        var taskAccount = state.value.account.takeIf { accountIdentity == taskIdentity }
        val launch = resolveDefaultModel()
        require(launch.basePrompt.isNotBlank()) { "请填写基础 Prompt" }
        launch.activeSettings.requestValidationError(launch.activeCharacters.size)?.let { error(it) }
        launch.imageGuidance.validationError(launch.selectedModel)?.let { error(it) }
        val id = tasks.launchNovelAi("NovelAI Studio") { report -> withContext(frozen) {
            try {
                val encodingMisses = guidance.vibeCacheMisses(launch)
                val prepared = guidance.prepare(launch)
                val total = if (launch.continuousModeEnabled) launch.continuousTargetCount.coerceAtLeast(1) else launch.activeSettings.count
                var completed = 0
                while (completed < total) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(launch.activeSettings.count, total - completed)
                    val batch = launch.copy(imageGuidance = prepared.retainedGuidance).withActiveSettings(launch.activeSettings.copy(count = count))
                    val estimatedCost = NovelAiImageCostEstimator.estimate(batch.activeSettings, taskAccount,
                        batch.imageGuidance, if (completed == 0) encodingMisses else 0)
                    val result = runtime.generate(batch, guidance = prepared.guidance, requestSize = prepared.requestSize,
                        composeResult = prepared.compose, retryRateLimitsUntilCancelled = launch.continuousModeEnabled,
                        onRetry = { attempt, delay -> status("429 · 第 $attempt 次等待 ${delay}ms"); report("等待限流恢复") },
                        onIntermediate = { bytes, step, progress ->
                            mutableState.update { it.copy(preview = bytes, status = "$completed/$total · Step $step · ${(progress * 100).toInt()}%") }
                            report("$completed/$total · Step $step")
                        })
                    completed += result.images.size
                    withContext(NonCancellable) { mutableState.update { it.copy(results = it.results + result.images.map { image -> image.path }, preview = null, status = "已保存 $completed/$total") } }
                    if (taskIdentity == studioCredentialRead(credentialIdentity)) {
                        accountAfterSuccess(estimatedCost, result.images.size); taskAccount = state.value.account
                    } else taskAccount = null
                }
            } catch (cancelled: CancellationException) {
                status("已停止；历史中的已提交批次保留"); throw cancelled
            } catch (failure: Exception) {
                if (failure is DesktopStudioCredentialReadException) {
                    invalidateAccount(STUDIO_ACCOUNT_READ_FAILURE)
                    status("账户读取失败；历史中已提交的批次保留")
                } else status("生成失败，未发布不完整批次")
                throw IllegalStateException("Studio generation failed")
            }
            finally { mutableState.update { it.copy(preview = null) } }
        } }
        mutableState.update { it.copy(taskId = id, generationTaskId = id) }
    }

    suspend fun design(input: String, newConversation: Boolean = designComposer.value.composingNew, attach: Boolean = false) = action(designAction = true) {
        val text = input.trim()
        require(text.isNotBlank())
        val launch = resolveDefaultModel()
        val app = settings.getAppSettings()
        val model = designModel(launch.aiDesignModelId, app)
        val target = if (launch.aiDesignNaturalLanguageMode) NovelAiImageModel.V5_FULL else launch.selectedModel
        val attachment = if (attach) {
            require(launch.basePrompt.isNotBlank() && launch.activeCharacters.all { it.prompt.isNotBlank() })
            require(launch.activeCharacters.size <= target.maxCharacters)
            NovelAiPositivePromptSnapshot(launch.basePrompt, launch.activeCharacters.map { it.prompt })
        } else null
        val id = tasks.launchNovelAi("Prompt Designer") { report ->
            val current = designRepository.currentConversation().takeUnless { newConversation }
            val pair = if (current == null) designRepository.createCurrentConversation(
                text, model.id, target, launch.aiDesignNaturalLanguageMode,
                NovelAiDesignContextSnapshot(characterImagePrompts = launch.importedCharacterPromptSources,
                    finalPromptRequirement = launch.extraRequirement), attachment)
            else current to designRepository.appendPendingTurn(current.id, text,
                model.id, target, launch.aiDesignNaturalLanguageMode, attachment)
            // The turn and current pointer are durable before consuming input or migrating legacy data.
            var legacyCleanupFailed = false
            withContext(NonCancellable) {
                mutableDesignComposer.update { it.copy(composingNew = false,
                    input = if (it.input == input) "" else it.input, attachStudioPrompt = false, revision = it.revision + 1) }
                if (current == null && launch.imageDescription.isNotBlank()) {
                    try {
                        repository.updateDraft { if (it.imageDescription == launch.imageDescription) it.copy(imageDescription = "") else it }
                    } catch (_: Exception) { legacyCleanupFailed = true }
                }
            }
            try { runDesignTurn(pair.first.id, pair.second.id, model, report) }
            finally {
                if (legacyCleanupFailed) mutableState.update { it.copy(status = it.status + "；对话已保存，旧兼容输入清理未完成") }
            }
        }
        mutableState.update { it.copy(taskId = id) }
    }

    private suspend fun runDesignTurn(conversationId: String, turnId: String,
        model: com.example.chatbar.data.local.entity.ModelConfig, report: (String) -> Unit) {
        mutableState.update { it.copy(designProgress = DesktopReversePromptProgress(stage = "正在规划画面与本地 Tag 检索")) }
        try {
            NovelAiDesignTurnRunner(designRepository, infrastructure.promptDesigner()).run(
                conversationId, turnId, model, settings.getPlayerSetting().playerName,
                onContent = { content -> mutableState.update { it.copy(designProgress = it.designProgress.content(content)) }; report("Prompt Designer 正在生成") },
                onReasoning = { reasoning -> mutableState.update { it.copy(designProgress = it.designProgress.copy(reasoning = reasoning)) } })
            status("设计结果已保存；可选择应用到 Studio")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { designRepository.failTurn(conversationId, turnId, "已停止生成，可重试", true) }
            status("设计已停止"); throw cancelled
        } catch (failure: Exception) {
            val safe = desktopDesignFailure(failure)
            withContext(NonCancellable) { designRepository.failTurn(conversationId, turnId, safe.text) }
            status(safe.text); throw DesktopDesignException(safe)
        }
    }

    suspend fun retryDesign(conversation: NovelAiDesignConversation, turn: NovelAiDesignTurn, regenerate: Boolean = false,
        editedText: String? = null) = action(designAction = true) {
        val draft = repository.loadDraft()
        val model = designModel(draft.aiDesignModelId)
        val natural = if (regenerate) draft.aiDesignNaturalLanguageMode else turn.naturalLanguageMode
        val target = if (natural) NovelAiImageModel.V5_FULL else turn.targetImageModel
        val id = tasks.launchNovelAi("Prompt Designer") { report ->
            val pair = if (editedText != null) designRepository.editTurnAndCreateCurrentConversation(
                conversation.id, turn.id, editedText, model.id)
            else conversation to designRepository.markTurnPending(conversation.id, turn.id, model.id, target, natural)
            if (editedText != null) openCurrentDesign()
            runDesignTurn(pair.first.id, pair.second.id, model, report)
        }
        mutableState.update { it.copy(taskId = id) }
    }

    suspend fun applyDesign(reply: NovelAiDesignReply) = replace { it.applyDesignedPromptPlan(reply.plan, reply.targetImageModel) }
    suspend fun switchDesign(id: String) = action { designRepository.switchCurrent(id); openCurrentDesign() }
    suspend fun deleteDesign(id: String) = action { designRepository.deleteHistoryConversation(id) }

    suspend fun reverseImage(bytes: ByteArray): Boolean {
        val expectedRevision = synchronized(reverseLock) { reverseRevision }
        return action {
            DesktopImageEditing.requireStatic(bytes)
            DesktopImageEditing.decode(bytes)
            val launch = resolveDefaultModel()
            val encoded = Base64.getEncoder().encodeToString(bytes)
            startReverse(launch, null, encoded, expectedRevision)
        }
    }

    fun beginReverseSceneEdit(): DesktopReverseSceneEdit? = synchronized(reverseLock) {
        val selection = reverseSelection ?: return@synchronized null
        val scene = state.value.reverseSceneDescription ?: return@synchronized null
        if (state.value.reverseBusy) null else DesktopReverseSceneEdit(selection.revision, scene)
    }

    fun applyReverseSceneEdit(editor: DesktopReverseSceneEdit, text: String): Boolean = synchronized(reverseLock) {
        if (state.value.reverseBusy || reverseSelection?.revision != editor.revision ||
            state.value.reverseSceneDescription != editor.original) {
            status("场景已变化，本次编辑未应用；请重新打开编辑器")
            return@synchronized false
        }
        if (text != editor.original) updateReverseSceneDescription(text)
        true
    }

    fun updateReverseSceneDescription(text: String) = synchronized(reverseLock) {
        if (state.value.reverseBusy) return@synchronized
        val selection = reverseSelection ?: return@synchronized
        reverseSelection = selection.copy(revision = ++reverseRevision)
        mutableState.update { it.copy(reverseSceneDescription = text, reverseCandidate = null, reverseTargetModel = null) }
    }

    suspend fun confirmReverseSceneDescription(): Boolean {
        val (capturedSelection, capturedScene) = synchronized(reverseLock) { reverseSelection to state.value.reverseSceneDescription }
        return action {
            val selection = requireNotNull(capturedSelection)
            val scene = requireNotNull(capturedScene).also { require(it.isNotBlank()) { "请填写场景描述" } }
            check(repository.loadDraft() == selection.draft) { "Studio 草稿已变化，请重新反推" }
            startReverse(selection.draft, scene, null, selection.revision)
        }
    }

    private suspend fun startReverse(launch: NovelAiStudioDraft, scene: String?, encoded: String?, expectedRevision: Long) {
        val selection = synchronized(reverseLock) {
            check(reverseRevision == expectedRevision) { "反推图片已更换或关闭" }
            check(!state.value.reverseBusy) { "图片反推任务仍在运行" }
            ReverseSelection(++reverseRevision, launch).also {
                reverseSelection = it
                mutableState.update { state -> state.copy(reverseCandidate = null, reverseTargetModel = null, reverseSceneDescription = scene,
                    reverseBusy = true, reverseProgress = DesktopReversePromptProgress(stage =
                        if (scene == null) "正在识别图片场景" else "正在根据确认场景设计 Prompt")) }
            }
        }
        fun publish(change: (DesktopNovelAiStudioState) -> DesktopNovelAiStudioState) = synchronized(reverseLock) {
            if (reverseSelection == selection) mutableState.update(change)
        }
        try {
            val app = settings.getAppSettings()
            val model = designModel(launch.aiDesignModelId, app)
            val player = settings.getPlayerSetting().playerName
            check(synchronized(reverseLock) { reverseSelection == selection }) { "反推图片已更换或关闭" }
            val id = tasks.launchNovelAi(if (scene == null) "识别图片场景" else "图片反推 Prompt") { report ->
                try {
                    check(synchronized(reverseLock) { reverseSelection == selection }) { "反推图片已更换或关闭" }
                    currentCoroutineContext().ensureActive()
                    val designer = infrastructure.promptDesigner()
                    val references = launch.importedCharacterPromptSources.map { it.name to it.prompt }
                    val progress: (String) -> Unit = { text ->
                        publish { it.copy(reverseProgress = it.reverseProgress.content(text)) }; report("正在反推图片")
                    }
                    val target = if (launch.aiDesignNaturalLanguageMode) NovelAiImageModel.V5_FULL else launch.selectedModel
                    if (scene == null) {
                        val description = designer.describeReferenceImage(requireNotNull(encoded), model, target,
                            references, player, progress)
                        currentCoroutineContext().ensureActive()
                        check(description.isNotBlank()) { "图片场景描述为空" }
                        check(repository.loadDraft() == launch) { "Studio 草稿已变化" }
                        publish { it.copy(reverseSceneDescription = description, reverseBusy = false,
                            reverseProgress = DesktopReversePromptProgress(stage = "请核对或编辑场景描述，再确认设计")) }
                    } else {
                        val result = designer.designForPromptToolDetailed(
                            imageDescription = "", characterPrompt = "", characterImagePrompts = references,
                            finalPromptRequirement = launch.extraRequirement, referenceImageProvided = true,
                            model = model, playerName = player, targetImageModel = target,
                            naturalLanguageMode = launch.aiDesignNaturalLanguageMode, confirmedSceneDescription = scene,
                            referenceImageInstruction = com.example.chatbar.domain.prompt.NovelAiPromptAuthority.novelAiImageReversePromptUser(target.displayName),
                            excludeStyle = false, onContentDelta = progress,
                            onReasoningDelta = { text -> publish { it.copy(reverseProgress = it.reverseProgress.copy(reasoning = text)) } })
                        currentCoroutineContext().ensureActive()
                        check(repository.loadDraft() == launch) { "Studio 草稿已变化" }
                        publish { it.copy(reverseCandidate = result.plan, reverseTargetModel = target, reverseBusy = false,
                            reverseProgress = it.reverseProgress.copy(stage = "候选已就绪，请预览后应用")) }
                    }
                } catch (cancelled: CancellationException) {
                    publish { it.copy(reverseBusy = false, reverseProgress = it.reverseProgress.copy(stage = "已停止，可重试")) }
                    throw cancelled
                } catch (_: Exception) {
                    publish { it.copy(reverseBusy = false, reverseCandidate = null,
                        reverseProgress = it.reverseProgress.copy(stage = "反推失败或草稿已变化，当前 Prompt 保留")) }
                    throw IllegalStateException("Reverse failed")
                }
            }
            synchronized(reverseLock) {
                if (reverseSelection == selection) reverseTaskId = id else tasks.requestUserStop(id)
            }
            mutableState.update { it.copy(taskId = id) }
        } catch (failure: Exception) {
            publish { it.copy(reverseBusy = false) }
            throw failure
        }
    }

    suspend fun applyReverseCandidate(): Boolean {
        val captured = synchronized(reverseLock) { Triple(reverseSelection, state.value.reverseCandidate, state.value.reverseTargetModel) }
        return action {
            val selection = requireNotNull(captured.first)
            check(!state.value.reverseBusy)
            val candidate = requireNotNull(captured.second)
            val target = requireNotNull(captured.third)
            val before = repository.loadDraft()
            check(before == selection.draft) { "Studio 草稿已变化，请重新反推" }
            repository.saveUndoDraft(before)
            repository.updateDraft(resetPromptEditors = true) { current ->
                synchronized(reverseLock) {
                    check(reverseSelection == selection) { "反推图片已更换或关闭" }
                    check(current == selection.draft) { "Studio 草稿已变化，请重新反推" }
                    current.applyReversePromptPlan(candidate, target)
                }
            }
            clearReverseCandidate()
        }
    }

    fun stopReverse() {
        val id = synchronized(reverseLock) {
            val revision = ++reverseRevision
            reverseSelection = reverseSelection?.copy(revision = revision)
            mutableState.update { it.copy(reverseBusy = false,
                reverseProgress = it.reverseProgress.copy(stage = "已停止，可重试")) }
            reverseTaskId.also { reverseTaskId = null }
        }
        id?.let(tasks::requestUserStop)
    }

    fun clearReverseCandidate() {
        val id = synchronized(reverseLock) {
            reverseSelection = null; reverseRevision++
            mutableState.update { it.copy(reverseCandidate = null, reverseTargetModel = null, reverseSceneDescription = null,
                reverseBusy = false, reverseProgress = DesktopReversePromptProgress()) }
            reverseTaskId.also { reverseTaskId = null }
        }
        id?.let(tasks::requestUserStop)
    }

    suspend fun translationEnabled() = settings.getAppSettings().novelAiPromptTranslationConsent ==
        com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent.ENABLED
    suspend fun setTranslation(enabled: Boolean) = settings.updateAppSettings { it.copy(novelAiPromptTranslationConsent =
        if (enabled) com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent.ENABLED
        else com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent.DISABLED) }

    fun stop() { tasks.tasks.value.firstOrNull { it.kind == DesktopTaskKind.NOVELAI && it.status == DesktopTaskStatus.RUNNING }?.taskId?.let(tasks::requestUserStop) }
    private fun status(text: String) = mutableState.update { it.copy(status = text) }
    private suspend fun cleanupAfterCommit(candidates: List<String>) {
        if (candidates.isEmpty()) return
        try { cleanup(candidates) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status("操作已保存；无法核实清理引用，旧图片保留") }
    }

    private suspend fun action(designAction: Boolean = false, inspectAssets: Boolean = true, block: suspend () -> Unit) = mutex.withLock {
        try {
            val candidates = if (inspectAssets && state.value.ready) buildSet {
                draft.value?.imageGuidance?.ownedAssetPaths()?.let(::addAll)
                repository.loadUndoDraft()?.imageGuidance?.ownedAssetPaths()?.let(::addAll)
                repository.loadGuidanceCheckpoint()?.ownedAssetPaths()?.let(::addAll)
            }.toList() else emptyList()
            withContext(Dispatchers.IO) { block() }
            cleanupAfterCommit(candidates + repository.takeRetiredResources())
            true
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (failure is DesktopStudioUndoUnavailable) status("当前没有可撤销的修改")
            else if (failure is DesktopStudioCredentialReadException) {
                invalidateAccount(STUDIO_ACCOUNT_READ_FAILURE); status(STUDIO_ACCOUNT_READ_FAILURE)
            } else status(if (designAction) desktopDesignFailure(failure).text else "操作失败，请检查输入或存储；现有图片保留")
            false
        }
    }
}
