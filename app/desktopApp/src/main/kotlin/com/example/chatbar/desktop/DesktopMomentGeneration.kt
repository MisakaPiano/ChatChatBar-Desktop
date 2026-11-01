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
import kotlinx.coroutines.flow.collect
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
) : DesktopMomentGenerator, DesktopMomentImageGenerator, DesktopMomentDebugGenerator {
    private val imageClientOwner = lazy {
        secureNovelAiClient().newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    }
    private fun core(token: String?, registerImageCleanup: (String) -> Unit) = MomentGenerationCore(text,
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
    override suspend fun generate(card: CharacterCard, session: ChatSession, messages: List<ChatMessage>, latest: MomentPost?,
        settings: AppSettings, task: MomentTask, checkpoint: String, saveCheckpoint: suspend (String) -> Unit,
        progress: (MomentGenerationProgress) -> Unit, registerImageCleanup: (String) -> Unit): MomentGenerationResult = withContext(Dispatchers.IO) {
        val model = models.resolveChatModel(session.modelId, settings)
        check(model != null && model.hasConfiguredAuthentication(settings)) { "未配置可用朋友圈对话模型/API Key" }
        val imageModel = models.resolveImageModel(session.imageModelId, settings)
        val token = secrets.credentialForCall(DesktopCredentialKey.NovelAiToken)
        val core = core(token, registerImageCleanup)
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

    override suspend fun generate(card: CharacterCard, session: ChatSession, messages: List<ChatMessage>, latest: MomentPost?,
        settings: AppSettings, task: MomentTask, registerImageCleanup: (String) -> Unit): MomentDebugGenerationResult = withContext(Dispatchers.IO) {
        val model = models.resolveChatModel(session.modelId, settings)
        check(model != null && model.hasConfiguredAuthentication(settings))
        val imageModel = models.resolveImageModel(session.imageModelId, settings)
        val core = core(secrets.credentialForCall(DesktopCredentialKey.NovelAiToken), registerImageCleanup)
        val player = session.playerName?.takeIf(String::isNotBlank)
            ?: settingsRepository.getPlayerSetting().playerName.takeIf(String::isNotBlank)
        core.debugGenerateNow(card, session, messages, latest, model, imageModel,
            novelAiImageModel = session.resolvedNovelAiImageModel(card.defaultNovelAiImageModel, settings.novelAiImageModel),
            scheduledAt = task.scheduledAt, finalPromptRequirement = settings.imagePromptToolPreference,
            playerName = player, allowCleartextModelApi = settings.allowCleartextModelApi,
            imageAspectRatio = settings.novelAiImageAspectRatio).also { currentCoroutineContext().ensureActive() }
    }

    override suspend fun generate(post: MomentPost, card: CharacterCard?, session: ChatSession?, settings: AppSettings,
        draft: NovelAiImageRegenerationDraft?, registerImageCleanup: (String) -> Unit,
        progress: (MomentGenerationProgress) -> Unit): DesktopMomentImageResult = withContext(Dispatchers.IO) {
        check(!post.isPlaceholder)
        val token = secrets.credentialForCall(DesktopCredentialKey.NovelAiToken)?.takeIf(String::isNotBlank)
            ?: error("未配置NovelAI账户")
        val targetModel = session?.resolvedNovelAiImageModel(characterDefault = card?.defaultNovelAiImageModel,
            globalDefault = settings.novelAiImageModel) ?: card?.defaultNovelAiImageModel ?: settings.novelAiImageModel
        val brief = post.imageBrief.takeIf(String::isNotBlank) ?: post.text
        val plan = if (draft != null) {
            require(draft.canRegenerate); draft.toPromptPlan()
        } else {
            requireNotNull(card) { "原角色卡已删除，无法重新设计图片" }
            val model = models.resolveImageModel(session?.imageModelId, settings)
            check(model != null && model.hasConfiguredAuthentication(settings)) { "未配置可用生图辅助模型" }
            val player = session?.playerName?.takeIf(String::isNotBlank)
                ?: settingsRepository.getPlayerSetting().playerName.takeIf(String::isNotBlank)
            progress(MomentGenerationProgress(MomentGenerationProgressPhase.DESIGNING_IMAGE, "正在设计动态配图"))
            infrastructure().promptDesignerOnIo().designForMoment(card, brief, model,
                finalPromptRequirement = settings.imagePromptToolPreference, playerName = player,
                targetImageModel = targetModel, onDelta = {
                    progress(MomentGenerationProgress(MomentGenerationProgressPhase.DESIGNING_IMAGE, "正在设计动态配图", streamedText = it))
                })
        }
        val size = draft?.imageSize() ?: NovelAiImageSizePolicy.resolve(settings.novelAiImageAspectRatio, NovelAiImageSizePreset.SQUARE)
        val service = NovelAiImageService(imageClientOwner.value, safeErrorsOnly = true)
        val bytes = GlobalImageGenerationConcurrencyGate.instance.run {
            var final: ByteArray? = null
            service.generate(token, plan, size, NovelAiGenerationSettings.legacy(service.newSeed(), model = targetModel)).collect { event ->
                when (event) {
                    is NovelAiImageEvent.Final -> { check(final == null); final = event.image }
                    is NovelAiImageEvent.Error -> error("动态图片请求未完成")
                    is NovelAiImageEvent.Intermediate -> progress(MomentGenerationProgress(
                        MomentGenerationProgressPhase.GENERATING_IMAGE, "正在生成动态配图", progress = event.progress))
                }
            }
            requireNotNull(final) { "未返回最终动态图片" }
        }
        currentCoroutineContext().ensureActive(); DesktopImageEditing.decode(bytes)
        val path = withContext(NonCancellable) {
            resources.materializeImageTracked(PackagedImage("moment.png", Base64.getEncoder().encodeToString(bytes)),
                System.currentTimeMillis(), "moment${UUID.randomUUID()}") { registerImageCleanup(imageCleanup.prepareCandidate(it)) }
        }
        DesktopMomentImageResult(path, plan.toGeneratedImageMetadata(path, size), plan.baseCaption, brief)
    }
    suspend fun closeAndDrain() = withContext(NonCancellable + Dispatchers.IO) {
        if (imageClientOwner.isInitialized()) {
            val client = imageClientOwner.value
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            check(client.dispatcher.executorService.awaitTermination(30, TimeUnit.SECONDS)) { "朋友圈图像连接尚未排水" }
        }
    }
}
