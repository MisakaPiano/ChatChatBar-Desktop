package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import java.nio.file.Files
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.test.*

class SpeakerTagHistoryRecoveryTest {
    private class Fixture {
        val root = Files.createTempDirectory("speaker-rename-recovery-")
        val storage = JsonFileStorage(root)
        val cards = CharacterRepository(storage)
        val chats = ChatRepository(storage)
        suspend fun seed(): SpeakerTagRenameTask {
            val task = SpeakerTagRenameTask("task", "card", 2,
                listOf(SpeakerTagRename("a", "A", "B"), SpeakerTagRename("b", "B", "A")), 3, rewriteProtocolVersion = 1)
            cards.save(CharacterCard("card", "Same card title", characters = listOf(CharacterInfo("a", "B"), CharacterInfo("b", "A")),
                pendingSpeakerRenameTasks = listOf(task), createdAt = 1, updatedAt = 2))
            chats.createSession(ChatSession("session", "card", "Independent session title", createdAt = 1, updatedAt = 1))
            chats.addMessage(ChatMessage("first", "session", MessageRole.ASSISTANT,
                "<n=\"A\"/>one A B <n=\"NPC\"/>keep", alternatives = listOf("<n=\"B\"/>alternative"), createdAt = 1, updatedAt = 1))
            chats.addMessage(ChatMessage("second", "session", MessageRole.ASSISTANT,
                "<n=\"B\"/>two", alternatives = listOf("<n=\"A\"/>other"), createdAt = 2, updatedAt = 2))
            return task
        }
        fun reopened() = JsonFileStorage(root).let { CharacterRepository(it) to ChatRepository(it) }
        fun close() { root.toFile().deleteRecursively() }
    }

    @Test fun `partial write and fresh repositories resume swapped names exactly once including alternatives`() = runTest {
        val f = Fixture()
        try {
            f.seed(); var writes = 0; var changedSessions = emptyList<String>()
            val failing = SpeakerTagHistoryService(f.cards, f.chats, rewrite = { task, persist ->
                f.chats.rewriteSpeakerTagsForTask(task, persist) { message ->
                    f.storage.saveEntity("chat_messages", "${message.sessionId}_${message.id}", message, ChatMessage.serializer())
                    if (++writes == 1) throw IOException("synthetic partial write")
                }
            }, sourcesChanged = { changedSessions = it })
            assertFailsWith<IOException> { failing.execute("card", "task") }
            assertEquals(listOf("session"), changedSessions)
            val (cards, chats) = f.reopened()
            assertEquals(2, cards.getById("card")!!.pendingSpeakerRenameTasks.single().rewritePlan!!.size)
            SpeakerTagHistoryService(cards, chats).resumePending()
            assertEquals("<n=\"B\"/>one A B <n=\"NPC\"/>keep", chats.getMessage("first", "session")!!.content)
            assertEquals(listOf("<n=\"A\"/>alternative"), chats.getMessage("first", "session")!!.alternatives)
            assertEquals("<n=\"A\"/>two", chats.getMessage("second", "session")!!.content)
            assertEquals(listOf("<n=\"B\"/>other"), chats.getMessage("second", "session")!!.alternatives)
            assertTrue(cards.getById("card")!!.pendingSpeakerRenameTasks.isEmpty())
            assertEquals("Independent session title", chats.getSession("session")!!.title)
        } finally { f.close() }
    }

    @Test fun `task clear failure cannot replay swapped labels after reopening`() = runTest {
        val f = Fixture()
        try {
            f.seed(); var failed = false
            val service = SpeakerTagHistoryService(f.cards, f.chats, updateTask = { cardId, taskId, transform ->
                val task = f.cards.getById(cardId)!!.pendingSpeakerRenameTasks.single { it.id == taskId }
                if (!failed && transform(task) == null) { failed = true; throw IOException("synthetic task clear") }
                f.cards.mutatePendingSpeakerTask(cardId, taskId, transform)
            })
            assertFailsWith<IOException> { service.execute("card", "task") }
            val (cards, chats) = f.reopened()
            val first = chats.getMessage("first", "session")!!
            val second = chats.getMessage("second", "session")!!
            SpeakerTagHistoryService(cards, chats).resumePending()
            assertEquals(first, chats.getMessage("first", "session"))
            assertEquals(second, chats.getMessage("second", "session"))
            assertTrue(cards.getById("card")!!.pendingSpeakerRenameTasks.isEmpty())
        } finally { f.close() }
    }

    @Test fun `changed source after a prepared task refuses before any further overwrite`() = runTest {
        val f = Fixture()
        try {
            f.seed()
            val service = SpeakerTagHistoryService(f.cards, f.chats, rewrite = { task, persist ->
                f.chats.rewriteSpeakerTagsForTask(task, persist) { throw IOException("synthetic before first write") }
            })
            assertFailsWith<IOException> { service.execute("card", "task") }
            val (cards, chats) = f.reopened()
            val edited = chats.getMessage("second", "session")!!.copy(content = "later user edit", updatedAt = 99)
            chats.updateMessage(edited)
            val savedEdit = chats.getMessage("second", "session")!!
            assertFailsWith<IllegalStateException> { SpeakerTagHistoryService(cards, chats).execute("card", "task") }
            assertEquals(savedEdit, chats.getMessage("second", "session"))
            assertEquals("<n=\"A\"/>one A B <n=\"NPC\"/>keep", chats.getMessage("first", "session")!!.content)
            assertEquals(2, cards.getById("card")!!.pendingSpeakerRenameTasks.single().rewritePlan!!.size)
        } finally { f.close() }
    }

