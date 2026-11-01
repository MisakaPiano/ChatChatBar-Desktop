package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.moment.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import kotlinx.coroutines.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Original shared core, with lazily opened image owners and compiled memory from the existing authority. */
internal class DesktopMomentGeneration(
    private val text: DesktopMomentTextGateway,
    private val models: EffectiveModelResolver,
    private val settingsRepository: SettingsRepository,
    private val secrets: DesktopSecretStore,
    private val infrastructure: () -> DesktopNovelAiInfrastructure,
    private val resources: DesktopCharacterResourceStore,
    private val imageCleanup: DesktopMomentImageCleanup,
    private val compiledMemory: suspend (ChatSession) -> String,
) : DesktopMomentGenerator {
    private val imageClientOwner = lazy {
        secureNovelAiClient().newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    }
    override suspend fun generate(card: CharacterCard, session: ChatSession, messages: List<ChatMessage>, latest: MomentPost?,
        settings: AppSettings, task: MomentTask, checkpoint: String, saveCheckpoint: suspend (String) -> Unit,
        progress: (MomentGenerationProgress) -> Unit, registerImageCleanup: (String) -> Unit): MomentGenerationResult = withContext(Dispatchers.IO) {
        val model = models.resolveChatModel(session.modelId, settings)
        check(model != null && model.hasConfiguredAuthentication(settings)) { "未配置可用朋友圈对话模型/API Key" }
        val imageModel = models.resolveImageModel(session.imageModelId, settings)
        val token = secrets.credentialForCall(DesktopCredentialKey.NovelAiToken)
        val core = MomentGenerationCore(text,
            { infrastructure().promptDesignerOnIo() },
            { NovelAiImageService(imageClientOwner.value, safeErrorsOnly = true) },
            { _, bytes ->
                DesktopImageEditing.decode(bytes)
                withContext(NonCancellable) {
                    resources.materializeImageTracked(PackagedImage("moment.png", Base64.getEncoder().encodeToString(bytes)),
                        System.currentTimeMillis(), "moment${UUID.randomUUID()}") { reference ->
                        registerImageCleanup(imageCleanup.prepareCandidate(reference))
                    }
                }
            }, { token }, compiledMemoryProvider = compiledMemory)
        val resume = if (checkpoint.isBlank()) null else requireNotNull(core.decodeCheckpoint(checkpoint)) {
            "已保存检查点不可读取；保留原动态，不重新执行已完成阶段"
        }
        val player = session.playerName?.takeIf(String::isNotBlank)
            ?: settingsRepository.getPlayerSetting().playerName.takeIf(String::isNotBlank)
        core.generateStreaming(card, session, messages, latest, model, imageModel,
            novelAiImageModel = session.resolvedNovelAiImageModel(characterDefault = card.defaultNovelAiImageModel,
                globalDefault = settings.novelAiImageModel), scheduledAt = task.scheduledAt,
            finalPromptRequirement = settings.imagePromptToolPreference, playerName = player,
            allowCleartextModelApi = settings.allowCleartextModelApi, autoGenerateImages = settings.momentsImagesEnabled,
            imageAspectRatio = settings.novelAiImageAspectRatio, resumeFrom = resume,
            onCheckpoint = { saveCheckpoint(core.encodeCheckpoint(it)) }, onProgress = progress)
    }
    suspend fun closeAndDrain() = withContext(NonCancellable + Dispatchers.IO) {
        if (imageClientOwner.isInitialized()) {
            val client = imageClientOwner.value
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            check(client.dispatcher.executorService.awaitTermination(30, TimeUnit.SECONDS)) { "朋友圈图像连接尚未排水" }
        }
    }
}
