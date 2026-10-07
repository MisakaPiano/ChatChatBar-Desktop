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

internal data class DesktopNovelAiStudioState(
    val ready: Boolean = false,
    val credentialConfigured: Boolean = false,
    val applyingHistory: Boolean = false,
    val accountUi: NovelAiAccountUiState = NovelAiAccountUiState(loading = false),
    val status: String = "",
    val taskId: String? = null,
    val preview: ByteArray? = null,
    val reverseCandidate: NovelAiPromptPlan? = null,
    val reverseProgress: DesktopReversePromptProgress = DesktopReversePromptProgress(),
    val designProgress: DesktopReversePromptProgress = DesktopReversePromptProgress(),
    val catalogUpdate: com.example.chatbar.domain.update.DanbooruCatalogUpdateInfo? = null,
    val results: List<String> = emptyList(),
    val selected: Set<NovelAiHistoryImageSelection> = emptySet(),
) {
    val account: NovelAiAccountUsage? get() = accountUi.takeIf { it.error == null }?.effectiveUsage
}

internal data class DesktopReversePromptProgress(val stage: String = "", val content: String = "", val reasoning: String = "") {
    fun content(value: String) = copy(content = value,
        stage = Regex("【([^】]+)】").findAll(value).lastOrNull()?.groupValues?.get(1) ?: "正在理解图片")
}

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
) {
    val repository = NovelAiStudioStateRepository(storage)
    val designRepository = NovelAiDesignConversationRepository(storage)
    val draft = repository.draft
    val history = repository.history
    private val mutex = Mutex()
    private val accountMutex = Mutex()
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
        val processor = requireNotNull(postProcessor)
        val account = state.value.account
        val id = tasks.launchNovelAi(tab.label) { report ->
            try {
                onStatus("正在请求 ${tab.label}")
                val result = processor.process(bytes, tab, source, options, account) { onStatus(it); report(it) }
                currentCoroutineContext().ensureActive()
                onComplete(result); onStatus("处理完成；来源未改变")
                refreshAccount()
            } catch (cancelled: CancellationException) { onStatus("已停止；来源和上次结果保留"); throw cancelled }
            catch (_: Exception) { onStatus("图片处理未完成；来源和上次结果保留，请检查配置或服务状态"); throw DesktopNovelAiRequestException("图片处理未完成") }
        }
        mutableState.update { it.copy(taskId = id) }
    }

    suspend fun load() = action {
        repository.initialize()
        designRepository.initialize()
        repository.loadDraft()
        resolveDefaultModel()
        val latest = history.first().firstOrNull()?.images.orEmpty().map { it.path }
        mutableState.update { it.copy(ready = true, results = latest) }
        refreshAccount()
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
        val configured = try {
            withContext(Dispatchers.IO) { credentialConfigured() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(credentialConfigured = false,
                accountUi = NovelAiAccountUiState(loading = false, error = "无法读取安全凭据，请检查 NovelAI 安全设置")) }
            return@withLock
        }
        mutableState.update { it.copy(credentialConfigured = configured,
            accountUi = if (configured && it.credentialConfigured) it.accountUi.copy(loading = true)
                else if (configured) NovelAiAccountUiState(loading = true)
                else NovelAiAccountUiState(loading = false, error = "未配置 Token")) }
        if (!configured) return@withLock
        try {
            val latest = withContext(Dispatchers.IO) { account() }
            mutableState.update { it.copy(accountUi = it.accountUi.reconcile(latest)) }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(accountUi = it.accountUi.copy(loading = false)) }; throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(accountUi = it.accountUi.copy(loading = false, error = "账户信息获取失败；免费资格未确认")) }
        }
    }

    internal suspend fun accountAfterSuccess(cost: NovelAiGenerationCost, count: Int) {
        mutableState.update { current -> current.copy(accountUi = current.accountUi
            .recordAnlasGeneration(cost.anlas.toLong()).let {
                if (cost.kind == NovelAiGenerationChargeKind.V5_ALLOWANCE) it.recordV5Generation(count) else it
            }) }
        refreshAccount()
    }

    suspend fun edit(change: (NovelAiStudioDraft) -> NovelAiStudioDraft) = action {
        repository.updateDraft(transform = change)
    }

    suspend fun replace(change: (NovelAiStudioDraft) -> NovelAiStudioDraft) = action {
        val before = repository.loadDraft()
        repository.saveUndoDraft(before)
        repository.updateDraft(resetPromptEditors = true, transform = change)
    }

    suspend fun undo() = action {
        val previous = repository.loadUndoDraft() ?: error("没有可撤销操作")
        repository.updateDraft(resetPromptEditors = true) { previous }
        repository.clearUndoDraft()
    }

    suspend fun importCard(id: String) {
        val card = requireNotNull(characters.getById(id))
        replace { current -> current.importCharacterCardPromptSources(card.id, card.defaultImagePrompt, card.defaultImageNegativePrompt,
            card.characters.map { NovelAiCharacterPromptSource(it.name, it.imagePrompt) }) }
    }

    suspend fun clearPrompts() {
        val current = repository.loadDraft()
        val card = current.importedCharacterCardId?.let { characters.getById(it) }
        replace { it.clearPrompts(card?.defaultImageNegativePrompt) }
    }

    suspend fun availableCards() = characters.getAll()
    suspend fun availableModels() = resolver.availableChatModels().map { it.copy(apiKey = "") }
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

    suspend fun deleteSelected() = action {
        val selections = state.value.selected.toList()
        require(selections.isNotEmpty())
        val candidates = mutableListOf<String>()
        // Never stage/delete source files first. Each entity update is durable before cleanup.
        coordinator.withNormalOperation { withContext(NonCancellable) {
            selections.groupBy { it.entryId }.forEach { (id, selected) ->
                val read = storage.readEntityStrict(DesktopNovelAiGenerationStore.KEY, id,
                    NovelAiGenerationHistoryEntry.serializer())
                val entry = (read as? EntityReadResult.Valid)?.value ?: error("无法核实历史记录，未删除图片")
                val remaining = NovelAiHistoryDeletionPolicy.apply(listOf(entry), selected).getValue(id)
                if (remaining == null) repository.deleteHistory(id) else repository.saveHistory(remaining)
                candidates += selected.map { it.imagePath }
            }
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

    suspend fun importGuidance(path: Path, target: NovelAiImageUseTarget) = action {
        coordinator.withNormalOperation { withContext(NonCancellable) {
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
            repository.updateDraft { old -> old.copy(imageGuidance = when (target) {
                NovelAiImageUseTarget.IMAGE_TO_IMAGE -> old.imageGuidance.copy(action = NovelAiGenerationAction.IMAGE_TO_IMAGE, baseImage = asset, maskImage = null)
                NovelAiImageUseTarget.INPAINT -> old.imageGuidance.copy(action = NovelAiGenerationAction.INPAINT, baseImage = asset, maskImage = null,
                    focusedInpaintRegion = null)
                NovelAiImageUseTarget.PRECISE_REFERENCE -> old.imageGuidance.copy(referenceMode = NovelAiReferenceMode.PRECISE,
                    preciseReference = old.imageGuidance.preciseReference.copy(asset = asset))
                NovelAiImageUseTarget.VIBE_REFERENCE -> {
                    require(old.imageGuidance.vibes.size < NovelAiImageGuidanceDraft.MAX_VIBES)
                    old.imageGuidance.copy(referenceMode = NovelAiReferenceMode.VIBE,
                        vibes = old.imageGuidance.vibes + NovelAiVibeReferenceDraft(asset = asset))
                }
            }) }
        } }
    }

    suspend fun useHistoryImage(reference: String, target: NovelAiImageUseTarget) {
        resources.readBytes(reference) // Validate owned regular file before resolving.
        importGuidance(resources.resolveOwnedReference(reference), target)
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
        val launch = resolveDefaultModel()
        require(launch.basePrompt.isNotBlank()) { "请填写基础 Prompt" }
        launch.activeSettings.validationError(launch.activeCharacters.size)?.let { error(it) }
        launch.imageGuidance.validationError(launch.selectedModel)?.let { error(it) }
        val id = tasks.launchNovelAi("NovelAI Studio") { report ->
            try {
                val encodingMisses = guidance.vibeCacheMisses(launch)
                val prepared = guidance.prepare(launch)
                val total = if (launch.continuousModeEnabled) launch.continuousTargetCount.coerceAtLeast(1) else launch.activeSettings.count
                var completed = 0
                while (completed < total) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(launch.activeSettings.count, total - completed)
                    val batch = launch.copy(imageGuidance = prepared.retainedGuidance).withActiveSettings(launch.activeSettings.copy(count = count))
                    val estimatedCost = NovelAiImageCostEstimator.estimate(batch.activeSettings, state.value.account,
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
                    accountAfterSuccess(estimatedCost, result.images.size)
                }
            } catch (cancelled: CancellationException) {
                status("已停止；历史中的已提交批次保留"); throw cancelled
            } catch (_: Exception) { status("生成失败，未发布不完整批次"); throw IllegalStateException("Studio generation failed") }
            finally { mutableState.update { it.copy(preview = null) } }
        }
        mutableState.update { it.copy(taskId = id) }
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

    suspend fun reverseImage(bytes: ByteArray) = action {
        DesktopImageEditing.requireStatic(bytes)
        DesktopImageEditing.decode(bytes)
        val launch = resolveDefaultModel()
        val app = settings.getAppSettings()
        val model = designModel(launch.aiDesignModelId, app)
        val id = tasks.launchNovelAi("图片反推 Prompt") { report ->
            mutableState.update { it.copy(reverseCandidate = null, reverseProgress = DesktopReversePromptProgress(stage = "正在准备图片反推")) }
            try {
                val plan = infrastructure.promptDesigner().designForPromptTool(
                    imageDescription = "", characterPrompt = "", finalPromptRequirement = launch.extraRequirement,
                    imageBase64s = listOf(Base64.getEncoder().encodeToString(bytes)), model = model,
                    playerName = settings.getPlayerSetting().playerName, targetImageModel = launch.selectedModel,
                    referenceImageInstruction = com.example.chatbar.domain.prompt.NovelAiPromptAuthority.novelAiImageReversePromptUser(launch.selectedModel.displayName),
                    excludeStyle = false, onContentDelta = { text ->
                        mutableState.update { it.copy(reverseProgress = it.reverseProgress.content(text)) }; report("正在反推图片")
                    }, onReasoningDelta = { text -> mutableState.update { it.copy(reverseProgress = it.reverseProgress.copy(reasoning = text)) } })
                mutableState.update { it.copy(reverseCandidate = plan, reverseProgress = it.reverseProgress.copy(stage = "候选已就绪")) }
                status("图片反推候选已就绪，请预览后应用")
            } catch (_: CancellationException) {
                mutableState.update { it.copy(reverseProgress = it.reverseProgress.copy(stage = "已停止，可重试")) }
                status("图片反推已停止"); throw CancellationException()
            } catch (_: Exception) {
                mutableState.update { it.copy(reverseProgress = it.reverseProgress.copy(stage = "反推失败，可重试")) }
                status("图片反推失败或草稿已变化，当前 Prompt 保留"); throw IllegalStateException("Reverse failed")
            }
        }
        mutableState.update { it.copy(taskId = id) }
    }

    suspend fun applyReverseCandidate() = replace { current ->
        current.applyReversePromptPlan(requireNotNull(state.value.reverseCandidate))
    }
    fun clearReverseCandidate() { mutableState.update { it.copy(reverseCandidate = null, reverseProgress = DesktopReversePromptProgress()) } }

    suspend fun translationEnabled() = settings.getAppSettings().novelAiPromptTranslationConsent ==
        com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent.ENABLED
    suspend fun setTranslation(enabled: Boolean) = settings.updateAppSettings { it.copy(novelAiPromptTranslationConsent =
        if (enabled) com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent.ENABLED
        else com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent.DISABLED) }

    fun stop() { tasks.tasks.value.firstOrNull { it.kind == DesktopTaskKind.NOVELAI && it.status == DesktopTaskStatus.RUNNING }?.taskId?.let(tasks::requestUserStop) }
    private fun status(text: String) = mutableState.update { it.copy(status = text) }
    private suspend fun action(designAction: Boolean = false, block: suspend () -> Unit) = mutex.withLock {
        try {
            val candidates = if (state.value.ready) buildSet {
                draft.value?.imageGuidance?.ownedAssetPaths()?.let(::addAll)
                repository.loadUndoDraft()?.imageGuidance?.ownedAssetPaths()?.let(::addAll)
                repository.loadGuidanceCheckpoint()?.ownedAssetPaths()?.let(::addAll)
            }.toList() else emptyList()
            withContext(Dispatchers.IO) { block() }
            try { cleanup(candidates) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { status("操作已保存；无法核实清理引用，旧图片保留") }
            true
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { status(if (designAction) desktopDesignFailure(failure).text else "操作失败，请检查输入或存储；现有图片保留"); false }
    }
}
