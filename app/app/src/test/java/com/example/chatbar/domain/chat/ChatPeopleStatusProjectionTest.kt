package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import org.junit.Assert.*
import org.junit.Test

class ChatPeopleStatusProjectionTest {
    private val card = CharacterCard.create("synthetic", characters = listOf(CharacterInfo(id="p", name="Alice")))
    private fun message(id: String, text: String, time: Long, role: MessageRole = MessageRole.ASSISTANT) =
        ChatMessage(id=id,sessionId="s",role=role,content=text,createdAt=time,updatedAt=time)

    @Test fun explicitClosedStatusUsesLatestActiveAlternativeAndPreservesRawText() {
        val collector = ChatPeopleStatusCollector(card)
        val old = message("a", "---\n姓名：Alice\n服装：coat\n---", 1)
        val newer = message("b", "---\n姓名：Alice\n服装：cape\n---", 2)
        collector.accept(newer.copy(alternatives = listOf("---\n姓名：Alice\n服装：discarded\n---", newer.content), currentAlternativeIndex = 1));collector.accept(old)
        assertEquals("b", collector.snapshot().getValue("p").sourceMessageId)
        assertTrue(collector.snapshot().getValue("p").text.contains("cape"))
        assertTrue(old.content.contains("coat"))
    }
    @Test fun proseMentionsUserAndUnclosedStatusNeverInventOwnership() {
        val collector = ChatPeopleStatusCollector(card)
        collector.accept(message("a", "Alice felt tired.", 1))
        collector.accept(message("b", "---\n姓名：Alice\n状态：ready", 2))
        collector.accept(message("c", "---\n姓名：Alice\n状态：ready\n---", 3, MessageRole.USER))
        assertTrue(collector.snapshot().isEmpty())
    }
    @Test fun duplicatePersonNameAndMultiOwnerBlockAreNotAssigned() {
        val ambiguous = ChatPeopleStatusCollector(card.copy(characters=card.characters + CharacterInfo(id="q",name="Alice")))
        ambiguous.accept(message("a", "---\n姓名：Alice\n状态：ready\n---", 1))
        assertTrue(ambiguous.snapshot().isEmpty())
        val collector = ChatPeopleStatusCollector(card)
        collector.accept(message("b", "---\n姓名：Alice\n姓名：Bob\n状态：ready\n---", 2))
        assertTrue(collector.snapshot().isEmpty())
    }
}
