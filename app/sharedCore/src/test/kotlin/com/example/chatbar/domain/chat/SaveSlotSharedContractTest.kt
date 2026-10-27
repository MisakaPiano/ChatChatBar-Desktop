package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class SaveSlotSharedContractTest {
    private val json = Json { encodeDefaults = true }
    @Test fun `original legacy defaults and serializer owner remain unchanged`() {
        val raw = """{"id":"slot","sessionId":"s","name":"Synthetic","createdAt":1}"""
        val slot = json.decodeFromString(SaveSlot.serializer(), raw)
        assertEquals(4, slot.schemaVersion); assertEquals(300, slot.replyLength); assertEquals(7, SaveSlot.create("s", "Synthetic").schemaVersion)
        assertEquals("com.example.chatbar.data.local.entity.SaveSlot", SaveSlot.serializer().descriptor.serialName)
        assertNull(slot.packageRef); assertEquals(slot, json.decodeFromString(SaveSlot.serializer(), json.encodeToString(SaveSlot.serializer(), slot)))
    }
    @Test fun `v8 reference and current candidates serialize through the same authority`() {
        val message = ChatMessage("m", "s", MessageRole.ASSISTANT, "selected", alternatives = listOf("old", "selected"),
            alternativeVersionIds = listOf("a", "b"), currentAlternativeVersionId = "b", currentAlternativeIndex = 1, createdAt = 1, updatedAt = 1)
        val slot = SaveSlot(schemaVersion = 8, id = "slot", sessionId = "s", name = "Synthetic", messages = listOf(message),
            packageRef = SaveSlotPackageRef("slot.cbsave", messageCount = 1), createdAt = 1)
        assertEquals(slot, json.decodeFromString(SaveSlot.serializer(), json.encodeToString(SaveSlot.serializer(), slot)))
    }
}
