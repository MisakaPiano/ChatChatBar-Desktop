package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.security.NovelAiCredentialStore
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/** Android retains its task-run boundary and platform-owned image/credential instances. */
class MomentGenerationService(
    chatService: StreamingChatService,
    promptDesigner: NovelAiPromptDesigner,
    imageService: NovelAiImageService,
    imageStorage: NovelAiImageStorage,
    novelAiCredentials: NovelAiCredentialStore,
    json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    compiledMemoryProvider: suspend (ChatSession) -> String = { it.longTermMemory }
) {
    private val core = MomentGenerationCore(AndroidMomentTextGateway(chatService), { promptDesigner }, { imageService },
        { prefix, bytes -> imageStorage.save(prefix, bytes) }, novelAiCredentials::load, json, compiledMemoryProvider)
    suspend fun generate(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        latestPost: MomentPost?,
        model: ModelConfig,
        imageModel: ModelConfig?,
        novelAiImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        scheduledAt: Long,
        finalPromptRequirement: String = "",
        playerName: String? = session.playerName,
        allowCleartextModelApi: Boolean = false,
        autoGenerateImages: Boolean = true,
        imageAspectRatio: String = "",
        resumeFrom: MomentGenerationCheckpoint? = null,
        onCheckpoint: suspend (MomentGenerationCheckpoint) -> Unit = {}
    ): MomentGenerationResult = withAiTaskRun {
        core.generate(
            card = card,
            session = session,
            messages = messages,
            latestPost = latestPost,
            model = model,
            imageModel = imageModel,
            novelAiImageModel = novelAiImageModel,
            scheduledAt = scheduledAt,
            finalPromptRequirement = finalPromptRequirement,
            playerName = playerName,
            allowCleartextModelApi = allowCleartextModelApi,
            autoGenerateImages = autoGenerateImages,
            imageAspectRatio = imageAspectRatio,
            resumeFrom = resumeFrom,
            onCheckpoint = onCheckpoint
        )
    }

    suspend fun generateStreaming(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        latestPost: MomentPost?,
        model: ModelConfig,
        imageModel: ModelConfig?,
        novelAiImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        scheduledAt: Long,
        finalPromptRequirement: String = "",
        playerName: String? = session.playerName,
        allowCleartextModelApi: Boolean = false,
        autoGenerateImages: Boolean = true,
        imageAspectRatio: String = "",
        resumeFrom: MomentGenerationCheckpoint? = null,
        onCheckpoint: suspend (MomentGenerationCheckpoint) -> Unit = {},
        onProgress: (MomentGenerationProgress) -> Unit
    ): MomentGenerationResult = withAiTaskRun {
        core.generateStreaming(
            card = card,
            session = session,
            messages = messages,
            latestPost = latestPost,
            model = model,
            imageModel = imageModel,
            novelAiImageModel = novelAiImageModel,
            scheduledAt = scheduledAt,
            finalPromptRequirement = finalPromptRequirement,
            playerName = playerName,
            allowCleartextModelApi = allowCleartextModelApi,
            autoGenerateImages = autoGenerateImages,
            imageAspectRatio = imageAspectRatio,
            resumeFrom = resumeFrom,
            onCheckpoint = onCheckpoint,
            onProgress = onProgress
        )
    }

    suspend fun debugGenerateNow(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        latestPost: MomentPost?,
        model: ModelConfig,
        imageModel: ModelConfig?,
        novelAiImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        scheduledAt: Long = System.currentTimeMillis(),
        finalPromptRequirement: String = "",
        playerName: String? = session.playerName,
        allowCleartextModelApi: Boolean = false,
        imageAspectRatio: String = ""
    ): MomentDebugGenerationResult = withAiTaskRun {
        core.debugGenerateNow(
            card = card,
            session = session,
            messages = messages,
            latestPost = latestPost,
            model = model,
            imageModel = imageModel,
            novelAiImageModel = novelAiImageModel,
            scheduledAt = scheduledAt,
            finalPromptRequirement = finalPromptRequirement,
            playerName = playerName,
            allowCleartextModelApi = allowCleartextModelApi,
            imageAspectRatio = imageAspectRatio
        )
    }

    fun encodeCheckpoint(checkpoint: MomentGenerationCheckpoint): String = core.encodeCheckpoint(checkpoint)
    fun decodeCheckpoint(value: String): MomentGenerationCheckpoint? = core.decodeCheckpoint(value)
}

internal class AndroidMomentTextGateway(private val chat: StreamingChatService) : MomentTextGateway {
    private fun context(operation: MomentTextOperation): AiTaskContext = when (operation) {
        MomentTextOperation.JUDGE -> AiTaskContext(AiTaskKind.MOMENT_JUDGE, AiTaskStage.JUDGE)
        MomentTextOperation.GENERATE -> AiTaskContext(AiTaskKind.MOMENT_GENERATION)
    }
    override suspend fun completeText(messages: List<ChatApiMessage>, modelConfig: ModelConfig, operation: MomentTextOperation): String =
        chat.completeText(messages = messages, modelConfig = modelConfig, taskContext = context(operation))
    override fun streamText(messages: List<ChatApiMessage>, modelConfig: ModelConfig, operation: MomentTextOperation): Flow<StreamEvent> =
        chat.streamText(messages = messages, modelConfig = modelConfig, taskContext = context(operation))
}