    @Test fun `legacy overlapping pending task cannot guess whether its first labels were already rewritten`() = runTest {
        val f = Fixture()
        try {
            val task = f.seed().copy(rewriteProtocolVersion = null)
            f.cards.save(f.cards.getById("card")!!.copy(pendingSpeakerRenameTasks = listOf(task)))
            val first = f.chats.getMessage("first", "session")!!
            assertFailsWith<IllegalArgumentException> { SpeakerTagHistoryService(f.cards, f.chats).execute("card", "task") }
            assertEquals(first, f.chats.getMessage("first", "session"))
            val retained = f.cards.getById("card")!!.pendingSpeakerRenameTasks.single()
            assertNull(retained.rewritePlan); assertNotNull(retained.lastError)
        } finally { f.close() }
    }

    @Test fun `atomic task metadata retains newer unrelated card and indexing fields`() = runTest {
        val f = Fixture()
        try {
            f.seed(); var changed = false
            val service = SpeakerTagHistoryService(f.cards, f.chats, updateTask = { cardId, taskId, transform ->
                val latest = f.cards.getById(cardId)!!
                if (!changed && transform(latest.pendingSpeakerRenameTasks.single()) == null) {
                    changed = true
                    f.cards.save(latest.copy(basicSetting = "new unrelated setting", ragIndexMessage = "new index progress"))
                }
                f.cards.mutatePendingSpeakerTask(cardId, taskId, transform)
            })
            service.execute("card", "task")
            val card = f.cards.getById("card")!!
            assertEquals("new unrelated setting", card.basicSetting)
            assertEquals("new index progress", card.ragIndexMessage)
            assertTrue(card.pendingSpeakerRenameTasks.isEmpty())
        } finally { f.close() }
    }

    @Test fun `corrupt selected history never disappears from a rename plan and unrelated sessions are not decoded`() = runTest {
        val f = Fixture()
        try {
            f.seed()
            val selected = f.root.resolve("entities/chat_messages/session_first.json")
            val original = Files.readAllBytes(selected)
            Files.writeString(f.root.resolve("entities/chat_messages/unrelated_bad.json"), "{unrelated invalid JSON")
            Files.writeString(selected, "{selected invalid JSON")
            assertFails { SpeakerTagHistoryService(f.cards, f.chats).execute("card", "task") }
            assertEquals("{selected invalid JSON", Files.readString(selected))
            assertNull(f.cards.getById("card")!!.pendingSpeakerRenameTasks.single().rewritePlan)
            Files.write(selected, original)
            SpeakerTagHistoryService(f.cards, f.chats).execute("card", "task")
            assertEquals("{unrelated invalid JSON", Files.readString(f.root.resolve("entities/chat_messages/unrelated_bad.json")))
            assertTrue(f.cards.getById("card")!!.pendingSpeakerRenameTasks.isEmpty())
        } finally { f.close() }
    }

    @Test fun `unknown card fields refuse metadata rewrite before touching history and preserve the original bytes`() = runTest {
        val f = Fixture()
        try {
            f.seed()
            val source = f.root.resolve("entities/character_cards/card.json")
            val original = Json.parseToJsonElement(Files.readString(source)).jsonObject
            val extended = JsonObject(original + ("futureCardField" to JsonPrimitive("retain exactly"))).toString()
            Files.writeString(source, extended)
            val first = f.chats.getMessage("first", "session")!!
            assertFails { SpeakerTagHistoryService(f.cards, f.chats).execute("card", "task") }
            assertEquals(extended, Files.readString(source))
            assertEquals(first, f.chats.getMessage("first", "session"))
            assertNull(f.cards.getById("card")!!.pendingSpeakerRenameTasks.single().rewritePlan)
        } finally { f.close() }
    }

    @Test fun `unknown message fields are not silently removed by a speaker rewrite`() = runTest {
        val f = Fixture()
        try {
            f.seed()
            val source = f.root.resolve("entities/chat_messages/session_first.json")
            val original = Json.parseToJsonElement(Files.readString(source)).jsonObject
            val extended = JsonObject(original + ("futureMessageField" to JsonPrimitive("retain exactly"))).toString()
            Files.writeString(source, extended)
            assertFails { SpeakerTagHistoryService(f.cards, f.chats).execute("card", "task") }
            assertEquals(extended, Files.readString(source))
            assertNull(f.cards.getById("card")!!.pendingSpeakerRenameTasks.single().rewritePlan)
            assertEquals("<n=\"B\"/>two", f.chats.getMessage("second", "session")!!.content)
        } finally { f.close() }
    }

    @Test fun `cancellation after a durable write retains the plan and notifies source invalidation before recovery`() = runTest {
        val f = Fixture()
        try {
            f.seed(); var changedSessions = emptyList<String>()
            val service = SpeakerTagHistoryService(f.cards, f.chats, rewrite = { task, persist ->
                f.chats.rewriteSpeakerTagsForTaskObserved(task, persist) { throw CancellationException("synthetic stop") }
            }, sourcesChanged = { changedSessions = it })
            assertFailsWith<CancellationException> { service.execute("card", "task") }
            assertEquals(listOf("session"), changedSessions)
            assertNotNull(f.cards.getById("card")!!.pendingSpeakerRenameTasks.single().rewritePlan)
            val (cards, chats) = f.reopened()
            SpeakerTagHistoryService(cards, chats).resumePending()
            assertEquals("<n=\"B\"/>one A B <n=\"NPC\"/>keep", chats.getMessage("first", "session")!!.content)
            assertTrue(cards.getById("card")!!.pendingSpeakerRenameTasks.isEmpty())
        } finally { f.close() }
    }
}
