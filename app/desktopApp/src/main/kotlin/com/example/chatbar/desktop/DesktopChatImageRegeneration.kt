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
    fun pauseBatch(messageId: String) {
        controls[messageId]?.pause()
        progress.update { values -> values[messageId]?.let { values + (messageId to it.copy(detail = "已请求暂停；当前批次完成后生效")) } ?: values }
    }
    fun resumeBatch(messageId: String) {
        controls[messageId]?.resume()
        progress.update { values -> values[messageId]?.let { values + (messageId to it.copy(detail = "已请求继续；仍遵守剩余等待")) } ?: values }
    }
    fun batchPaused(messageId: String): Boolean = controls[messageId]?.isPaused == true
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
    /** Explicit Assistant-message action mirrors ChatViewModel.generateNovelAiImage; no eligibility AI judge. */
    suspend fun generateFromAssistant(original: ChatMessage, requirements: DesktopChatImageRequirements? = null): String {
        requirePreflight(original)
        val openingSession = requireNotNull(chats.getSession(original.sessionId))
        val batchPlan = requirements?.batchPlan
        if (batchPlan != null) require(batchPlan.origin == ImageBatchOrigin.CHAT_MANUAL)
        suspend fun eligible() = chats.readMessageDurable(original.id, original.sessionId) == EntityReadResult.Valid(original) &&
            (chats.readSessionDurable(original.sessionId) as? EntityReadResult.Valid)?.value?.characterCardId == openingSession.characterCardId
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
        val control = batchPlan?.let { ImageBatchControl() }
        if (control != null) {
            check(controls.putIfAbsent(original.id, control) == null)
            progress.update { it + (original.id to ImageBatchProgress(target = requireNotNull(batchPlan).targetTotalImages,
                batches = batchPlan.batches.size, detail = "正在准备本次图片；尚未发出生图请求")) }
        }
        try {
        return tasks.launchNovelAi("聊天图片", original.sessionId, original.id, retryable = batchPlan == null, queueIfBusy = true,
            batchManagedWork = batchPlan != null, onFinished = { if (control != null) controls.remove(original.id, control) }) { report -> withContext(frozen) {
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
                persistDesktopChatImages(chats, resources, coordinator, original, plan, size, bytes, recipe, ::eligible,
                    expectedCharacterCardId = openingSession.characterCardId)
            }, client = imageClient ?: secureNovelAiClient(), diagnostics = tasks.diagnostics)
            if (batchPlan == null) ImageBatchRunner.ordinaryStudioRequest {
                runtime.generate(draft, promptPlan = plan, maxRateLimitRetries = 10,
                    onIntermediate = { _, step, _ -> report.generationStatus("聊天图片 · Step $step") })
            } else runDesktopManualImageBatch(batchPlan, requireNotNull(control), requireNotNull(frozen.read()),
                runner = ImageBatchRunner(admission = tasks.imageBatchAdmission),
                account = { accountForBatch?.invoke() ?: accountService.fetchCancellable(requireNotNull(frozen.read())) },
                estimate = { count, usage -> NovelAiImageCostEstimator.estimate(launch.copy(count = count), usage) },
                validateOwner = { require(eligible()) { "图片来源或角色卡绑定已变化，批次停止" } },
                onProgress = { update ->
                    progress.update { (it + (original.id to update)).entries.toList().takeLast(128).associate { entry -> entry.toPair() } }
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
            } finally { if (control != null) controls.remove(original.id, control) }
        } }
        } catch (failure: Throwable) {
            if (control != null) controls.remove(original.id, control)
            throw failure
        }
    }
    suspend fun translationEnabled() = settings.getAppSettings().novelAiPromptTranslationConsent == NovelAiPromptTranslationConsent.ENABLED
    suspend fun initialSettings(message: ChatMessage, draft: NovelAiImageRegenerationDraft): NovelAiGenerationSettings {
        val session = requireNotNull(chats.getSession(message.sessionId))
        val card = characters.getById(session.characterCardId)
        return NovelAiGenerationSettings(model = NovelAiImageModelResolution.resolve(session.novelAiImageModel,
            card?.defaultNovelAiImageModel, settings.getAppSettings().novelAiImageModel), guidance = 8f,
            customWidth = draft.width, customHeight = draft.height)
            .let { card?.defaultImageGenerationSettings?.applyTo(it) ?: it }
    }
    suspend fun generate(original: ChatMessage, draft: NovelAiImageRegenerationDraft, launch: NovelAiGenerationSettings): String {
        require(draft.canRegenerate)
        launch.validationError(draft.characterPrompts.size)?.let { error(it) }
        val plan = draft.toPromptPlan()
        val studio = NovelAiStudioDraft(stylePrompt = draft.stylePrompt, basePrompt = draft.baseCaption,
            negativePrompt = draft.negativePrompt, characters = draft.characterPrompts.map {
                NovelAiCharacterPromptDraft(prompt = it.prompt, negativePrompt = it.negativePrompt)
            }).withActiveSettings(launch)
        suspend fun eligible(): Boolean = chats.readMessageDurable(original.id, original.sessionId) == EntityReadResult.Valid(original) &&
            chats.readSessionDurable(original.sessionId) is EntityReadResult.Valid
        require(eligible())
        val frozen = credential()
        require(!frozen.read().isNullOrBlank())
        return tasks.launchNovelAi("聊天图片重新生成", original.sessionId, original.id, retryable = true, queueIfBusy = true) { report -> withContext(frozen) {
            require(eligible())
            report.generationStatus("正在生成图片")
            DesktopNovelAiGenerationRuntime(secrets, persist = { bytes, recipe ->
                persistDesktopChatImages(chats, resources, coordinator, original, plan, launch.imageSize(), bytes, recipe, ::eligible)
            }, client = imageClient ?: secureNovelAiClient(), diagnostics = tasks.diagnostics).generate(studio, promptPlan = plan, maxRateLimitRetries = 10,
                onIntermediate = { _, step, _ -> report.generationStatus("重新生成 · Step $step") })
        } }
    }
}

internal suspend fun persistDesktopChatImages(chats: ChatRepository, resources: DesktopCharacterResourceStore,
    coordinator: DesktopDataOperationCoordinator, original: ChatMessage, plan: NovelAiPromptPlan,
    size: NovelAiImageSize, bytes: List<ByteArray>, recipe: NovelAiGenerationRecipe,
    eligible: suspend () -> Boolean, expectedCharacterCardId: String? = null): NovelAiGenerationHistoryEntry = coordinator.withExclusiveMaintenance {
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
