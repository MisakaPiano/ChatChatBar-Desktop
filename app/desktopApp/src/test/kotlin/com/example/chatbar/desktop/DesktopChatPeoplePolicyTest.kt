package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import kotlin.test.*

class DesktopChatPeoplePolicyTest {
    private val alice = CharacterInfo.create("Alice")
    private val bob = CharacterInfo.create("Bob")
    private val card = CharacterCard.create("Card", greeting = "Hello").copy(characters = listOf(alice, bob))
    private fun message(text: String, order: Long = 1) = ChatMessage.create("session", MessageRole.ASSISTANT, text)
        .copy(orderKey = order)
    private fun status(name: String, text: String) = "---\n姓名：$name\n心情：$text\n---"

    @Test fun `latest persisted current alternative wins for each explicitly identified person`() {
        val older = message(status("Alice", "old"), 1)
        val bobStatus = message("```status\nName: Bob\nMood: calm\n```", 2)
        val newer = message("old version", 3).copy(alternatives = listOf(status("Alice", "discarded"), status("Alice", "latest")),
            currentAlternativeIndex = 1)
        val people = desktopChatPeople(card, listOf(newer, older, bobStatus))
        assertTrue(people[0].status!!.text.contains("latest"))
        assertEquals(newer, people[0].status!!.source)
        assertEquals(bobStatus, people[1].status!!.source)
        assertEquals(listOf(alice.id, bob.id), people.map { it.character.id })
    }

    @Test fun `mentions adjacency single voice and generic unnamed status never establish ownership`() {
        for (text in listOf("<n=\"Alice\"/>[Hello]\n---\n心情：calm\n---", "---\nAlice is calm\n---",
            "---\n心情：Alice很平静\n---", "---\n姓名：Unknown\n心情：calm\n---")) {
            assertTrue(desktopChatPeople(card, listOf(message(text))).all { it.status == null })
        }
        assertNull(desktopChatPeople(card.copy(characters = listOf(alice)), listOf(message("---\n心情：calm\n---"))).single().status)
    }

    @Test fun `mixed owners code unfinished fences user messages and action options stay unrecognized`() {
        val rejected = listOf(
            status("Alice", "calm").removeSuffix("---"), "```python\n姓名：Alice\n心情：calm\n```",
            "---\n姓名：Alice\n姓名：Bob\n心情：calm\n---",
            "---\n姓名：Alice / Bob\n心情：calm\n---",
            "---\n姓名：Alice\n行动选项\n1. 等待\n2. 离开\n---",
        )
        rejected.forEach { text -> assertTrue(desktopChatPeople(card, listOf(message(text))).all { it.status == null }, text) }
        assertTrue(desktopChatPeople(card, listOf(message(status("Alice", "calm")).copy(role = MessageRole.USER))).all { it.status == null })
    }

    @Test fun `duplicate names cannot identify a status or avatar edit while duplicate IDs disable all identity actions`() {
        val names = card.copy(characters = listOf(alice, bob.copy(name = " alice ")))
        assertTrue(desktopChatPeople(names, listOf(message(status("Alice", "calm")))).all { it.status == null })
        assertNull(desktopChatPersonForSpeaker(desktopChatPeople(names, emptyList()), "Alice"))
        val ids = card.copy(characters = listOf(alice, bob.copy(id = alice.id)))
        assertTrue(desktopChatPeople(ids, listOf(message(status("Alice", "calm")))).all { !it.identityUnique && it.status == null })
        assertNull(desktopChatPersonForSpeaker(desktopChatPeople(ids, emptyList()), "Alice"))
    }

    @Test fun `edited deleted alternate selected and renamed sources recompute without retaining stale state`() {
        val source = message(status("Alice", "old"))
        assertNotNull(desktopChatPeople(card, listOf(source)).first().status)
        assertNull(desktopChatPeople(card, listOf(source.copy(content = "No status"))).first().status)
        assertNull(desktopChatPeople(card, emptyList()).first().status)
        val changed = source.copy(alternatives = listOf(source.content, "No status"), currentAlternativeIndex = 1)
        assertNull(desktopChatPeople(card, listOf(changed)).first().status)
        assertNull(desktopChatPeople(card.copy(characters = listOf(alice.copy(name = "Renamed"))), listOf(source)).first().status)
        assertEquals(alice.id, desktopChatPersonForSpeaker(desktopChatPeople(card, emptyList()), "alice")!!.character.id)
        assertNull(desktopChatPersonForSpeaker(desktopChatPeople(card, emptyList()), null))
    }

    @Test fun `explicit status heading works while references to another person are not mistaken for owners`() {
        val source = message("---\n【Alice状态】\n关系：与Bob成为朋友\n---")
        val people = desktopChatPeople(card, listOf(source))
        assertEquals(source, people.first().status!!.source)
        assertNull(people.last().status)
    }

    @Test fun `emoji and markdown owner labels expose full latest status without changing source`() {
        for (owner in listOf("♟姓名:Alice", "👤 **姓名：**Alice", "- **Name: Alice**", "✨【Alice状态】")) {
            val text = "---\n$owner\n位置：门口\n关系：等待Bob\n---"
            val source = message(text)
            val people = desktopChatPeople(card, listOf(source))
            assertEquals(source, people.first().status?.source, owner)
            assertTrue(people.first().status!!.text.contains("位置：门口"))
            assertNull(people.last().status)
            assertEquals(text, source.content)
        }
        val mixed = message("---\n👤姓名：Alice\n👤姓名：Bob\n位置：门口\n---")
        assertTrue(desktopChatPeople(card, listOf(mixed)).all { it.status == null })
    }
}
