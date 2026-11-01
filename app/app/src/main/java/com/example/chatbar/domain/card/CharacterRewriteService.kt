package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage
import com.example.chatbar.domain.prompt.withAiTaskRun
import com.example.chatbar.domain.prompt.rethrowIfAiTaskTerminalFailure
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.search.CharacterReferenceDocument
import com.example.chatbar.domain.search.CharacterResearchOptions
import com.example.chatbar.domain.search.CharacterResearchService
import com.example.chatbar.domain.search.ResearchBrief
import com.example.chatbar.domain.search.ResearchDebugSnapshot
import com.example.chatbar.domain.search.hasManualUrlSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

data class CharacterRewriteGenerationCheckpoint(
    val research: ResearchDebugSnapshot? = null,
    val rawFinalText: String = ""
)

class CharacterRewriteService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService,
    private val researchService: CharacterResearchService? = null,
    @OptIn(ExperimentalSerializationApi::class)
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
        allowTrailingComma = true
    }
) {
    suspend fun rewriteStreaming(
        userInput: String,
        currentCard: CharacterCard,
        modelOverride: ModelConfig? = null,
        referenceDocuments: List<CharacterReferenceDocument> = emptyList(),
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        resumeFrom: CharacterRewriteGenerationCheckpoint? = null,
        onCheckpoint: (CharacterRewriteGenerationCheckpoint) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> },
        onRawText: (String) -> Unit
    ): CharacterRewriteDraft = withAiTaskRun(Dispatchers.IO) {
        require(userInput.isNotBlank()) { "请输入改写要求" }
        val model = resolveModel(modelOverride)
        var checkpoint = resumeFrom ?: CharacterRewriteGenerationCheckpoint()
        val researchBrief = buildResearchBrief(
            userInput,
            currentCard,
            model,
            referenceDocuments,
            researchOptions,
            onStatus,
            onResearchDebug,
            onVisibleOutput,
            resumeFrom?.research
        ) { snapshot ->
            checkpoint = checkpoint.copy(research = snapshot)
            onCheckpoint(checkpoint)
        }
        onStatus("改写角色卡")
        val messages = listOf(
            ChatApiMessage.text("system", PromptTemplates.CHARACTER_REWRITE_SYSTEM_PROMPT),
            ChatApiMessage.text("user", buildUserPrompt(userInput, currentCard, researchBrief))
        )
        val raw = StringBuilder(checkpoint.rawFinalText)
        val previewThrottle = StreamingTextPreviewThrottle(onRawText)
        var streamError: Throwable? = null
        if (checkpoint.rawFinalText.isBlank()) chatService.streamText(
            taskContext = AiTaskContext(AiTaskKind.CHARACTER_REWRITE, AiTaskStage.GENERATE),
            messages = messages,
            modelConfig = model,
            thinkingBudget = 512,
            readTimeoutSeconds = CHARACTER_CARD_AI_READ_TIMEOUT_SECONDS
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    raw.append(event.text)
                    previewThrottle.publishIfDue(raw)
                }
                is StreamEvent.Error -> streamError = event.asException()
                StreamEvent.Done,
                is StreamEvent.Usage,
                is StreamEvent.ReasoningDelta -> Unit
            }
        }

        val rawText = raw.toString()
        previewThrottle.publishFinal(rawText)
        streamError?.let { throw it }
        if (rawText.isBlank()) error("AI 自动改写返回空内容")
        if (checkpoint.rawFinalText.isBlank()) {
            checkpoint = checkpoint.copy(rawFinalText = rawText)
            onCheckpoint(checkpoint)
        }
        val draft = parseGeneratedDraft(rawText) ?: repairDraft(rawText, model, currentCard)
        materializeDraft(currentCard, draft)
    }

    private suspend fun repairDraft(
        raw: String,
        model: ModelConfig,
        currentCard: CharacterCard
    ): CharacterRewriteDraft {
        val repaired = chatService.completeTextStreaming(
            taskContext = AiTaskContext(AiTaskKind.CHARACTER_REWRITE, AiTaskStage.REPAIR),
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.CHARACTER_REWRITE_REPAIR_PROMPT),
                ChatApiMessage.text(
                    "user",
                    buildJsonObject {
                        put("outputSchema", currentCard.rewriteOutputSchema())
                        put("text", raw)
                    }.toString()
                )
            ),
            modelConfig = model,
            thinkingBudget = 256,
            readTimeoutSeconds = CHARACTER_CARD_AI_READ_TIMEOUT_SECONDS
        )
        return parseGeneratedDraft(repaired)
            ?: error("AI 自动改写结果不是可解析 JSON：${raw.take(500)}")
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
        referenceDocuments: List<CharacterReferenceDocument>,
        researchOptions: CharacterResearchOptions,
        onStatus: (String) -> Unit,
        onResearchDebug: (ResearchDebugSnapshot) -> Unit,
        onVisibleOutput: (String, String, String) -> Unit,
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
        return if (referenceDocuments.isNotEmpty() || researchOptions.hasManualUrlSource()) {
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

    private fun parseGeneratedDraft(raw: String): CharacterRewriteDraft? =
        parseDraft(raw, json)

    fun buildUserPrompt(userInput: String, currentCard: CharacterCard, externalResearch: ResearchBrief? = null): String =
        buildPromptPayload(userInput, currentCard, externalResearch)

    fun mergeInto(
        current: CharacterCard,
        draft: CharacterRewriteDraft,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): CharacterCard = CharacterRewriteService.mergeInto(current, draft, idFactory)

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = false
            allowTrailingComma = true
        }

        fun parseDraft(raw: String, json: Json = defaultJson): CharacterRewriteDraft? =
            CharacterRewritePolicy.parseDraft(raw, json)

        fun buildPromptPayload(
            userInput: String,
            currentCard: CharacterCard,
            externalResearch: ResearchBrief? = null
        ): String =
            buildJsonObject {
                put("request", userInput.trim())
                put("current", currentCard.toRewriteCurrent())
                put("outputSchema", currentCard.rewriteOutputSchema())
                if (currentCard.editMode == CharacterEditMode.STRUCTURED) {
                    put("characterImageGuide", PromptTemplates.CHARACTER_IMAGE_NAI_PROMPT_GUIDE.trim())
                }
                externalResearch?.takeIf(ResearchBrief::hasContent)?.let { brief ->
                    put(
                        "externalResearchUsage",
                        PromptTemplates.CHARACTER_EXTERNAL_RESEARCH_USAGE_PROMPT
                    )
                    put(
                        "externalResearch",
                        defaultJson.parseToJsonElement(defaultJson.encodeToString(ResearchBrief.serializer(), brief))
                    )
                }
            }.toString()

        fun materializeDraft(
            current: CharacterCard,
            draft: CharacterRewriteDraft
        ): CharacterRewriteDraft = CharacterRewritePolicy.materializeDraft(current, draft)

        fun mergeInto(
            current: CharacterCard,
            draft: CharacterRewriteDraft,
            idFactory: () -> String = { UUID.randomUUID().toString() }
        ): CharacterCard = CharacterRewritePolicy.mergeInto(current, draft, idFactory)
    }
}

