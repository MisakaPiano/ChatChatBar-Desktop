package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatSession
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionTitleRenamePolicyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `ordinary rename retains String replace behavior`() {
        assertEquals("New and New", SessionTitleRenamePolicy.rewrite("Old and Old", "Old", "New"))
    }

    @Test fun `substring rename preserves completed spans and is idempotent`() {
        listOf(
            "Al chat" to "Alice chat",
            "Alice chat" to "Alice chat",
            "Alice meets Al" to "Alice meets Alice",
            "bba" to "baba",
        ).forEach { (title, expected) ->
            val old = if (title == "bba") "ba" else "Al"
            val new = if (title == "bba") "aba" else "Alice"
            val once = SessionTitleRenamePolicy.rewrite(title, old, new)
            assertEquals(expected, once)
            assertEquals(once, SessionTitleRenamePolicy.rewrite(once, old, new))
        }
    }

    @Test fun `partial multi-session rename can retry without duplicating new name`() = runTest {
        val root = temp.newFolder().toPath()
        val repository = ChatRepository(JsonFileStorage(root))
        val first = repository.createSession(ChatSession.create("card", "Al chat"))
        val second = repository.createSession(ChatSession.create("card", "Al again"))
        val unrelated = repository.createSession(ChatSession.create("other", "Al other"))
        var writes = 0
        assertFailsWith<IllegalStateException> {
            repository.rewriteSessionTitlesForCharacterCard("card", "Al", "Alice") { session ->
                writes++
                if (writes == 2) error("second session write unavailable")
                repository.updateSession(session)
            }
        }
        assertEquals(2, writes)
        assertEquals(1, listOf(first, second).count {
            repository.getSession(it.id)?.title?.startsWith("Alice") == true
        })
        assertEquals(1, repository.rewriteSessionTitlesForCharacterCard("card", "Al", "Alice"))
        assertEquals("Alice chat", repository.getSession(first.id)?.title)
        assertEquals("Alice again", repository.getSession(second.id)?.title)
        assertEquals("Al other", repository.getSession(unrelated.id)?.title)
        assertEquals(0, repository.rewriteSessionTitlesForCharacterCard("card", "Al", "Alice"))
    }
}
