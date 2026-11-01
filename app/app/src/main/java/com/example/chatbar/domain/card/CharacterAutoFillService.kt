package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ImageUnderstandingResult
import com.example.chatbar.domain.chat.ImageUnderstandingService
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.prompt.withAiTaskRun
import com.example.chatbar.domain.prompt.rethrowIfAiTaskTerminalFailure
import com.example.chatbar.domain.search.CharacterReferenceDocument
import com.example.chatbar.domain.search.CharacterResearchOptions
import com.example.chatbar.domain.search.CharacterResearchService
import com.example.chatbar.domain.search.ResearchBrief
import com.example.chatbar.domain.search.ResearchDebugSnapshot
import com.example.chatbar.domain.search.hasManualUrlSource
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

data class CharacterAutoFillGenerationCheckpoint(
    val imageContext: CharacterAutoFillImageContext? = null,
    val directImageBase64s: List<String> = emptyList(),
    val research: ResearchDebugSnapshot? = null,
    val rawFinalText: String = ""
)

class CharacterAutoFillService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService,
    private val researchService: CharacterResearchService? = null,
    private val imageUnderstandingService: ImageUnderstandingService? = null,
    @OptIn(ExperimentalSerializationApi::class)
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        allowTrailingComma = true
    }
) {
    private val promptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    suspend fun generate(
        userInput: String,
        currentCard: CharacterCard,
        modelOverride: ModelConfig? = null,
        imageBase64s: List<String> = emptyList(),
        referenceDocuments: List<CharacterReferenceDocument> = emptyList(),
        researchOptions: CharacterResearchOptions = CharacterResearchOptions()
    ): CharacterAutoFillDraft = withAiTaskRun(Dispatchers.IO) {
        require(currentCard.editMode == CharacterEditMode.STRUCTURED) { "AI 自动填充仅支持分段模式" }
        require(
            userInput.isNotBlank() ||
                imageBase64s.any(String::isNotBlank) ||
                referenceDocuments.any { it.content.isNotBlank() } ||
                researchOptions.hasManualUrlSource()
        ) { "请输入角色信息，或提供参考图片、文档、网址" }
        val model = resolveModel(modelOverride)
        val imageContext = prepareImageContext(imageBase64s, model)
        val researchBrief = if (
            userInput.isNotBlank() ||
            referenceDocuments.isNotEmpty() ||
            researchOptions.hasManualUrlSource()
        ) {
            buildResearchBrief(
                userInput,
                currentCard,
                model,
                referenceDocuments = referenceDocuments,
                researchOptions = researchOptions
            )
        } else {
            null
        }
        val userPrompt = buildUserPrompt(userInput, currentCard, researchBrief, imageContext.promptContext)

        characterCardCandidateGenerator(chatService).fill(currentCard, userPrompt, model,
            directImages = imageContext.directImageBase64s, json = json)
    }

    suspend fun generateStreaming(
        userInput: String,
        currentCard: CharacterCard,
        modelOverride: ModelConfig? = null,
        imageBase64s: List<String> = emptyList(),
        referenceDocuments: List<CharacterReferenceDocument> = emptyList(),
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        resumeFrom: CharacterAutoFillGenerationCheckpoint? = null,
        onCheckpoint: (CharacterAutoFillGenerationCheckpoint) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> },
        onRawText: (String) -> Unit
    ): CharacterAutoFillDraft = withAiTaskRun(Dispatchers.IO) {
        require(currentCard.editMode == CharacterEditMode.STRUCTURED) { "AI 自动填充仅支持分段模式" }
        require(
            userInput.isNotBlank() ||
                imageBase64s.any(String::isNotBlank) ||
                referenceDocuments.any { it.content.isNotBlank() } ||
                researchOptions.hasManualUrlSource()
        ) { "请输入角色信息，或提供参考图片、文档、网址" }
        val model = resolveModel(modelOverride)
        var checkpoint = resumeFrom ?: CharacterAutoFillGenerationCheckpoint()
        val imageContext = checkpoint.imageContext?.let {
            PreparedAutoFillImageContext(it, checkpoint.directImageBase64s)
        } ?: prepareImageContext(imageBase64s, model, onStatus, onVisibleOutput).also { prepared ->
            checkpoint = checkpoint.copy(
                imageContext = prepared.promptContext,
                directImageBase64s = prepared.directImageBase64s
            )
            onCheckpoint(checkpoint)
        }
        val researchBrief = if (
            userInput.isNotBlank() ||
            referenceDocuments.isNotEmpty() ||
            researchOptions.hasManualUrlSource()
        ) {
            buildResearchBrief(
                userInput,
                currentCard,
                model,
                referenceDocuments,
                researchOptions,
                onStatus,
                onResearchDebug,
                onVisibleOutput,
                checkpoint.research
            ) { snapshot ->
                checkpoint = checkpoint.copy(research = snapshot)
                onCheckpoint(checkpoint)
            }
        } else {
            null
        }
        onStatus("生成角色卡")

        val userPrompt = buildUserPrompt(userInput, currentCard, researchBrief, imageContext.promptContext)
        characterCardCandidateGenerator(chatService).fill(currentCard, userPrompt, model,
            directImages = imageContext.directImageBase64s, completedRaw = checkpoint.rawFinalText, json = json,
            onCheckpoint = { raw -> checkpoint = checkpoint.copy(rawFinalText = raw); onCheckpoint(checkpoint) },
            onStatus = onStatus, onRaw = onRawText)
    }

    private suspend fun resolveModel(modelOverride: ModelConfig?): ModelConfig {
        val model = modelOverride ?: modelResolver.defaultChatModel()
            ?: error("未配置可用的默认对话模型")
        require(model.apiKey.isNotBlank()) { "${model.displayName.ifBlank { model.modelName }} API Key 为空" }
        return model
    }

    private suspend fun buildResearchBrief(
        userInput: String,
        currentCard: CharacterCard,
        generationModel: ModelConfig,
        referenceDocuments: List<CharacterReferenceDocument> = emptyList(),
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        onStatus: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> },
        resumeFrom: ResearchDebugSnapshot? = null,
        onCheckpoint: (ResearchDebugSnapshot) -> Unit = {}
    ): ResearchBrief? {
        val service = researchService ?: if (
            referenceDocuments.isNotEmpty() || researchOptions.hasManualUrlSource()
        ) {
            error("外部资料研究服务不可用")
        } else {
            return null
        }
        val research: suspend () -> ResearchBrief? = {
            service.research(
                userInput = userInput,
                currentCard = currentCard,
                modelConfig = generationModel,
                researchOptions = researchOptions,
                referenceDocuments = referenceDocuments,
                onDebug = onResearchDebug,
                resumeFrom = resumeFrom,
                onCheckpoint = onCheckpoint,
                onStatus = onStatus,
                onVisibleOutput = onVisibleOutput
            )
        }
        return if (
            referenceDocuments.isNotEmpty() ||
            researchOptions.hasManualUrlSource()
        ) {
            research()
        } else {
            try {
                research()
            } catch (error: Throwable) {
                error.rethrowIfAiTaskTerminalFailure()
                onStatus("外部资料研究失败，继续使用角色卡现有资料：${error.message ?: error::class.java.simpleName}")
                null
            }
        }
    }

    private suspend fun prepareImageContext(
        imageBase64s: List<String>,
        model: ModelConfig,
        onStatus: suspend (String) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> }
    ): PreparedAutoFillImageContext {
        val images = imageBase64s.filter(String::isNotBlank)
        if (images.isEmpty()) return PreparedAutoFillImageContext()
        val result = imageUnderstandingService?.prepare(
            imageBase64s = images,
            generationModel = model,
            requireUnderstanding = true,
            announceDirect = true,
            onStatus = onStatus,
            onDescriptionText = { index, text ->
                onVisibleOutput("image-description-$index", "图片识别输出", text)
            }
        ) ?: if (model.isMultimodal) {
            ImageUnderstandingResult(directImageBase64s = images)
        } else {
            error("当前模型不支持多模态，且未配置可用的视觉模型，无法基于图片生成")
        }
        return PreparedAutoFillImageContext(
            promptContext = CharacterAutoFillImageContext(
                hasSourceImages = result.hasSourceImages,
                descriptions = result.descriptions
            ),
            directImageBase64s = result.directImageBase64s
        )
    }

    companion object {
        private val defaultJson = CharacterAutoFillPolicy.defaultJson
        private val defaultPromptJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        fun parseDraft(raw: String, json: Json = defaultJson): CharacterAutoFillDraft? =
            CharacterAutoFillPolicy.parseDraft(raw, json)

        fun mergeInto(
            current: CharacterCard, draft: CharacterAutoFillDraft,
            idFactory: () -> String = { UUID.randomUUID().toString() }
        ): CharacterCard = CharacterCardAiPayload.mergeFill(current, draft, idFactory)

        fun mergeCharacters(
            current: List<CharacterInfo>,
            draft: List<CharacterAutoFillCharacterDraft>,
            idFactory: () -> String = { UUID.randomUUID().toString() }
        ): List<CharacterInfo> = CharacterAutoFillPolicy.mergeCharacters(current, draft, idFactory)

        fun buildPromptPayload(
            userInput: String,
            currentCard: CharacterCard,
            externalResearch: ResearchBrief? = null,
            imageContext: CharacterAutoFillImageContext? = null,
            promptJson: Json = defaultPromptJson
        ): String = CharacterCardAiPayload.fill(userInput, currentCard,
            externalResearch?.takeIf(ResearchBrief::hasContent)?.let {
                promptJson.parseToJsonElement(promptJson.encodeToString(ResearchBrief.serializer(), it))
            }, imageContext, promptJson)
    }

    fun mergeInto(
        current: CharacterCard,
        draft: CharacterAutoFillDraft,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): CharacterCard = CharacterAutoFillService.mergeInto(current, draft, idFactory)

    fun buildUserPrompt(
        userInput: String,
        currentCard: CharacterCard,
        externalResearch: ResearchBrief? = null,
        imageContext: CharacterAutoFillImageContext? = null
    ): String =
        CharacterAutoFillService.buildPromptPayload(userInput, currentCard, externalResearch, imageContext, promptJson)
}

private data class PreparedAutoFillImageContext(
    val promptContext: CharacterAutoFillImageContext = CharacterAutoFillImageContext(),
    val directImageBase64s: List<String> = emptyList()
)
