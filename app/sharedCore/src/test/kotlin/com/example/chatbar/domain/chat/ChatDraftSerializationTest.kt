package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatDraft
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.Test

class ChatDraftSerializationTest {
    @Test
    fun `draft round trips by session id`() {
        val draft = ChatDraft(
            sessionId = "session-1",
            content = "half typed message",
            updatedAt = 200
        )

        val encoded = Json.encodeToString(ChatDraft.serializer(), draft)
        val decoded = Json.decodeFromString(ChatDraft.serializer(), encoded)

        assertEquals("session-1", decoded.sessionId)
        assertEquals("half typed message", decoded.content)
        assertEquals(200, decoded.updatedAt)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun `empty images keep legacy JSON and nonempty images survive copy and round trip`() {
        val json = Json { encodeDefaults = true }
        val plain = ChatDraft("s", "body", 7)
        val old = """{"sessionId":"s","content":"body","updatedAt":7}"""
        assertEquals(old, json.encodeToString(ChatDraft.serializer(), plain))
        assertEquals(emptyList(), json.decodeFromString(ChatDraft.serializer(), old).images)
        val image = ChatDraft("s", "", 7, listOf("images/a.png", "images/b.png"))
        assertEquals(image, json.decodeFromString(ChatDraft.serializer(), json.encodeToString(ChatDraft.serializer(), image)))
        assertEquals(image.images, image.copy(content = "new text").images)
    }
}
