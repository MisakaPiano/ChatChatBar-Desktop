package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.withAiTaskRun
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class LlmResearchBriefSummarizer(
    private val chatService: StreamingChatService,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        allowTrailingComma = true
    }
) : ResearchBriefSummarizer {
    private val core = CharacterResearchBriefCore(characterResearchGateway(chatService), json)
    override suspend fun summarize(request: String, currentCard: CharacterCard, plan: CharacterResearchPlan,
        sources: List<ResearchSource>, modelConfig: ModelConfig, onStatus: (String) -> Unit,
        onRawText: (String) -> Unit): ResearchBriefResult = withAiTaskRun(Dispatchers.IO) {
        core.summarize(request, currentCard, plan, sources, modelConfig, onStatus, onRawText)
    }
    internal fun rawOutputFallbackBrief(rawResponse: String, plan: CharacterResearchPlan, sources: List<ResearchSource>): ResearchBrief? =
        core.rawOutputFallbackBrief(rawResponse, plan, sources)
    fun parseSummary(raw: String): ResearchBriefDraft? = core.parseSummary(raw)
    internal fun summaryUserPrompt(request: String, plan: CharacterResearchPlan, sources: List<ResearchSource>): String =
        core.summaryUserPrompt(request, plan, sources)
}
