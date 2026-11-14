package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

/** A read-only snapshot; absence means ownership could not be established, not that a person is inactive. */
data class ChatPersonStatus(val text: String, val sourceMessageId: String, val sourceCreatedAt: Long)

class ChatPeopleStatusCollector(card: CharacterCard) {
    private val ids = card.characters.groupingBy { it.id }.eachCount()
    private val people = card.characters.groupBy { it.name.trim().lowercase() }
    private data class Saved(val order: Triple<Long, Long, String>, val status: ChatPersonStatus)
    private val found = mutableMapOf<String, Saved>()

    fun accept(message: ChatMessage) {
        if (message.role != MessageRole.ASSISTANT) return
        val order = Triple(message.orderKey, message.createdAt, message.id)
        parseRoleplayTextSegments(message.displayContent).forEach { segment ->
            val owner = RoleplayStatusOwnership.ownerNames(segment).singleOrNull() ?: return@forEach
            val person = people[owner.lowercase()]?.singleOrNull() ?: return@forEach
            if (person.id.isBlank() || ids[person.id] != 1) return@forEach
            val old = found[person.id]?.order
            val comparator = compareBy<Triple<Long, Long, String>>({ it.first }, { it.second }, { it.third })
            if (old == null || comparator.compare(order, old) >= 0) {
                found[person.id] = Saved(order, ChatPersonStatus(segment.displayText.trim(), message.id, message.createdAt))
            }
        }
    }

    fun snapshot(): Map<String, ChatPersonStatus> = found.mapValues { it.value.status }
}