private fun CharacterCard.toRewriteCurrent(): JsonObject = buildJsonObject {
    putNonBlank("name", name)
    putNonBlank("greeting", greeting)
    putNonBlank("basicSetting", basicSetting)
    putNonBlank("defaultImagePrompt", defaultImagePrompt)
    when (editMode) {
        CharacterEditMode.STRUCTURED -> {
            val visibleCharacters = characters.mapNotNull(CharacterInfo::toRewriteCurrentCharacter)
            if (visibleCharacters.isNotEmpty()) {
                put("characters", buildJsonArray {
                    visibleCharacters.forEach { add(it) }
                })
            }
        }
        CharacterEditMode.FREEFORM -> {
            putNonBlank("freeformCharacterText", freeformCharacterText)
        }
    }
}

private fun CharacterInfo.toRewriteCurrentCharacter(): JsonObject? {
    val hasContent = listOf(
        name,
        profile,
        appearance,
        clothing,
        abilities,
        habits,
        background,
        relationships,
        speakingStyle,
        imagePrompt
    ).any(String::isNotBlank)
    if (!hasContent) return null
    return buildJsonObject {
        put("id", id)
        putNonBlank("name", name)
        putNonBlank("profile", profile)
        putNonBlank("appearance", appearance)
        putNonBlank("clothing", clothing)
        putNonBlank("abilities", abilities)
        putNonBlank("habits", habits)
        putNonBlank("background", background)
        putNonBlank("relationships", relationships)
        putNonBlank("speakingStyle", speakingStyle)
        putNonBlank("imagePrompt", imagePrompt)
    }
}

private fun CharacterCard.rewriteOutputSchema(): JsonObject =
    PromptTemplates.characterRewriteOutputSchema(editMode, cardPatchFields, structuredCharacterPatchFields)

private val cardPatchFields = listOf("name", "greeting", "basicSetting", "defaultImagePrompt")

private val structuredCharacterPatchFields = listOf(
    "name",
    "profile",
    "appearance",
    "clothing",
    "abilities",
    "habits",
    "background",
    "relationships",
    "speakingStyle",
    "imagePrompt"
)

private fun JsonObjectBuilder.putNonBlank(key: String, value: String) {
    if (value.isNotBlank()) put(key, value)
}

private fun jsonStringArray(values: List<String>) = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}
