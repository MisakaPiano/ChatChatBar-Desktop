package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.domain.card.CharacterGreetingChoice
import com.example.chatbar.domain.card.CharacterGreetingPolicy

class CharacterSessionService(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val formatCardRepository: FormatCardRepository,
    private val staleFormatCardWarning: (String) -> Unit = {},
) {
    suspend fun createSessionForCharacter(cardId: String, greetingChoice: CharacterGreetingChoice? = null): String {
        val card = requireNotNull(characterRepository.getById(cardId)) { "角色卡不存在" }
        val greeting = CharacterGreetingPolicy.resolve(card, greetingChoice)
        val defaultFormatCardId = card.defaultFormatCardId?.takeIf(String::isNotBlank)?.let { id ->
            if (formatCardRepository.getById(id) != null) {
                id
            } else {
                staleFormatCardWarning(
                    "角色卡 ${card.id} 绑定的格式卡 $id 已不存在，新会话沿用全局默认"
                )
                null
            }
        }
        val session = ChatSession.create(
            characterCardId = card.id,
            title = card.name,
            formatCardId = defaultFormatCardId,
        )
        chatRepository.createSession(session)
        chatRepository.addMessage(
            ChatMessage.create(
                sessionId = session.id,
                role = MessageRole.ASSISTANT,
                content = greeting,
            )
        )
        return session.id
    }
}
