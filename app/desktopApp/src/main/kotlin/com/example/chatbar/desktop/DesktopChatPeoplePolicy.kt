package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.RoleplayStatusOwnership
import com.example.chatbar.domain.chat.parseRoleplayTextSegments

internal data class DesktopPersonStatus(val text: String, val source: ChatMessage)
internal data class DesktopChatPerson(
    val cardId: String,
    val character: CharacterInfo,
    val identityUnique: Boolean,
    val status: DesktopPersonStatus?,
    val enabled: Boolean = true,
    val cardEnabled: Boolean = true,
    val resident: Boolean = true,
)

/** Read-only projection of persisted current alternatives, independent of the paged viewport. */
internal fun desktopChatPeople(card: CharacterCard?, messages: List<ChatMessage>, session: ChatSession? = null): List<DesktopChatPerson> {
    if (card == null) return emptyList()
    val presence = com.example.chatbar.domain.chat.CharacterResidencyPolicy.recent(card, messages)
    val ids = card.characters.groupingBy { it.id }.eachCount()
    val names = card.characters.groupBy { it.name.trim().lowercase() }
    val statuses = mutableMapOf<String, DesktopPersonStatus>()
    val identifiableCount = card.characters.count { it.id.isNotBlank() && ids[it.id] == 1 && names[it.name.trim().lowercase()]?.size == 1 }
    messages.asSequence().filter { it.role == MessageRole.ASSISTANT }.sortedWith(ChatMessage.TimelineComparator.reversed())
        .takeWhile { statuses.size < identifiableCount }
        .forEach { message ->
            parseRoleplayTextSegments(message.displayContent).asReversed().forEach segment@ { segment ->
                if (segment.kind != RoleplaySegmentKind.STATUS) return@segment
                val owners = RoleplayStatusOwnership.ownerNames(segment)
                // No ownership inferred from adjacency, a mention in prose, or the number of voices.
                val name = owners.singleOrNull() ?: return@segment
                val person = names[name.lowercase()]?.singleOrNull() ?: return@segment
                if (person.id.isBlank() || ids[person.id] != 1) return@segment
                statuses.putIfAbsent(person.id, DesktopPersonStatus(segment.displayText.trim(), message))
            }
        }
    return card.characters.map { person -> DesktopChatPerson(card.id, person,
        person.id.isNotBlank() && ids[person.id] == 1, statuses[person.id],
        com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.isEnabled(session, card, person.id),
        com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.cardAllows(card, person.id),
        person.id !in presence.absentForTwoTurns) }
}

internal val DesktopPrimaryChatState.participatingCharacter: CharacterCard?
    get() = selectedCharacter?.let { card -> selectedSession?.let {
        com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.effectiveCard(it, card)
    } ?: card }

/** Display-name/avatar matching alone never authorizes an edit target. */
internal fun desktopChatPersonForSpeaker(people: List<DesktopChatPerson>, speakerName: String?): DesktopChatPerson? {
    val name = speakerName?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return people.filter { it.character.name.trim().equals(name, true) }.singleOrNull()?.takeIf { it.identityUnique }
}
