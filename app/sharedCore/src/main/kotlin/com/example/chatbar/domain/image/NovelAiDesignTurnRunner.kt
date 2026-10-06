package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.NovelAiDesignConversationRepository

class NovelAiDesignTurnRunner(
    private val conversationRepository: NovelAiDesignConversationRepository,
    private val promptDesigner: NovelAiPromptDesigner,
) {
    suspend fun run(conversationId: String, turnId: String, model: ModelConfig, playerName: String?,
        onContent: (String) -> Unit = {}, onReasoning: (String) -> Unit = {}) {
            val conversation = conversationRepository.conversations.value
                .firstOrNull { it.id == conversationId }
                ?: error("AI 设计会话不存在")
            val turnIndex = conversation.turns.indexOfFirst { it.id == turnId }
            val turn = conversation.turns.getOrNull(turnIndex) ?: error("AI 设计轮次不存在")
            val replacesExistingReply = turn.reply != null
            val revisionBaseline = conversation.revisionBaselineFor(turnIndex)
            val designContext = conversation.designContext
            val characterPrompt = designContext.characterPrompt
            val cardPrompts = designContext.characterImagePrompts.map { it.name to it.prompt }
            val designResult = if (revisionBaseline == null) {
                promptDesigner.designForPromptToolDetailed(
                    imageDescription = turn.userText,
                    characterPrompt = characterPrompt,
                    characterImagePrompts = cardPrompts,
                    finalPromptRequirement = designContext.finalPromptRequirement,
                    model = model,
                    playerName = playerName,
                    targetImageModel = turn.targetImageModel,
                    naturalLanguageMode = turn.naturalLanguageMode,
                    onContentDelta = onContent,
                    onReasoningDelta = onReasoning
                )
            } else {
                val plan = promptDesigner.reviseForPromptTool(
                    previousPlan = revisionBaseline,
                    modificationRequest = turn.userText,
                    characterPrompt = characterPrompt,
                    characterImagePrompts = cardPrompts,
                    initialResearch = conversation.revisionResearchFor(turnIndex),
                    finalPromptRequirement = designContext.finalPromptRequirement,
                    model = model,
                    playerName = playerName,
                    targetImageModel = turn.targetImageModel,
                    naturalLanguageMode = turn.naturalLanguageMode,
                    onContentDelta = onContent,
                    onReasoningDelta = onReasoning
                )
                com.example.chatbar.domain.image.NovelAiPromptToolDesignResult(
                    plan = plan,
                    research = conversation.revisionResearchFor(turnIndex)
                )
            }
            val reply = NovelAiDesignReply(
                plan = designResult.plan,
                targetImageModel = turn.targetImageModel,
                designModelId = model.id,
                naturalLanguageMode = turn.naturalLanguageMode
            )
            conversationRepository.completeTurn(
                conversationId = conversationId,
                turnId = turnId,
                reply = reply,
                initialResearch = if (revisionBaseline == null) designResult.research else null,
                replaceInitialResearch = revisionBaseline == null && replacesExistingReply
            )
    }
}
