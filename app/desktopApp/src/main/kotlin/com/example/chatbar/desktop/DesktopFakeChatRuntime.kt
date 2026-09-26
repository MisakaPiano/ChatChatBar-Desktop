package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.domain.card.FormatCardUserToolPolicy
import com.example.chatbar.domain.chat.CharacterSessionService
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.ChatHistoryPromptPolicy
import com.example.chatbar.domain.chat.ChatHistoryPromptZone
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.chat.MainChatRequestAssembler
import com.example.chatbar.domain.chat.MainChatRequestAssemblyInput
import com.example.chatbar.domain.chat.PlaceholderRenderer
import com.example.chatbar.domain.chat.PromptAssembler
import com.example.chatbar.domain.chat.joinPromptParts
import com.example.chatbar.domain.chat.resolveFormatCardForRequest
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import com.example.chatbar.domain.worldbook.WorldBookRequestPlanner
import com.example.chatbar.domain.worldbook.WorldBookScanContext

data class DesktopFakeChatInputs(
    val globalPlayerName: String? = null,
    val globalPlayerSetting: String? = null,
    val effectiveContextWindowSize: Int,
    val defaultFormatCardId: String? = null,
    val formatPromptPosition: FormatPromptPosition = FormatPromptPosition.END,
    val excludeAssistantStatusFromHistory: Boolean = false,
    val ragInjectionMode: String = "OFF",
    val assistantSegmentedBubblesEnabled: Boolean = false,
)

data class DesktopFakeChatRequest(
    val sessionId: String,
    val messages: List<ChatApiMessage>,
    val promptCacheKey: String?,
)

data class DesktopFakeChatSession(
    val session: ChatSession,
    val messages: List<ChatMessage>,
)

fun interface DesktopFakeChatDriver {
    suspend fun capture(request: DesktopFakeChatRequest)
}

/**
 * Desktop-only fake driver orchestration over the shared chat authorities.
 *
 * This boundary persists real chat state and emits transport-neutral logical messages only. It
 * performs no provider request and never fabricates an assistant response.
 */
class DesktopFakeChatRuntime internal constructor(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val formatCardRepository: FormatCardRepository,
    private val characterSessionService: CharacterSessionService,
    private val contextWindowManager: ContextWindowManager,
    private val worldBookRequestPlanner: WorldBookRequestPlanner,
    private val promptAssembler: PromptAssembler,
    private val mainChatRequestAssembler: MainChatRequestAssembler,
    private val driver: DesktopFakeChatDriver,
) {
    suspend fun createSession(characterId: String): String =
        characterSessionService.createSessionForCharacter(characterId)

    suspend fun openSession(sessionId: String): DesktopFakeChatSession {
        val session = requireNotNull(chatRepository.getSession(sessionId)) { "对话不存在" }
        return DesktopFakeChatSession(session, chatRepository.getMessages(sessionId))
    }

    suspend fun submitText(
        sessionId: String,
        content: String,
        inputs: DesktopFakeChatInputs,
    ): DesktopFakeChatRequest {
        require(content.isNotBlank()) { "用户消息不能为空" }
        val session = requireNotNull(chatRepository.getSession(sessionId)) { "对话不存在" }
        requireNotNull(characterRepository.getById(session.characterCardId)) { "角色卡不存在" }
        val persistedUser = chatRepository.addMessage(
            ChatMessage.create(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = content,
            ),
        )
        return buildAndCapture(sessionId, persistedUser, inputs)
    }

    suspend fun rebuildForPersistedUser(
        sessionId: String,
        userMessageId: String,
        inputs: DesktopFakeChatInputs,
    ): DesktopFakeChatRequest {
        val user = requireNotNull(chatRepository.getMessage(userMessageId, sessionId)) {
            "当前用户消息不存在"
        }
        require(user.role == MessageRole.USER) { "当前消息必须是USER" }
        return buildAndCapture(sessionId, user, inputs)
    }

    private suspend fun buildAndCapture(
        sessionId: String,
        currentUser: ChatMessage,
        inputs: DesktopFakeChatInputs,
    ): DesktopFakeChatRequest {
        var session = requireNotNull(chatRepository.getSession(sessionId)) { "对话不存在" }
        require(currentUser.sessionId == session.id) { "当前用户消息不属于该对话" }
        val card = requireNotNull(characterRepository.getById(session.characterCardId)) { "角色卡不存在" }
        val playerName = session.playerName?.takeIf(String::isNotBlank)
            ?: inputs.globalPlayerName?.takeIf(String::isNotBlank)
        val playerSetting = session.playerSetting?.takeIf(String::isNotBlank)
            ?: inputs.globalPlayerSetting?.takeIf(String::isNotBlank)

        val effectiveContextWindowSize = inputs.effectiveContextWindowSize.coerceAtLeast(0)
        val candidateTurnCount = (effectiveContextWindowSize.toLong() + CONTEXT_CANDIDATE_PADDING)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val candidates = chatRepository.getContextCandidateMessages(
            sessionId = session.id,
            recentTurnCount = candidateTurnCount,
            includeSourceTurnIds = setOfNotNull(currentUser.sourceTurnId),
        )
        val currentIndex = candidates.indexOfFirst { it.id == currentUser.id }
        check(currentIndex >= 0) { "当前用户消息不在对话上下文中" }
        val contextMessages = contextWindowManager.getRecentMessages(
            allMessages = candidates.take(currentIndex + 1),
            windowSize = effectiveContextWindowSize,
        )
        val promptGroups = contextWindowManager.getPromptMessageGroups(
            contextMessages = contextMessages,
            latestMessageId = currentUser.id,
        )

        val worldBookPlan = worldBookRequestPlanner.plan(
            card = card,
            session = session,
            previousTimed = session.timedWorldInfo,
            excludedMessageId = null,
            transientUserMessage = null,
            scanContext = WorldBookScanContext.fromCard(card, playerSetting, playerName),
            playerName = playerName,
        )
        if (worldBookPlan.timedWorldInfo != session.timedWorldInfo) {
            session = session.copy(timedWorldInfo = worldBookPlan.timedWorldInfo)
            chatRepository.updateSession(session)
        }

        val availableFormatCards = formatCardRepository.getAll()
        val activeFormatCard = resolveFormatCardForRequest(
            sessionFormatCardId = session.formatCardId,
            defaultFormatCardId = inputs.defaultFormatCardId,
            availableCards = availableFormatCards,
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
        val requestCurrentUser = FormatCardUserToolPolicy.appendRequestSuffix(
            userContent = renderedCurrentUser,
            tools = activeFormatCard?.userTools.orEmpty(),
        )
        val assembly = mainChatRequestAssembler.assemble(
            MainChatRequestAssemblyInput(
                promptLayers = promptLayers,
                positionedRequirementsSystemPrompt = positionedRequirements,
                formatPromptPosition = inputs.formatPromptPosition,
                earlierHistoryMessages = earlierHistory,
                previousTurnMessages = previousTurn,
                currentUserMessage = ChatApiMessage.text("user", requestCurrentUser),
                strongPromptSystemSuffix = FormatCardUserToolPolicy.strongPromptSystemSuffix(
                    activeFormatCard?.userTools.orEmpty(),
                ),
                playerName = playerName,
                botName = card.effectiveBotName,
            ),
        )
        val request = DesktopFakeChatRequest(
            sessionId = session.id,
            messages = assembly.messages,
            promptCacheKey = assembly.promptCacheKey,
        )
        driver.capture(request)
        return request
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
