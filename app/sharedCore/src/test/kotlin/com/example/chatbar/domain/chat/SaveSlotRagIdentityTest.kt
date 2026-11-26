package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import kotlin.test.*

class SaveSlotRagIdentityTest {
    @Test fun `portable fixed identities change only id and source and retain original evidence`() {
        val original = VectorChunk("archived", ChunkSourceType.CHAT_MEMORY, "source", messageId = "original-message",
            content = "original body", embedding = listOf(1f, 0f), metadata = mapOf("sessionId" to "source", "sourceTurnId" to "turn", "messageIds" to "original-message"), createdAt = 7)
        val mapped = SaveSlotRagIdentity.restore(original, "foreign")
        assertEquals("36564814-e56d-3722-8d59-bb101ae39b82", mapped.id)
        assertEquals(original.copy(id = mapped.id, sourceId = "foreign"), mapped)
        assertEquals(mapped, SaveSlotRagIdentity.restore(original, "foreign"))
        assertNotEquals(mapped.id, SaveSlotRagIdentity.restore(original, "other").id)
        val refs = SessionCopyPolicy("foreign", setOf("original-message")).chunk(mapped)
        assertEquals(mapped.id, refs.id); assertEquals("foreign-0000000000", refs.messageId)
    }
    @Test fun `UTF8 identity is fixed and blank identity is refused without new provenance`() {
        val original = VectorChunk("旧块", ChunkSourceType.CHAT_MEMORY, "source", content = "body", embedding = emptyList(), createdAt = 1)
        assertEquals("0684e372-77fb-30f0-8605-f12d61e74ebb", SaveSlotRagIdentity.restore(original, "目标").id)
        val opaque = SaveSlotRagIdentity.restore(original.copy(id = "../logical-id"), "target")
        assertTrue(opaque.id.matches(Regex("[0-9a-f-]{36}")))
        assertEquals(original.content, opaque.content)
        assertFails { SaveSlotRagIdentity.restore(original, " ") }
        assertFails { SaveSlotRagIdentity.restore(original.copy(id = ""), "target") }
    }
}
