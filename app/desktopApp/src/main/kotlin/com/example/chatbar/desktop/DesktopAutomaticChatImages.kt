package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*

/** No AI eligibility judge. Only explicit opt-in and persisted provider completion can hand off. */
internal class DesktopAutomaticChatImages(
    private val chats: ChatRepository,
    private val characters: CharacterRepository,
    private val settings: SettingsRepository,
    private val resolver: EffectiveModelResolver,
    private val designer: suspend () -> NovelAiPromptDesigner,
    private val resources: DesktopCharacterResourceStore,
    private val gate: DesktopDataOperationCoordinator,
    private val secrets: DesktopSecretStore,
    private val launch: (String, suspend ((String) -> Unit) -> Unit) -> String,
) {
    suspend fun completed(result: DesktopRealChatResult, stopped: () -> Boolean): String? {
        val original = result.assistant
        val session = chats.getSession(original.sessionId) ?: return null
        if (!session.automaticImageGenerationEnabled) return null
        val evidence = result.completion?.let { ChatReplyCompletion(it.finishReason, it.refused,
            it.transportFailed || result.failureMessage != null) }
        val reason = AutomaticChatImagePolicy.skipReason(evidence, original.content)
            ?: AutomaticChatImagePolicy.skipReason(evidence, original.displayContent)
            ?: if (stopped()) "回复已停止" else null
        if (reason != null) return "自动生图已跳过：$reason"
        if (!eligible(original, stopped)) return "自动生图已跳过：消息或设置已变化"
        return try {
            launch(original.sessionId) { report ->
                require(eligible(original, stopped))
                val app = settings.getAppSettings()
                val current = requireNotNull(chats.getSession(original.sessionId))
                val card = requireNotNull(characters.getById(current.characterCardId))
                val model = requireNotNull(resolver.resolveImageModel(current.imageModelId, app))
                val target = NovelAiImageModelResolution.resolve(current.novelAiImageModel, card.defaultNovelAiImageModel, app.novelAiImageModel)
                val plan = designer().design(chats.getMessages(original.sessionId), original.id, card, model,
                    playerName = current.playerName ?: settings.getPlayerSetting().playerName,
                    sessionId = current.id, finalPromptRequirement = current.imagePromptPreference,
                    targetImageModel = target, naturalLanguageMode = current.novelAiNaturalLanguageMode && target == NovelAiImageModel.V5_FULL,
                    onDelta = { report("自动生图 · Prompt Designer") })
                currentCoroutineContext().ensureActive()
                require(eligible(original, stopped))
                val size = NovelAiImageSizePolicy.resolve(app.novelAiImageAspectRatio, plan.sizePreset)
                val edit = plan.toRegenerationDraft()
                val draft = NovelAiStudioDraft(stylePrompt = edit.stylePrompt, basePrompt = edit.baseCaption,
                    characters = edit.characterPrompts.map { NovelAiCharacterPromptDraft(prompt = it.prompt, negativePrompt = it.negativePrompt) },
                    negativePrompt = edit.negativePrompt).withActiveSettings(NovelAiGenerationSettings(model = target,
                        customWidth = size.width, customHeight = size.height, guidance = 8f, count = 1))
                val runtime = DesktopNovelAiGenerationRuntime(secrets, persist = { bytes, recipe ->
                    persistDesktopChatImages(chats, resources, gate, original, plan, size, bytes, recipe) { eligible(original, stopped) }
                })
                runtime.generate(draft, maxRateLimitRetries = 10, onIntermediate = { _, step, _ -> report("自动生图 · Step $step") })
                report("自动图片已保存")
            }
            "自动生图已启动"
        } catch (_: DesktopTaskAdmissionException) { "自动生图已跳过：已有图像任务运行" }
    }

    private suspend fun eligible(original: ChatMessage, stopped: () -> Boolean): Boolean {
        if (stopped() || original.role != MessageRole.ASSISTANT) return false
        val session = chats.readSessionDurable(original.sessionId) as? EntityReadResult.Valid ?: return false
        if (!session.value.automaticImageGenerationEnabled) return false
        val latest = chats.readMessageDurable(original.id, original.sessionId) as? EntityReadResult.Valid ?: return false
        return latest.value == original
    }
}
