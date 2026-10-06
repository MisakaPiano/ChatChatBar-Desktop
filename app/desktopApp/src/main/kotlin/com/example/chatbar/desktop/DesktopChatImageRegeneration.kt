package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.image.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*

/** Formal chat regeneration creates another linked image message; it does not mutate its source. */
internal class DesktopChatImageRegeneration(
    private val chats: ChatRepository, private val characters: CharacterRepository,
    private val settings: SettingsRepository, private val resources: DesktopCharacterResourceStore,
    private val coordinator: DesktopDataOperationCoordinator, private val secrets: DesktopSecretStore,
    private val tasks: DesktopTaskRuntime, val infrastructure: DesktopNovelAiInfrastructure,
) {
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
        return tasks.launchNovelAi("聊天图片重新生成", original.sessionId) { report ->
            require(eligible())
            DesktopNovelAiGenerationRuntime(secrets, persist = { bytes, recipe ->
                persistDesktopChatImages(chats, resources, coordinator, original, plan, launch.imageSize(), bytes, recipe, ::eligible)
            }).generate(studio, promptPlan = plan, maxRateLimitRetries = 10,
                onIntermediate = { _, step, _ -> report("重新生成 · Step $step") })
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
