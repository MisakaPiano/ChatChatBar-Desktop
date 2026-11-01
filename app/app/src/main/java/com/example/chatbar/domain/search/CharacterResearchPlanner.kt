package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.withAiTaskRun
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class CharacterResearchPlanner(
    private val chatService: StreamingChatService,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        allowTrailingComma = true
    }
) : CharacterResearchPlanProvider {
    private val core = CharacterResearchPlannerCore(characterResearchGateway(chatService), json)
    override suspend fun plan(userInput: String, currentCard: CharacterCard, modelConfig: ModelConfig,
        maxQueries: Int, onStatus: (String) -> Unit, onRawText: (String) -> Unit): CharacterResearchPlanResult =
        withAiTaskRun(Dispatchers.IO) { core.plan(userInput, currentCard, modelConfig, maxQueries, onStatus, onRawText) }
    fun parsePlan(raw: String, maxQueries: Int): CharacterResearchPlan? = core.parsePlan(raw, maxQueries)
}

internal fun CharacterCard.researchSummary(): String = PromptTemplates.characterResearchSummary(this)
