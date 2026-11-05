package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.card.extractJsonObjectCandidates
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.prompt.rethrowIfAiTaskTerminalFailure
import com.example.chatbar.domain.prompt.CharacterCardAiPromptAuthority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class CharacterResearchBriefCore(
    private val gateway: CharacterResearchAiGateway,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        allowTrailingComma = true
    }
) : ResearchBriefSummarizer {
    override suspend fun summarize(
        request: String,
        currentCard: CharacterCard,
        plan: CharacterResearchPlan,
        sources: List<ResearchSource>,
        modelConfig: ModelConfig,
        onStatus: (String) -> Unit,
        onRawText: (String) -> Unit
    ): ResearchBriefResult = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext ResearchBriefResult(failureReason = "sources empty")
        var rawResponse = ""
        runCatching {
            var reasoningNotified = false
            var contentNotified = false
            val visibleText = StringBuilder()
            rawResponse = gateway.complete(
                stage = CharacterResearchAiStage.SUMMARIZE,
                messages = listOf(
                    ChatApiMessage.text("system", CharacterCardAiPromptAuthority.characterResearchBriefSystemPrompt()),
                    ChatApiMessage.text("user", summaryUserPrompt(request, plan, sources))
                ),
                modelConfig = modelConfig,
                onDelta = { chunk ->
                    visibleText.append(chunk)
                    onRawText(visibleText.toString())
                    if (!contentNotified) {
                        contentNotified = true
                        onStatus("AI 正在输出资料整理结果")
                    }
                },
                onReasoningDelta = {
                    if (!reasoningNotified) {
                        reasoningNotified = true
                        onStatus("AI 正在整理资料（思考中）")
                    }
                }
            )
            val draft = parseSummary(rawResponse)
            if (draft == null) {
                ResearchBriefResult(
                    failureReason = "summary JSON parse failed",
                    rawResponsePreview = rawResponse.take(1200),
                    brief = rawOutputFallbackBrief(rawResponse, plan, sources)
                )
            } else {
                ResearchBriefResult(
                    brief = ResearchBrief(
                        reason = plan.reason,
                        queries = plan.queries.map { it.query },
                        facts = draft.facts.cleanList(12, 320),
                        notes = draft.notes.cleanList(8, 320),
                        sources = sources
                    ),
                    rawResponsePreview = rawResponse.take(1200)
                )
            }
        }.getOrElse { error ->
            error.rethrowIfAiTaskTerminalFailure()
            ResearchBriefResult(
                failureReason = error.message ?: error::class.java.simpleName,
                rawResponsePreview = rawResponse.take(1200),
                brief = rawOutputFallbackBrief(rawResponse, plan, sources)
            )
        }
    }

    fun rawOutputFallbackBrief(
        rawResponse: String,
        plan: CharacterResearchPlan,
        sources: List<ResearchSource>
    ): ResearchBrief? {
        val rawText = ResearchCleaner.sanitizeText(rawResponse)
        if (rawText.isBlank()) return null
        return ResearchBrief(
            reason = plan.reason,
            queries = plan.queries.map { it.query },
            facts = listOf(rawText),
            notes = listOf(CharacterCardAiPromptAuthority.CHARACTER_RESEARCH_BRIEF_RAW_FALLBACK_NOTE),
            sources = sources
        )
    }

    fun parseSummary(raw: String): ResearchBriefDraft? {
        val candidates = raw.extractJsonObjectCandidates().ifEmpty { listOf(raw.trim()) }
        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching { json.decodeFromString(ResearchBriefDraft.serializer(), candidate) }.getOrNull()
        }
    }

    fun summaryUserPrompt(
        request: String,
        plan: CharacterResearchPlan,
        sources: List<ResearchSource>
    ): String = CharacterCardAiPromptAuthority.characterResearchBriefUserPrompt(
        request = request,
        sources = sources.joinToString(separator = "\n\n") { source ->
            CharacterCardAiPromptAuthority.characterResearchBriefSource(
                sourceId = source.sourceId,
                title = source.title,
                sourceType = source.sourceType,
                url = source.url,
                excerpt = source.excerpt
            )
        }
    )

    private fun List<String>.cleanList(maxItems: Int, maxChars: Int): List<String> =
        map { ResearchCleaner.sanitizeText(it).take(maxChars).trim() }
            .filter(String::isNotBlank)
            .distinct()
            .take(maxItems)
}

@Serializable
data class ResearchBriefDraft(
    val facts: List<String> = emptyList(),
    val notes: List<String> = emptyList()
)
