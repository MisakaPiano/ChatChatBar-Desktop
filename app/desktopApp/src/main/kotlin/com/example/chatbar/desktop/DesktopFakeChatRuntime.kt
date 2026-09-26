package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.CharacterSessionService
import com.example.chatbar.domain.chat.ChatApiMessage

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
    private val characterSessionService: CharacterSessionService,
    private val requestPlanner: DesktopChatRequestPlanner,
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
        val plan = requestPlanner.planPersistedUser(
            sessionId = sessionId,
            currentUser = currentUser,
            inputs = inputs,
            readOnlyRepositoryAccess = false,
        )
        if (plan.worldBook.timedWorldInfo != plan.session.timedWorldInfo) {
            chatRepository.updateSession(plan.session.copy(timedWorldInfo = plan.worldBook.timedWorldInfo))
        }
        val request = plan.toFakeRequest()
        driver.capture(request)
        return request
    }
}
