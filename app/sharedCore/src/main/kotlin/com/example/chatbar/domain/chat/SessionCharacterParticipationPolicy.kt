package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.SessionCharacterParticipation

/** Request-only structured-person view; no changes to card entities or historical context. */
object SessionCharacterParticipationPolicy {
    fun isEnabled(session: ChatSession?, card: CharacterCard, personId: String): Boolean =
        session == null || session.characterCardId != card.id || card.editMode != CharacterEditMode.STRUCTURED ||
            personId !in session.desktopCharacterParticipation.singleOrNull { it.cardId == card.id }?.disabledPersonIds.orEmpty()

    fun effectiveCard(session: ChatSession, card: CharacterCard): CharacterCard {
        val active = card.characters.filter { isEnabled(session, card, it.id) }
        return if (active.size == card.characters.size) card else card.copy(characters = active)
    }

    fun change(session: ChatSession, card: CharacterCard, personId: String, enabled: Boolean): ChatSession {
        require(session.characterCardId == card.id) { "人物不属于当前会话角色卡" }
        require(card.editMode == CharacterEditMode.STRUCTURED) { "自由文本卡尚不支持逐人物关闭" }
        require(personId.isNotBlank() && card.characters.count { it.id == personId } == 1) { "人物已变化或身份不唯一" }
        val index = session.desktopCharacterParticipation.indexOfFirst { it.cardId == card.id }
        val before = session.desktopCharacterParticipation.getOrNull(index)?.disabledPersonIds.orEmpty()
        val after = if (enabled) before - personId else before + personId
        val overrides = session.desktopCharacterParticipation.toMutableList()
        if (after.isEmpty()) { if (index >= 0) overrides.removeAt(index) }
        else if (index >= 0) overrides[index] = SessionCharacterParticipation(card.id, after)
        else overrides.add(SessionCharacterParticipation(card.id, after))
        return session.copy(desktopCharacterParticipation = overrides.toList())
    }
}
