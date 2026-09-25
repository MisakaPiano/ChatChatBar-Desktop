package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatScrollPosition
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ChatRepositoryFoundationTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        roots.asReversed().forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        roots.clear()
    }

    @Test
    fun `session CRUD pin draft and scroll state remain repository backed`() = runTest {
        val repository = repository()
        val session = session("session")

        repository.createSession(session)
        repository.pinSession(session.id)
        repository.updateSessionDraft(session.id, "draft")
        repository.updateScrollPosition(
            ChatScrollPosition(session.id, anchorMessageId = "m1", scrollOffset = 12, capturedAt = 2)
        )

        assertTrue(requireNotNull(repository.getSession(session.id)).isPinned)
        assertEquals(listOf(session.id), repository.observePinnedSessions().first().map { it.id })
        assertEquals("draft", repository.getSessionDraft(session.id))
        assertEquals("m1", repository.getScrollPosition(session.id)?.anchorMessageId)

        repository.unpinSession(session.id)
        repository.deleteSession(session.id)

        assertNull(repository.getSession(session.id))
        assertEquals("", repository.getSessionDraft(session.id))
        assertNull(repository.getScrollPosition(session.id))
    }

    @Test
    fun `message append update delete and preview semantics remain stable`() = runTest {
        val repository = repository()
        repository.createSession(session("session"))

        val user = repository.addMessage(message("user", MessageRole.USER, 1, "hello"))
        val assistant = repository.addMessage(message("assistant", MessageRole.ASSISTANT, 2, "reply"))
        assertNotNull(user.sourceTurnId)
        assertEquals(user.sourceTurnId, assistant.sourceTurnId)
        assertEquals("reply", repository.getSession("session")?.lastMessagePreview)

        repository.updateMessage(assistant.copy(content = "edited"))
        assertEquals("edited", repository.getMessage("assistant", "session")?.content)
        assertEquals("edited", repository.getSession("session")?.lastMessagePreview)

        repository.deleteMessage("assistant", "session")
        assertEquals(listOf("user"), repository.getMessages("session").map { it.id })
        assertEquals("hello", repository.getSession("session")?.lastMessagePreview)
    }

    @Test
    fun `generated message insertion preserves anchor turn and timeline ordering`() = runTest {
        val repository = repository()
        repository.createSession(session("session"))
        val user = repository.addMessage(message("user", MessageRole.USER, 1, "u"))
        repository.addMessage(message("assistant", MessageRole.ASSISTANT, 3, "a"))

        val image = repository.addMessageAfter(
            message("image", MessageRole.ASSISTANT, 2, "image"),
            anchorMessageId = user.id
        )

        assertEquals(listOf("user", "image", "assistant"), repository.getMessages("session").map { it.id })
        assertEquals(user.sourceTurnId, image.sourceTurnId)
        assertEquals(user.id, image.generatedFromMessageId)
    }

    @Test
    fun `index paging and source turn helpers use persisted lightweight index`() = runTest {
        val root = newRoot()
        val repository = ChatRepository(JsonFileStorage(root))
        repository.createSession(session("session"))
        val messages = (1..85).flatMap { turn ->
            val sourceId = "turn-$turn"
            listOf(
                message("u-$turn", MessageRole.USER, turn * 2L, "u$turn", sourceId, turn.toLong()),
                message("a-$turn", MessageRole.ASSISTANT, turn * 2L + 1, "a$turn", sourceId, turn.toLong())
            )
        }
        repository.replaceMessagesForSession("session", messages)

        val initial = repository.getInitialMessagePage("session")
        assertEquals(160, initial.messages.size)
        assertTrue(initial.hasOlder)
        val older = repository.getOlderMessagePage("session", initial.messages.first().id)
        assertEquals(10, older.messages.size)
        assertEquals(85, repository.getMessageTurnCount("session"))
        assertEquals(setOf("u-1", "a-1"), repository.getMessagesForSourceTurn("session", "turn-1").map { it.id }.toSet())
        assertEquals("u-1", repository.getFirstMessageIdForSourceTurn("session", "turn-1"))

        val reopened = ChatRepository(JsonFileStorage(root))
        assertEquals(messages.map { it.id }.toSet(), reopened.getMessageIds("session"))
        assertEquals(listOf("u-85", "a-85"), reopened.getRecentMessages("session", 2).map { it.id })
    }

    @Test
    fun `replace normalizes session ids and clears obsolete messages and scroll`() = runTest {
        val repository = repository()
        repository.createSession(session("session"))
        repository.addMessage(message("old", MessageRole.USER, 1, "old"))
        repository.updateScrollPosition(ChatScrollPosition("session", "old", capturedAt = 1))

        repository.replaceMessagesForSession(
            "session",
            listOf(message("new", MessageRole.USER, 2, "new").copy(sessionId = "foreign"))
        )

        assertEquals(listOf("new"), repository.getMessages("session").map { it.id })
        assertEquals("session", repository.getMessage("new", "session")?.sessionId)
        assertNull(repository.getMessage("old", "session"))
        assertNull(repository.getScrollPosition("session"))
    }

    @Test
    fun `legacy messages lazily receive stable source turns and deletion records tombstone`() = runTest {
        val repository = repository()
        repository.createSession(session("session"))
        repository.replaceMessagesForSession(
            "session",
            listOf(
                message("user", MessageRole.USER, 1, "u").copy(timelineTurn = 7),
                message("assistant", MessageRole.ASSISTANT, 2, "a").copy(timelineTurn = 7)
            )
        )

        val migrated = repository.ensureSourceTurns("session")
        assertTrue(migrated.all { it.sourceTurnId != null && it.sourceTurnOrder != null })
        assertEquals(migrated[0].sourceTurnId, migrated[1].sourceTurnId)

        repository.deleteMessage("user", "session")
        assertTrue(repository.getSession("session")!!.sourceTurnTombstones.isEmpty())
        repository.deleteMessage("assistant", "session")
        val tombstones = repository.getSession("session")!!.sourceTurnTombstones
        assertEquals(1, tombstones.size)
        assertEquals(migrated[0].sourceTurnId, tombstones.single().sourceTurnId)
    }

    @Test
    fun `pre extraction Android JSON remains readable and semantically stable after rewrite`() = runTest {
        val root = newRoot()
        val sessionsDir = root.resolve("entities/chat_sessions").createDirectories()
        val messagesDir = root.resolve("entities/chat_messages").createDirectories()
        sessionsDir.resolve("android-session.json").writeText(
            """{"id":"android-session","characterCardId":"card","title":"Android fixture","modelId":"model","replyLength":900,"longTermMemory":"memory","nextTimelineTurn":8,"timelineTombstones":[3],"createdAt":11,"updatedAt":12}"""
        )
        messagesDir.resolve("android-session_android-user.json").writeText(
            """{"id":"android-user","sessionId":"android-session","role":"USER","content":"fixture body","images":["images/a.png"],"alternatives":["alt"],"currentAlternativeIndex":0,"createdAt":21,"updatedAt":22,"orderKey":1000000,"timelineTurn":7}"""
        )

        val repository = ChatRepository(JsonFileStorage(root))
        val decodedSession = requireNotNull(repository.getSession("android-session"))
        val decodedMessage = repository.getMessages("android-session").single()
        assertEquals(900, decodedSession.replyLength)
        assertEquals(setOf(3L), decodedSession.timelineTombstones)
        assertEquals(listOf("images/a.png"), decodedMessage.images)
        assertEquals("alt", decodedMessage.displayContent)

        repository.updateSession(decodedSession)
        repository.updateMessage(decodedMessage)
        val reopened = ChatRepository(JsonFileStorage(root))
        val rewrittenSession = requireNotNull(reopened.getSession("android-session"))
        val rewrittenMessage = reopened.getMessages("android-session").single()
        assertEquals(
            decodedSession.copy(
                updatedAt = rewrittenSession.updatedAt,
                lastMessagePreview = "alt",
                lastMessageTime = 21,
                lastMessageRole = MessageRole.USER
            ),
            rewrittenSession
        )
        assertEquals(decodedMessage.copy(updatedAt = rewrittenMessage.updatedAt), rewrittenMessage)
        assertFalse(rewrittenSession.isPinned)
    }

    private fun repository(): ChatRepository = ChatRepository(JsonFileStorage(newRoot()))

    private fun newRoot(): Path = Files.createTempDirectory("chat-repository-foundation-").also(roots::add)

    private fun session(id: String) = ChatSession(
        id = id,
        characterCardId = "card",
        title = "title",
        createdAt = 1,
        updatedAt = 1
    )

    private fun message(
        id: String,
        role: MessageRole,
        createdAt: Long,
        content: String,
        sourceTurnId: String? = null,
        sourceTurnOrder: Long? = null
    ) = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = content,
        createdAt = createdAt,
        updatedAt = createdAt,
        orderKey = createdAt * 1_000_000,
        sourceTurnId = sourceTurnId,
        sourceTurnOrder = sourceTurnOrder,
        timelineTurn = sourceTurnOrder
    )
}
