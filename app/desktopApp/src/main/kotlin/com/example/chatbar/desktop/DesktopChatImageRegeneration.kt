package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.DesktopAccountStore
import com.example.chatbar.desktop.security.DesktopAccountService
import com.example.chatbar.desktop.security.DesktopCredentialCall
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class DesktopChatImageRequirements(val imageContentHint: String = "", val imagePromptPreference: String,
    val persistPreference: Boolean = true, val batchPlan: ImageBatchRunPlan? = null)

internal enum class DesktopChatImagePreflight(val reason: String) {
    READY(""), SOURCE("来源回复已变化或不可用"), SESSION("会话不存在"), CHARACTER("角色卡不存在"),
    CREDENTIAL("未配置 NovelAI Token"), MODEL("生图辅助模型不可用"), AUTH("生图辅助模型认证未配置"),
    RATIO(requireNotNull(NovelAiImageSizePolicy.validationError("invalid"))), CONFIG("无法读取生图配置，请检查安全存储与设置"),
}
internal class DesktopChatImagePreflightException(val result: DesktopChatImagePreflight) : IllegalStateException(result.reason)

/** Formal chat regeneration creates another linked image message; it does not mutate its source. */
internal class DesktopChatImageRegeneration(
    private val chats: ChatRepository, private val characters: CharacterRepository,
    private val settings: SettingsRepository, private val resources: DesktopCharacterResourceStore,
    private val coordinator: DesktopDataOperationCoordinator, private val secrets: DesktopSecretStore,
    private val tasks: DesktopTaskRuntime, val infrastructure: DesktopNovelAiInfrastructure,
    private val resolver: com.example.chatbar.domain.model.EffectiveModelResolver? = null,
    private val imageClient: okhttp3.OkHttpClient? = null,
    private val accountForBatch: (suspend () -> NovelAiAccountUsage)? = null,
) {
    val taskStates get() = tasks.tasks
    private val controls = java.util.concurrent.ConcurrentHashMap<String, ImageBatchControl>()
    private val progress = MutableStateFlow<Map<String, ImageBatchProgress>>(emptyMap())
    val batchProgress = progress.asStateFlow()
    private val accountService by lazy { NovelAiAccountService(imageClient ?: secureNovelAiClient()) }
    private val previewAccounts = DesktopImageBatchAccountPreview()
    suspend fun previewManualImageRun(original: ChatMessage, configuration: ImageBatchConfiguration, refresh: Boolean = false): String {
        configuration.validationError()?.let { return it }
        val session = chats.getSession(original.sessionId) ?: return "会话已删除"
        val card = characters.getById(session.characterCardId) ?: return "角色卡不存在"
        val app = settings.getAppSettings()
        val target = NovelAiImageModelResolution.resolve(session.novelAiImageModel, card.defaultNovelAiImageModel, app.novelAiImageModel)
        val frozen = credential()
        val token = frozen.read()?.takeIf { it.isNotBlank() } ?: return "未配置 NovelAI Token，计费状态未知"
        val usage = withContext(frozen) { previewAccounts.read(token, refresh) {
            accountForBatch?.invoke() ?: accountService.fetchCancellable(token)
        } }
        val generation = NovelAiGenerationSettings(model = target, guidance = 8f)
            .let { card.defaultImageGenerationSettings?.applyTo(it) ?: it }
        val costs = configuration.freeze(ImageBatchOrigin.CHAT_MANUAL).batches.map { count ->
            NovelAiImageSizePreset.entries.maxOf { preset ->
                val size = NovelAiImageSizePolicy.resolve(app.novelAiImageAspectRatio, preset)
                NovelAiImageCostEstimator.estimate(generation.copy(count = count, customWidth = size.width, customHeight = size.height), usage).anlas.toLong()
            }
        }
        return "会话角色卡：${card.name} · 生图模型：${target.displayName}\n" +
            (if (usage == null) "账户状态未知；图片请求前必须成功查询。" else "当前 Anlas ${usage.anlas} · V5 额度约 ${usage.v5AllowancePercent ?: "未知"}%（另计）") +
            "\n按可能画幅估算各批 ${costs.joinToString(" + ")} Anlas；整项 ${costs.sum()} Anlas。设计后按实际画幅重新核对，估算可能变化。"
    }
    fun pauseBatch(messageId: String) {
        controls[messageId]?.pause()
        progress.update { values -> values[messageId]?.let { values + (messageId to it.copy(detail = "已请求暂停；当前批次完成后生效")) } ?: values }
    }
    fun resumeBatch(messageId: String) {
        controls[messageId]?.resume()
        progress.update { values -> values[messageId]?.let { values + (messageId to it.copy(detail = "已请求继续；仍遵守剩余等待")) } ?: values }
    }
    fun batchPaused(messageId: String): Boolean = controls[messageId]?.isPaused == true
    fun confirmBatchPayment(messageId: String, ticket: String) {
        val control = controls[messageId] ?: return
        if (control.approvePayment(ticket)) progress.update { values ->
            val current = values[messageId]?.takeIf { it.paymentTicket == ticket } ?: return@update values
            values + (messageId to current.copy(paymentTicket = null, confirmationEstimatedTotal = null,
                detail = if (control.isPaused) "费用已确认；任务仍暂停，可点击继续批次" else "本轮费用已确认，发出请求前仍会重新核费"))
        }
    }
    fun batchRunning(messageId: String): Boolean = controls.containsKey(messageId)
    private suspend fun credential(): DesktopCredentialCall {
        val key = DesktopCredentialKey.NovelAiToken
        currentCoroutineContext()[DesktopCredentialCall]?.takeIf { it.owner === secrets && it.credentialKey == key }?.let { return it }
        return (secrets as? DesktopAccountStore)?.captureCall(key, DesktopAccountService.NOVELAI)
            ?: DesktopCredentialCall(secrets, key, "unmanaged", secrets.load(key))
    }
    /** Presence/authentication booleans only leave this boundary; no account request or task admission. */
    suspend fun preflight(original: ChatMessage): DesktopChatImagePreflight = try {
        when {
            original.role != MessageRole.ASSISTANT || original.displayContent.isBlank() -> DesktopChatImagePreflight.SOURCE
            chats.readMessageDurable(original.id, original.sessionId) != EntityReadResult.Valid(original) -> DesktopChatImagePreflight.SOURCE
            else -> {
                val session = (chats.readSessionDurable(original.sessionId) as? EntityReadResult.Valid)?.value
                val card = session?.let { characters.getById(it.characterCardId) }
                val app = settings.getAppSettings()
                val model = session?.let { resolver?.resolveImageModel(it.imageModelId, app) }
                when {
                    session == null -> DesktopChatImagePreflight.SESSION
                    card == null -> DesktopChatImagePreflight.CHARACTER
                    secrets.load(DesktopCredentialKey.NovelAiToken).isNullOrBlank() -> DesktopChatImagePreflight.CREDENTIAL
                    model == null || model.baseUrl.toHttpUrlOrNull() == null -> DesktopChatImagePreflight.MODEL
                    !model.hasConfiguredAuthentication(app) -> DesktopChatImagePreflight.AUTH
                    NovelAiImageSizePolicy.validationError(app.novelAiImageAspectRatio) != null -> DesktopChatImagePreflight.RATIO
                    else -> {
                        NovelAiImageModelResolution.resolve(session.novelAiImageModel, card.defaultNovelAiImageModel, app.novelAiImageModel)
                        DesktopChatImagePreflight.READY
                    }
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { DesktopChatImagePreflight.CONFIG }

    private suspend fun requirePreflight(original: ChatMessage) {
        val result = preflight(original)
        if (result != DesktopChatImagePreflight.READY) throw DesktopChatImagePreflightException(result)
    }
    suspend fun savePreferences(original: ChatMessage, baseline: ChatSession, preferences: ImageBatchPreferences,
        imagePreference: String) {
        require(baseline.id == original.sessionId)
        preferences.validationError()?.let { error(it) }
        require(chats.readMessageDurable(original.id, original.sessionId) == EntityReadResult.Valid(original))
        require(chats.getSession(baseline.id)?.characterCardId == baseline.characterCardId)
        chats.saveSessionSettingsDraft(baseline, baseline.copy(imageBatchPreferences = preferences,
            imagePromptPreference = imagePreference))
    }
    /** Explicit Assistant-message action mirrors ChatViewModel.generateNovelAiImage; no eligibility AI judge. */
    suspend fun generateFromAssistant(original: ChatMessage, requirements: DesktopChatImageRequirements? = null,
        automatic: Boolean = false, stopped: () -> Boolean = { false }): String {
        requirePreflight(original)
        val openingSession = requireNotNull(chats.getSession(original.sessionId))
        val origin = if (automatic) ImageBatchOrigin.CHAT_AUTOMATIC else ImageBatchOrigin.CHAT_MANUAL
        val batchPlan = requirements?.batchPlan ?: openingSession.imageBatchPreferences.configuration().freeze(origin)
        require(batchPlan.origin == origin)
        suspend fun eligible(): Boolean {
            val current = (chats.readSessionDurable(original.sessionId) as? EntityReadResult.Valid)?.value ?: return false
            return !stopped() && (!automatic || current.automaticImageGenerationEnabled) &&
                current.characterCardId == openingSession.characterCardId &&
                chats.readMessageDurable(original.id, original.sessionId) == EntityReadResult.Valid(original)
        }
        require(eligible())
        val session = openingSession
        val originalCard = requireNotNull(characters.getById(session.characterCardId))
        val card = com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.effectiveCard(session, originalCard)
        val app = settings.getAppSettings()
        val model = requireNotNull(resolver?.resolveImageModel(session.imageModelId, app))
        val target = NovelAiImageModelResolution.resolve(session.novelAiImageModel, card.defaultNovelAiImageModel, app.novelAiImageModel)
        val messages = chats.getInitialMessagePage(original.sessionId, original.id).messages
        val playerName = com.example.chatbar.domain.chat.PlayerRolePolicy.name(session, originalCard, settings.getPlayerSetting().playerName)
        val frozen = credential()
        require(!frozen.read().isNullOrBlank())
        val preference = requirements?.imagePromptPreference ?: openingSession.imagePromptPreference
        // Remember the confirmed choice once at submission, outside the replayable task body.
        // A retry retains its request snapshot without writing over a later session preference.
        if (requirements?.persistPreference == true) chats.saveSessionSettingsDraft(openingSession,
            openingSession.copy(imagePromptPreference = preference))
        val control = ImageBatchControl()
        check(controls.putIfAbsent(original.id, control) == null)
        try {
        return tasks.launchNovelAi("聊天图片", original.sessionId, original.id,
            retryable = !automatic && batchPlan.targetTotalImages == 1, queueIfBusy = true,
            batchManagedWork = true, deferSourceCommitForRepair = true,
            onFinished = { controls.remove(original.id, control) }) { report ->
            val runControl = controls[original.id]?.takeIf { it === control } ?: ImageBatchControl().also {
                check(controls.putIfAbsent(original.id, it) == null)
            }
            progress.update { it + (original.id to ImageBatchProgress(target = batchPlan.targetTotalImages,
                batches = batchPlan.batches.size, detail = "正在准备本次图片；尚未发出生图请求")) }
            withContext(frozen) {
            try {
            require(eligible())
            val plan = infrastructure.promptDesigner().design(messages,
                original.id, card, model, playerName = playerName,
                sessionId = session.id, imageContentHint = requirements?.imageContentHint.orEmpty(),
                finalPromptRequirement = preference, targetImageModel = target,
                naturalLanguageMode = session.novelAiNaturalLanguageMode && target == NovelAiImageModel.V5_FULL,
                onDelta = { report.designSnapshot(it, model.apiKey) })
            currentCoroutineContext().ensureActive()
            require(eligible())
            val size = NovelAiImageSizePolicy.resolve(app.novelAiImageAspectRatio, plan.sizePreset)
            val edit = plan.toRegenerationDraft()
            val launch = NovelAiGenerationSettings(model = target, customWidth = size.width, customHeight = size.height, guidance = 8f, count = 1)
                .let { card.defaultImageGenerationSettings?.applyTo(it) ?: it }
            val draft = NovelAiStudioDraft(stylePrompt = edit.stylePrompt, basePrompt = edit.baseCaption, negativePrompt = edit.negativePrompt,
                characters = edit.characterPrompts.map { NovelAiCharacterPromptDraft(prompt = it.prompt, negativePrompt = it.negativePrompt) }).withActiveSettings(launch)
            report.generationStatus("正在生成图片")
            val runtime = DesktopNovelAiGenerationRuntime(secrets, persist = { bytes, recipe ->
                report.withSourceCommit {
                    persistDesktopChatImages(chats, resources, coordinator, original, plan, size, bytes, recipe, ::eligible,
                        expectedCharacterCardId = openingSession.characterCardId)
                }
            }, client = imageClient ?: secureNovelAiClient(), diagnostics = tasks.diagnostics)
            runDesktopManualImageBatch(batchPlan, runControl, requireNotNull(frozen.read()),
                runner = ImageBatchRunner(admission = tasks.imageBatchAdmission),
                account = { accountForBatch?.invoke() ?: accountService.fetchCancellable(requireNotNull(frozen.read())) },
                estimate = { count, usage -> NovelAiImageCostEstimator.estimate(launch.copy(count = count), usage) },
                validateOwner = { require(eligible()) { "图片来源或角色卡绑定已变化，批次停止" } },
                onProgress = { update ->
                    progress.update { (it + (original.id to runControl.currentProgress(update))).entries.toList().takeLast(128).associate { entry -> entry.toPair() } }
                    report.generationStatus(update.label())
                }) { count, _ ->
                require(eligible())
                runtime.generate(draft.withActiveSettings(launch.copy(count = count)), promptPlan = plan, safeBatchRequest = true,
                    onIntermediate = { _, step, _ -> report.generationStatus("聊天图片 · Step $step") }).images.size
            }
            } catch (stopped: ImageBatchStopped) {
                progress.update { values -> values[original.id]?.let { values + (original.id to it.copy(detail = requireNotNull(stopped.message))) } ?: values }
                report.generationStatus(requireNotNull(stopped.message)); throw stopped
            } catch (cancelled: CancellationException) {
                progress.update { values -> values[original.id]?.let { values + (original.id to it.copy(detail = "已停止；已保存图片保留，请核对已提交请求的计费")) } ?: values }
                throw cancelled
            } finally { controls.remove(original.id, runControl) }
        } }
        } catch (failure: Throwable) {
            controls.remove(original.id, control)
            throw failure
        }
    }
    suspend fun translationEnabled() = settings.getAppSettings().novelAiPromptTranslationConsent == NovelAiPromptTranslationConsent.ENABLED
    suspend fun sessionBatchPreferences(message: ChatMessage): ImageBatchPreferences =
        requireNotNull(chats.getSession(message.sessionId)).imageBatchPreferences
    suspend fun saveBatchPreferences(message: ChatMessage, baseline: ImageBatchPreferences, preferences: ImageBatchPreferences) {
        val session = requireNotNull(chats.getSession(message.sessionId))
        require(session.imageBatchPreferences == baseline) { "会话计划已变化，请重新打开" }
        savePreferences(message, session, preferences, session.imagePromptPreference)
    }

    suspend fun previewRegeneration(message: ChatMessage, launch: NovelAiGenerationSettings,
        useSessionPlan: Boolean, allowAnlas: Boolean): String {
        val config = if (useSessionPlan) sessionBatchPreferences(message).configuration(useSavedBatch = true)
            else ImageBatchConfiguration(allowAnlasSpending = allowAnlas)
        config.validationError()?.let { return "重新生成 · $it" }
        val frozen = credential()
        val token = frozen.read()?.takeIf(String::isNotBlank) ?: return "重新生成 · 费用未知"
        val usage = withContext(frozen) { previewAccounts.read(token) {
            accountForBatch?.invoke() ?: accountService.fetchCancellable(token)
        } } ?: return "重新生成 · 费用未知"
        val perBatch = NovelAiImageCostEstimator.estimate(launch.copy(count = config.requestCount), usage).anlas.toLong()
        val total = perBatch * config.numberOfBatches
        return "重新生成 · " + (if (total == 0L) "估算免 Anlas" else "整轮约 $total Anlas") +
            "（每批 $perBatch Anlas · ${config.requestCount} 张 × ${config.numberOfBatches} 批）"
    }
    suspend fun initialSettings(message: ChatMessage, draft: NovelAiImageRegenerationDraft): NovelAiGenerationSettings {
        val session = requireNotNull(chats.getSession(message.sessionId))
        val card = characters.getById(session.characterCardId)
        return NovelAiGenerationSettings(model = NovelAiImageModelResolution.resolve(session.novelAiImageModel,
            card?.defaultNovelAiImageModel, settings.getAppSettings().novelAiImageModel), guidance = 8f,
            customWidth = draft.width, customHeight = draft.height)
            .let { card?.defaultImageGenerationSettings?.applyTo(it) ?: it }
            .copy(customWidth = draft.width, customHeight = draft.height, count = 1)
    }
    suspend fun generate(original: ChatMessage, draft: NovelAiImageRegenerationDraft, launch: NovelAiGenerationSettings,
        useSessionPlan: Boolean = false, allowAnlas: Boolean = false): String {
        require(draft.canRegenerate)
        val session = requireNotNull(chats.getSession(original.sessionId))
        val configuration = if (useSessionPlan) session.imageBatchPreferences.configuration(useSavedBatch = true)
            else ImageBatchConfiguration(allowAnlasSpending = allowAnlas)
        val batchPlan = configuration.freeze(ImageBatchOrigin.CHAT_MANUAL)
        val requestSettings = launch.copy(count = batchPlan.requestCount)
        requestSettings.validationError(draft.characterPrompts.size)?.let { error(it) }
        val plan = draft.toPromptPlan()
        val studio = NovelAiStudioDraft(stylePrompt = draft.stylePrompt, basePrompt = draft.baseCaption,
            negativePrompt = draft.negativePrompt, characters = draft.characterPrompts.map {
                NovelAiCharacterPromptDraft(prompt = it.prompt, negativePrompt = it.negativePrompt)
            }).withActiveSettings(requestSettings)
        suspend fun eligible(): Boolean = chats.readMessageDurable(original.id, original.sessionId) == EntityReadResult.Valid(original) &&
            (chats.readSessionDurable(original.sessionId) as? EntityReadResult.Valid)?.value?.characterCardId == session.characterCardId
        require(eligible())
        val frozen = credential()
        require(!frozen.read().isNullOrBlank())
        val control = ImageBatchControl()
        check(controls.putIfAbsent(original.id, control) == null)
        try { return tasks.launchNovelAi("聊天图片重新生成", original.sessionId, original.id, retryable = batchPlan.targetTotalImages == 1,
            queueIfBusy = true, batchManagedWork = true, deferSourceCommitForRepair = true,
            onFinished = { controls.remove(original.id, control) }) { report ->
            val runControl = controls[original.id]?.takeIf { it === control } ?: ImageBatchControl().also {
                check(controls.putIfAbsent(original.id, it) == null)
            }
            try { withContext(frozen) {
            require(eligible())
            report.generationStatus("正在生成图片")
            val runtime = DesktopNovelAiGenerationRuntime(secrets, persist = { bytes, recipe ->
                report.withSourceCommit {
                    persistDesktopChatImages(chats, resources, coordinator, original, plan, requestSettings.imageSize(), bytes, recipe,
                        ::eligible, session.characterCardId)
                }
            }, client = imageClient ?: secureNovelAiClient(), diagnostics = tasks.diagnostics)
            runDesktopManualImageBatch(batchPlan, runControl, requireNotNull(frozen.read()),
                account = { accountForBatch?.invoke() ?: accountService.fetchCancellable(requireNotNull(frozen.read())) },
                estimate = { count, usage -> NovelAiImageCostEstimator.estimate(requestSettings.copy(count = count), usage) },
                validateOwner = { require(eligible()) },
                onProgress = { update -> progress.update { it + (original.id to runControl.currentProgress(update)) }; report(update.label()) },
                runner = ImageBatchRunner(admission = tasks.imageBatchAdmission)) { count, _ ->
                runtime.generate(studio.withActiveSettings(requestSettings.copy(count = count)), promptPlan = plan,
                    safeBatchRequest = true,
                    onIntermediate = { _, step, _ -> report.generationStatus("重新生成 · Step $step") }).images.size
            }
        } } finally { controls.remove(original.id, runControl) }
        } } catch (failure: Throwable) { controls.remove(original.id, control); throw failure }
    }
}

internal suspend fun persistDesktopChatImages(chats: ChatRepository, resources: DesktopCharacterResourceStore,
    coordinator: DesktopDataOperationCoordinator, original: ChatMessage, plan: NovelAiPromptPlan,
    size: NovelAiImageSize, bytes: List<ByteArray>, recipe: NovelAiGenerationRecipe,
    eligible: suspend () -> Boolean): NovelAiGenerationHistoryEntry =
    persistDesktopChatImages(chats, resources, coordinator, original, plan, size, bytes, recipe, eligible, null)

internal suspend fun persistDesktopChatImages(chats: ChatRepository, resources: DesktopCharacterResourceStore,
    coordinator: DesktopDataOperationCoordinator, original: ChatMessage, plan: NovelAiPromptPlan,
    size: NovelAiImageSize, bytes: List<ByteArray>, recipe: NovelAiGenerationRecipe,
    eligible: suspend () -> Boolean, expectedCharacterCardId: String?): NovelAiGenerationHistoryEntry = coordinator.withExclusiveMaintenance {
    withContext(NonCancellable + Dispatchers.IO) {
        require(eligible()) { "生图来源已更改，未发布结果" }
        val id = UUID.randomUUID().toString()
        val paths = mutableListOf<String>()
        try {
            bytes.forEachIndexed { index, value -> paths += resources.materializeImage(
                PackagedImage("image.png", Base64.getEncoder().encodeToString(value)), System.currentTimeMillis(), "p7chat${id}_$index") }
            chats.addMessageAfter(ChatMessage.create(original.sessionId, MessageRole.ASSISTANT, "",
                images = paths.toList(), generatedImageMetadata = paths.map { plan.toGeneratedImageMetadata(it, size) },
                generatedFromMessageId = original.id).copy(id = id), original.id,
                expectedAnchor = original, expectedCharacterCardId = expectedCharacterCardId)
            NovelAiGenerationHistoryEntry(images = novelAiHistoryImages(paths, recipe.settings.seed), recipe = recipe)
        } catch (failure: Throwable) {
            if (chats.readMessageDurable(id, original.sessionId) == EntityReadResult.Missing) paths.forEach(resources::deleteOwned)
            throw failure
        }
    }
}
