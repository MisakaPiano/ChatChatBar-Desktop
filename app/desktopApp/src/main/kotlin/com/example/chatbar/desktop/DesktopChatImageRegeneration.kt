package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.desktop.security.DesktopCredentialKey
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class DesktopChatImageRequirements(val imageContentHint: String = "", val imagePromptPreference: String,
    val persistPreference: Boolean = true)

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
) {
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
        suspend fun eligible() = chats.readMessageDurable(original.id, original.sessionId) == EntityReadResult.Valid(original)
        require(eligible())
        val openingSession = requireNotNull(chats.getSession(original.sessionId))
        val preference = requirements?.imagePromptPreference ?: openingSession.imagePromptPreference
        if (requirements?.persistPreference == true) chats.saveSessionSettingsDraft(openingSession,
            openingSession.copy(imagePromptPreference = preference))
        return tasks.launchNovelAi("聊天图片", original.sessionId, original.id, retryable = true) { report ->
            requirePreflight(original)
            require(eligible())
            val session = requireNotNull(chats.getSession(original.sessionId))
            val card = requireNotNull(characters.getById(session.characterCardId))
            val app = settings.getAppSettings()
            val model = requireNotNull(resolver?.resolveImageModel(session.imageModelId, app))
            val target = NovelAiImageModelResolution.resolve(session.novelAiImageModel, card.defaultNovelAiImageModel, app.novelAiImageModel)
            val plan = infrastructure.promptDesigner().design(chats.getInitialMessagePage(original.sessionId, original.id).messages,
                original.id, card, model, playerName = session.playerName?.takeIf(String::isNotBlank) ?: settings.getPlayerSetting().playerName,
                sessionId = session.id, imageContentHint = requirements?.imageContentHint.orEmpty(),
                finalPromptRequirement = preference, targetImageModel = target,
                naturalLanguageMode = session.novelAiNaturalLanguageMode && target == NovelAiImageModel.V5_FULL,
                onDelta = { report.designSnapshot(it, model.apiKey) })
            currentCoroutineContext().ensureActive()
            require(eligible())
            val size = NovelAiImageSizePolicy.resolve(app.novelAiImageAspectRatio, plan.sizePreset)
            val edit = plan.toRegenerationDraft()
            val launch = NovelAiGenerationSettings(model = target, customWidth = size.width, customHeight = size.height, guidance = 8f, count = 1)
            val draft = NovelAiStudioDraft(stylePrompt = edit.stylePrompt, basePrompt = edit.baseCaption, negativePrompt = edit.negativePrompt,
                characters = edit.characterPrompts.map { NovelAiCharacterPromptDraft(prompt = it.prompt, negativePrompt = it.negativePrompt) }).withActiveSettings(launch)
            report.generationStatus("正在生成图片")
            DesktopNovelAiGenerationRuntime(secrets, persist = { bytes, recipe ->
                persistDesktopChatImages(chats, resources, coordinator, original, plan, size, bytes, recipe, ::eligible)
            }, client = imageClient ?: secureNovelAiClient()).generate(draft, promptPlan = plan, maxRateLimitRetries = 10, onIntermediate = { _, step, _ -> report.generationStatus("聊天图片 · Step $step") })
        }
    }
    suspend fun translationEnabled() = settings.getAppSettings().novelAiPromptTranslationConsent == NovelAiPromptTranslationConsent.ENABLED
    suspend fun initialSettings(message: ChatMessage, draft: NovelAiImageRegenerationDraft): NovelAiGenerationSettings {
        val session = requireNotNull(chats.getSession(message.sessionId))
        val card = characters.getById(session.characterCardId)
        return NovelAiGenerationSettings(model = NovelAiImageModelResolution.resolve(session.novelAiImageModel,
            card?.defaultNovelAiImageModel, settings.getAppSettings().novelAiImageModel), guidance = 8f,
            customWidth = draft.width, customHeight = draft.height)
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
        return tasks.launchNovelAi("聊天图片重新生成", original.sessionId, original.id, retryable = true) { report ->
            require(eligible())
            report.generationStatus("正在生成图片")
            DesktopNovelAiGenerationRuntime(secrets, persist = { bytes, recipe ->
                persistDesktopChatImages(chats, resources, coordinator, original, plan, launch.imageSize(), bytes, recipe, ::eligible)
            }, client = imageClient ?: secureNovelAiClient()).generate(studio, promptPlan = plan, maxRateLimitRetries = 10,
                onIntermediate = { _, step, _ -> report.generationStatus("重新生成 · Step $step") })
        }
    }
}

internal suspend fun persistDesktopChatImages(chats: ChatRepository, resources: DesktopCharacterResourceStore,
    coordinator: DesktopDataOperationCoordinator, original: ChatMessage, plan: NovelAiPromptPlan,
    size: NovelAiImageSize, bytes: List<ByteArray>, recipe: NovelAiGenerationRecipe,
    eligible: suspend () -> Boolean): NovelAiGenerationHistoryEntry = coordinator.withExclusiveMaintenance {
    withContext(NonCancellable + Dispatchers.IO) {
        require(eligible()) { "生图来源已更改，未发布结果" }
        val id = UUID.randomUUID().toString()
        val paths = mutableListOf<String>()
        try {
            bytes.forEachIndexed { index, value -> paths += resources.materializeImage(
                PackagedImage("image.png", Base64.getEncoder().encodeToString(value)), System.currentTimeMillis(), "p7chat${id}_$index") }
            chats.addMessageAfter(ChatMessage.create(original.sessionId, MessageRole.ASSISTANT, "",
                images = paths.toList(), generatedImageMetadata = paths.map { plan.toGeneratedImageMetadata(it, size) },
                generatedFromMessageId = original.id).copy(id = id), original.id)
            NovelAiGenerationHistoryEntry(images = novelAiHistoryImages(paths, recipe.settings.seed), recipe = recipe)
        } catch (failure: Throwable) {
            if (chats.readMessageDurable(id, original.sessionId) == EntityReadResult.Missing) paths.forEach(resources::deleteOwned)
            throw failure
        }
    }
}
