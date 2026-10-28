package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.parseRoleplayTextSegments

internal data class DesktopPersonStatus(val text: String, val source: ChatMessage)
internal data class DesktopChatPerson(
    val cardId: String,
    val character: CharacterInfo,
    val identityUnique: Boolean,
    val status: DesktopPersonStatus?,
    val enabled: Boolean = true,
)

/** Read-only projection of persisted current alternatives, independent of the paged viewport. */
internal fun desktopChatPeople(card: CharacterCard?, messages: List<ChatMessage>, session: ChatSession? = null): List<DesktopChatPerson> {
    if (card == null) return emptyList()
    val ids = card.characters.groupingBy { it.id }.eachCount()
    val names = card.characters.groupBy { it.name.trim().lowercase() }
    val statuses = mutableMapOf<String, DesktopPersonStatus>()
    val identifiableCount = card.characters.count { it.id.isNotBlank() && ids[it.id] == 1 && names[it.name.trim().lowercase()]?.size == 1 }
    messages.asSequence().filter { it.role == MessageRole.ASSISTANT }.sortedWith(ChatMessage.TimelineComparator.reversed())
        .takeWhile { statuses.size < identifiableCount }
        .forEach { message ->
            parseRoleplayTextSegments(message.displayContent).asReversed().forEach segment@ { segment ->
                if (segment.kind != RoleplaySegmentKind.STATUS) return@segment
                val raw = segment.rawText.trim().replace("\r\n", "\n").lines()
                val closed = (raw.firstOrNull() == "---" && raw.lastOrNull() == "---") ||
                    (raw.firstOrNull()?.trim() in setOf("```", "```状态", "```状态栏", "```status") && raw.lastOrNull() == "```")
                if (!closed || raw.size < 3) return@segment
                val lines = segment.displayText.lines().map(String::trim).filter(String::isNotEmpty)
                if (lines.any { it.contains("行动选项") || it.contains("可选行动") ||
                        it.equals("action choices", true) || it.equals("action options", true) }) return@segment
                val owners = lines.mapNotNull { line ->
                    personNameField.matchEntire(line)?.groupValues?.get(1)?.trim()
                        ?: personHeading.matchEntire(line)?.groupValues?.get(1)?.trim()
                }.distinctBy { it.lowercase() }
                // No ownership inferred from adjacency, a mention in prose, or the number of voices.
                val name = owners.singleOrNull() ?: return@segment
                val person = names[name.lowercase()]?.singleOrNull() ?: return@segment
                if (person.id.isBlank() || ids[person.id] != 1 || lines.size < 2) return@segment
                statuses.putIfAbsent(person.id, DesktopPersonStatus(segment.displayText.trim(), message))
            }
        }
    return card.characters.map { person -> DesktopChatPerson(card.id, person,
        person.id.isNotBlank() && ids[person.id] == 1, statuses[person.id],
        com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.isEnabled(session, card, person.id)) }
}

internal val DesktopPrimaryChatState.participatingCharacter: CharacterCard?
    get() = selectedCharacter?.let { card -> selectedSession?.let {
        com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy.effectiveCard(it, card)
    } ?: card }

private val personNameField = Regex("(?:姓名|人物|角色|Name|Character)\\s*[:：]\\s*(.+)", RegexOption.IGNORE_CASE)
private val personHeading = Regex("(?:【|\\[)([^]】]+?)(?:状态栏|状态)(?:】|])")

/** Display-name/avatar matching alone never authorizes an edit target. */
internal fun desktopChatPersonForSpeaker(people: List<DesktopChatPerson>, speakerName: String?): DesktopChatPerson? {
    val name = speakerName?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return people.filter { it.character.name.trim().equals(name, true) }.singleOrNull()?.takeIf { it.identityUnique }
}
