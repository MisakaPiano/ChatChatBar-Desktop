package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ChatContextCandidateSnapshotTest {
    @Test fun `pinned old turns do not masquerade as a complete recent suffix and read only query creates no index`() = runBlocking {
        val root = Files.createTempDirectory("candidate-source-proof-")
        try {
            val chats = ChatRepository(JsonFileStorage(root))
            chats.createSession(ChatSession("s", "card", "synthetic", createdAt = 1, updatedAt = 1))
            repeat(10) { n ->
                for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                    val order = n * 2L + if (role == MessageRole.USER) 1 else 2
                    chats.addMessage(ChatMessage("$n-${role.name}", "s", role, "synthetic body $n", createdAt = order,
                        updatedAt = order, sourceTurnId = "turn-$n", sourceTurnOrder = n.toLong()))
                }
            }
            val normal = chats.getContextCandidateSnapshot("s", 2, setOf("turn-0"))
            assertTrue(normal.messages.any { it.id == "0-ASSISTANT" })
            assertFalse("0-ASSISTANT" in normal.completeRecentMessageIds)
            assertEquals(setOf("8-USER", "8-ASSISTANT", "9-USER", "9-ASSISTANT"), normal.completeRecentMessageIds)
            val index = root.resolve("entities/chat_message_indexes/s.json")
            Files.delete(index)
            val readOnly = chats.getContextCandidateSnapshotReadOnly("s", 2, setOf("turn-0"))
            assertEquals(normal, readOnly)
            assertFalse(Files.exists(index))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `read only source proof refuses an unreadable transcript instead of proving a partial timeline`() = runBlocking {
        val root = Files.createTempDirectory("candidate-source-unknown-")
        try {
            val storage = JsonFileStorage(root); val chats = ChatRepository(storage)
            chats.createSession(ChatSession("s", "card", "synthetic", createdAt = 1, updatedAt = 1))
            chats.addMessage(ChatMessage("a", "s", MessageRole.ASSISTANT, "known", createdAt = 1, updatedAt = 1))
            val bad = root.resolve("entities/chat_messages/s_unknown.json")
            Files.writeString(bad, "{not valid JSON")
            assertFails { chats.getContextCandidateSnapshotReadOnly("s", 2) }
            assertEquals("{not valid JSON", Files.readString(bad))
        } finally { root.toFile().deleteRecursively() }
    }
}
