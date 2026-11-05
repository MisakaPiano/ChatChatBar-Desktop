package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

/** Local output evidence only; never requests AI, interprets prose mentions, or changes prompts. */
object CharacterResidencyPolicy {
    fun presentPersonIds(card: CharacterCard, reply: ChatMessage): Set<String> {
        if (reply.role != MessageRole.ASSISTANT) return emptySet()
        val names = card.characters.groupBy { it.name.trim().lowercase() }
        val idCounts = card.characters.groupingBy { it.id }.eachCount()
        fun identify(name: String?): String? = name?.trim()?.takeIf(String::isNotEmpty)
            ?.let { names[it.lowercase()]?.singleOrNull() }
            ?.takeIf { it.id.isNotBlank() && idCounts[it.id] == 1 }?.id
        val segments = parseRoleplayTextSegments(reply.displayContent)
        return buildSet {
            segments.filter { it.kind == RoleplaySegmentKind.DIALOGUE || it.kind == RoleplaySegmentKind.THOUGHT }
                .mapNotNull { identify(it.speakerName) }.forEach(::add)
            // Only the ending status sequence participates. Ordinary code and action options do not.
            for (segment in segments.asReversed().takeWhile { it.kind == RoleplaySegmentKind.STATUS }) {
                RoleplayStatusOwnership.ownerNames(segment).mapNotNull(::identify).forEach(::add)
            }
        }
    }

    data class RecentPresence(val residentPersonIds: Set<String>, val absentForTwoTurns: Set<String>)

    fun recent(card: CharacterCard, messages: List<ChatMessage>): RecentPresence {
        val groups = ChatContextGroupPolicy.groups(messages.sortedWith(ChatMessage.TimelineComparator))
            .filter { group -> group.messages.any { it.role == MessageRole.ASSISTANT && it.displayContent.isNotBlank() } }
            .takeLast(2)
        val seen = groups.flatMap { group -> group.messages.flatMap { presentPersonIds(card, it) } }.toSet()
        val twoComplete = groups.size == 2 && groups.all { it.isCompleteTurn }
        val uniqueNames = card.characters.groupBy { it.name.trim().lowercase() }
        val uniqueIds = card.characters.groupingBy { it.id }.eachCount()
        val identifiable = card.characters.filter { it.id.isNotBlank() && it.name.isNotBlank() &&
            uniqueIds[it.id] == 1 && uniqueNames[it.name.trim().lowercase()]?.size == 1 }.map { it.id }.toSet()
        return RecentPresence(seen, if (twoComplete) identifiable - seen else emptySet())
    }
}
