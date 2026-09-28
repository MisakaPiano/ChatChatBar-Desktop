package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.domain.card.FormatCardUserToolPolicy
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.ChatHistoryPromptPolicy
import com.example.chatbar.domain.chat.ChatHistoryPromptZone
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.chat.TimelineArchiveBoundaryPolicy
import com.example.chatbar.domain.chat.MainChatRequestAssembler
import com.example.chatbar.domain.chat.MainChatRequestAssemblyInput
import com.example.chatbar.domain.chat.MainChatRequestAssemblyResult
import com.example.chatbar.domain.chat.PlaceholderRenderer
import com.example.chatbar.domain.chat.PromptAssembler
import com.example.chatbar.domain.chat.joinPromptParts
import com.example.chatbar.domain.chat.resolveFormatCardForRequest
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import com.example.chatbar.domain.worldbook.WorldBookRequestPlan
import com.example.chatbar.domain.worldbook.WorldBookRequestPlanner
import com.example.chatbar.domain.worldbook.WorldBookScanContext

/**
 * Desktop request-planning authority shared by the fake runtime and read-only Prompt Inspector.
 * It produces logical request data and proposed WorldBook state without persisting either.
 */
internal class DesktopChatRequestPlanner(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val formatCardRepository: FormatCardRepository,
    private val contextWindowManager: ContextWindowManager,
    private val worldBookRequestPlanner: WorldBookRequestPlanner,
    private val promptAssembler: PromptAssembler,
    private val mainChatRequestAssembler: MainChatRequestAssembler,
) {
    suspend fun planPersistedUser(
        sessionId: String,
        userMessageId: String,
        inputs: DesktopFakeChatInputs,
        readOnlyRepositoryAccess: Boolean,
    ): DesktopChatRequestPlan {
        val user = requireNotNull(chatRepository.getMessage(userMessageId, sessionId)) {
            "当前用户消息不存在"
        }
        require(user.role == MessageRole.USER) { "当前消息必须是USER" }
        return planPersistedUser(sessionId, user, inputs, readOnlyRepositoryAccess)
    }

    suspend fun planPersistedUser(
        sessionId: String,
        currentUser: ChatMessage,
        inputs: DesktopFakeChatInputs,
        readOnlyRepositoryAccess: Boolean,
    ): DesktopChatRequestPlan {
        return planCurrentUser(
            sessionId = sessionId,
            currentUser = currentUser,
            currentUserPersisted = true,
            inputs = inputs,
            readOnlyRepositoryAccess = readOnlyRepositoryAccess,
        )
    }

    suspend fun planContinuation(
        sessionId: String,
        inputs: DesktopFakeChatInputs,
        readOnlyRepositoryAccess: Boolean,
    ): DesktopChatRequestPlan {
        val latest = if (readOnlyRepositoryAccess) {
            chatRepository.getMessagesReadOnly(sessionId).lastOrNull()
        } else {
            chatRepository.getRecentMessages(sessionId, 1).lastOrNull()
        }
        val persistedUser = latest?.takeIf { it.role == MessageRole.USER }
        val currentUser = persistedUser ?: ChatMessage.create(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = MainChatPromptAuthority.continueGenerationUserPrompt(),
        )
        return planCurrentUser(
            sessionId = sessionId,
            currentUser = currentUser,
            currentUserPersisted = persistedUser != null,
            inputs = inputs,
            readOnlyRepositoryAccess = readOnlyRepositoryAccess,
        )
    }

    suspend fun firstUserToolValidationError(
        session: ChatSession,
        defaultFormatCardId: String?,
    ): String? = FormatCardUserToolPolicy.firstValidationError(
        resolveFormatCardForRequest(
            sessionFormatCardId = session.formatCardId,
            defaultFormatCardId = defaultFormatCardId,
            availableCards = formatCardRepository.getAll(),
        )?.userTools.orEmpty(),
    )

    suspend fun planRegeneration(
        sessionId: String,
        targetMessageId: String,
        inputs: DesktopFakeChatInputs,
    ): DesktopChatRequestPlan {
        val candidates = chatRepository.getContextCandidateMessages(
            sessionId, recentTurnCount = inputs.effectiveContextWindowSize.coerceAtLeast(0) + 6,
        ).filterNot { it.id == targetMessageId }
        val context = TimelineArchiveBoundaryPolicy.expandDirectContextToWholeTurns(
            candidates, contextWindowManager.getRecentMessages(candidates, inputs.effectiveContextWindowSize),
        )
        val user = context.lastOrNull { it.role == MessageRole.USER }
        return planCurrentUser(
            sessionId, user ?: ChatMessage.create(sessionId, MessageRole.USER, ""),
            currentUserPersisted = user != null, inputs = inputs, readOnlyRepositoryAccess = false,
            regenerationTargetId = targetMessageId, regenerationContext = context,
            includeCurrentUser = user != null,
        )
    }

    private suspend fun planCurrentUser(
        sessionId: String,
        currentUser: ChatMessage,
        currentUserPersisted: Boolean,
        inputs: DesktopFakeChatInputs,
        readOnlyRepositoryAccess: Boolean,
        regenerationTargetId: String? = null,
        regenerationContext: List<ChatMessage>? = null,
        includeCurrentUser: Boolean = true,
    ): DesktopChatRequestPlan {
        val session = requireNotNull(chatRepository.getSession(sessionId)) { "对话不存在" }
        require(currentUser.sessionId == session.id) { "当前用户消息不属于该对话" }
        require(currentUser.role == MessageRole.USER) { "当前消息必须是USER" }
        val card = requireNotNull(characterRepository.getById(session.characterCardId)) { "角色卡不存在" }
        val playerName = session.playerName?.takeIf(String::isNotBlank)
            ?: inputs.globalPlayerName?.takeIf(String::isNotBlank)
        val playerSetting = session.playerSetting?.takeIf(String::isNotBlank)
            ?: inputs.globalPlayerSetting?.takeIf(String::isNotBlank)

        val effectiveContextWindowSize = inputs.effectiveContextWindowSize.coerceAtLeast(0)
        val candidateTurnCount = (effectiveContextWindowSize.toLong() + CONTEXT_CANDIDATE_PADDING)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val candidates = if (readOnlyRepositoryAccess) {
            chatRepository.getContextCandidateMessagesReadOnly(
                sessionId = session.id,
                recentTurnCount = candidateTurnCount,
                includeSourceTurnIds = setOfNotNull(currentUser.sourceTurnId).takeIf { currentUserPersisted }
                    .orEmpty(),
            )
        } else {
            chatRepository.getContextCandidateMessages(
                sessionId = session.id,
                recentTurnCount = candidateTurnCount,
                includeSourceTurnIds = setOfNotNull(currentUser.sourceTurnId).takeIf { currentUserPersisted }
                    .orEmpty(),
            )
        }
        val contextCandidates = if (currentUserPersisted) {
            val currentIndex = candidates.indexOfFirst { it.id == currentUser.id }
            check(currentIndex >= 0) { "当前用户消息不在对话上下文中" }
            candidates.take(currentIndex + 1)
        } else {
            candidates
        }
        val contextMessages = regenerationContext ?: contextWindowManager.getRecentMessages(
            allMessages = contextCandidates,
            windowSize = effectiveContextWindowSize,
        )
        val promptGroups = contextWindowManager.getPromptMessageGroups(
            contextMessages = contextMessages,
            latestMessageId = currentUser.id.takeIf { currentUserPersisted },
        )

        val worldBookDebugLog = mutableListOf<String>()
        val worldBookPlan = worldBookRequestPlanner.plan(
            card = card,
            session = session,
            previousTimed = session.timedWorldInfo,
            excludedMessageId = regenerationTargetId,
            transientUserMessage = currentUser.takeIf { !currentUserPersisted && regenerationTargetId == null },
            scanContext = WorldBookScanContext.fromCard(card, playerSetting, playerName),
            playerName = playerName,
            debugLog = worldBookDebugLog::add,
            readOnlyRepositoryAccess = readOnlyRepositoryAccess,
        )

        val activeFormatCard = resolveFormatCardForRequest(
            sessionFormatCardId = session.formatCardId,
            defaultFormatCardId = inputs.defaultFormatCardId,
            availableCards = formatCardRepository.getAll(),
        )
        val renderedFormatCardContent = activeFormatCard
            ?.content
            ?.takeIf(String::isNotBlank)
            ?.let { content ->
                promptAssembler.renderFormatCardForUserMessage(
                    content = content,
                    playerName = playerName,
                    botName = card.effectiveBotName,
                    worldBookOutlets = worldBookPlan.outlets,
                )
            }
        val requirements = MainChatPromptAuthority.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = renderedFormatCardContent,
            replyLength = session.replyLength,
            includeFormatHistoryContinuityNotice =
                ChatHistoryPromptPolicy.shouldIncludeFormatContinuityNotice(
                    excludeAssistantStatusFromHistory = inputs.excludeAssistantStatusFromHistory,
                    formatCardContent = renderedFormatCardContent,
                    earlierHistoryMessages = promptGroups.historyMessages,
                ),
        )
        val promptLayers = promptAssembler.assembleCachePromptLayers(
            characterCard = card,
            playerSetting = playerSetting,
            playerName = playerName,
            supplementarySetting = session.supplementarySetting?.takeIf(String::isNotBlank),
            ragResults = emptyList(),
            ragInjectionMode = inputs.ragInjectionMode,
            replyLength = session.replyLength,
            replyLanguage = session.replyLanguage?.takeIf(String::isNotBlank),
            memoryArchive = null,
            memoryHeadAndTimeline = null,
            worldBookPrompt = worldBookPlan.prompt,
            worldBookOutlets = worldBookPlan.outlets,
        )
        val positionedRequirements = joinPromptParts(
            requirements,
            promptLayers.replyConstraintsSystemPrompt,
            MainChatPromptAuthority.replyTailSystemPrompt(
                replyLength = session.replyLength,
                roleplaySpeakerFormatEnabled = inputs.assistantSegmentedBubblesEnabled,
                characterNames = card.characters.map { it.name },
            ),
        )
        val earlierHistory = promptGroups.historyMessages.mapNotNull { message ->
            logicalHistoryMessage(
                message = message,
                zone = ChatHistoryPromptZone.EARLIER_HISTORY,
                inputs = inputs,
                playerName = playerName,
                card = card,
            )
        }
        val previousTurn = promptGroups.previousTurnMessages.mapNotNull { message ->
            logicalHistoryMessage(
                message = message,
                zone = ChatHistoryPromptZone.PREVIOUS_TURN,
                inputs = inputs,
                playerName = playerName,
                card = card,
            )
        }
        val renderedCurrentUser = PlaceholderRenderer.render(
            currentUser.displayContent,
            playerName,
            card.effectiveBotName,
        )
        val requestCurrentUser = if (includeCurrentUser) FormatCardUserToolPolicy.appendRequestSuffix(
            userContent = renderedCurrentUser,
            tools = activeFormatCard?.userTools.orEmpty(),
        ) else null
        val assembly = mainChatRequestAssembler.assemble(
            MainChatRequestAssemblyInput(
                promptLayers = promptLayers,
                positionedRequirementsSystemPrompt = positionedRequirements,
                formatPromptPosition = inputs.formatPromptPosition,
                earlierHistoryMessages = earlierHistory,
                previousTurnMessages = previousTurn,
                currentUserMessage = requestCurrentUser?.let { ChatApiMessage.text("user", it) },
                strongPromptSystemSuffix = if (includeCurrentUser) FormatCardUserToolPolicy.strongPromptSystemSuffix(
                    activeFormatCard?.userTools.orEmpty(),
                ) else "",
                playerName = playerName,
                botName = card.effectiveBotName,
            ),
        )
        return DesktopChatRequestPlan(
            session = session,
            currentUser = currentUser,
            assembly = assembly,
            worldBook = worldBookPlan,
            worldBookDebugLog = worldBookDebugLog,
            currentUserPersisted = currentUserPersisted,
        )
    }

    private fun logicalHistoryMessage(
        message: ChatMessage,
        zone: ChatHistoryPromptZone,
        inputs: DesktopFakeChatInputs,
        playerName: String?,
        card: CharacterCard,
    ): ChatApiMessage? {
        val source = ChatHistoryPromptPolicy.sourceText(
            message = message,
            excludeAssistantStatusFromHistory = inputs.excludeAssistantStatusFromHistory,
            zone = zone,
        )
        val rendered = PlaceholderRenderer.render(source, playerName, card.effectiveBotName)
        val payload = ChatHistoryPromptPolicy.payloadText(rendered, hasSupportedImage = false)
            ?: return null
        return ChatApiMessage.text(message.role.name.lowercase(), payload)
    }

    private companion object {
        const val CONTEXT_CANDIDATE_PADDING = 6L
    }
}

internal data class DesktopChatRequestPlan(
    val session: ChatSession,
    val currentUser: ChatMessage,
    val assembly: MainChatRequestAssemblyResult,
    val worldBook: WorldBookRequestPlan,
    val worldBookDebugLog: List<String>,
    val currentUserPersisted: Boolean,
) {
    fun toFakeRequest(): DesktopFakeChatRequest = DesktopFakeChatRequest(
        sessionId = session.id,
        messages = assembly.messages,
        promptCacheKey = assembly.promptCacheKey,
    )
}
