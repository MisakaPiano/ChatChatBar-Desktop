package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
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
import kotlinx.serialization.json.Json
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
        characterCardCandidateGenerator(chatService).rewrite(currentCard,
            buildUserPrompt(userInput, currentCard, researchBrief), model,
            completedRaw = checkpoint.rawFinalText, json = json,
            onCheckpoint = { raw -> checkpoint = checkpoint.copy(rawFinalText = raw); onCheckpoint(checkpoint) },
            onRaw = onRawText)
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
            userInput: String, currentCard: CharacterCard, externalResearch: ResearchBrief? = null
        ): String = CharacterCardAiPayload.rewrite(userInput, currentCard,
            externalResearch?.takeIf(ResearchBrief::hasContent)?.let {
                defaultJson.parseToJsonElement(defaultJson.encodeToString(ResearchBrief.serializer(), it))
            })

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
