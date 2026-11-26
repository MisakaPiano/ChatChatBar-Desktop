package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ChatDraftImagesTest {
    @Test fun `image only draft survives cold repository and legacy text update preserves attachment order`() = runBlocking {
        val root = Files.createTempDirectory("synthetic-image-draft-")
        try {
            val repo = ChatRepository(JsonFileStorage(root))
            repo.updateSessionDraftSnapshot("s", "", listOf("images/b.png", "images/a.png"))
            val cold = ChatRepository(JsonFileStorage(root))
            assertEquals(listOf("images/b.png", "images/a.png"), cold.getSessionDraftSnapshot("s")!!.images)
            cold.updateSessionDraft("s", "text")
            assertEquals(listOf("images/b.png", "images/a.png"), cold.getSessionDraftSnapshot("s")!!.images)
            cold.updateSessionDraftSnapshot("s", "text", emptyList())
            assertEquals(emptyList(), cold.getSessionDraftSnapshot("s")!!.images)
            cold.updateSessionDraftSnapshot("s", "", emptyList())
            assertNull(cold.getSessionDraftSnapshot("s"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `corrupt or foreign draft read and replacement refuse without overwriting original`() = runBlocking {
        for (raw in listOf("broken", """{"sessionId":"foreign","content":"keep","updatedAt":1}""")) {
            val root = Files.createTempDirectory("synthetic-refused-draft-")
            try {
                val path = root.resolve("entities/chat_drafts/s.json"); Files.createDirectories(path.parent); Files.writeString(path, raw)
                val repo = ChatRepository(JsonFileStorage(root))
                assertFails { repo.getSessionDraftSnapshot("s") }
                assertFails { repo.updateSessionDraftSnapshot("s", "replacement", listOf("images/a.png")) }
                assertEquals(raw, Files.readString(path))
            } finally { root.toFile().deleteRecursively() }
        }
    }
}
