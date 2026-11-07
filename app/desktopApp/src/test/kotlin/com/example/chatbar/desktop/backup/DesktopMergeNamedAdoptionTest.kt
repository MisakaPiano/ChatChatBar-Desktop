package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMergeNamedAdoptionTest {
    private fun card(id: String, name: String) = "entities/character_cards/$id.json" to
        backupJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, name, greeting = "Synthetic greeting", createdAt = 1, updatedAt = 1)).jsonObject
    private fun session(id: String, card: String, title: String) = "entities/chat_sessions/$id.json" to
        backupJson.encodeToJsonElement(ChatSession.serializer(), ChatSession(id, card, title, createdAt = 1, updatedAt = 1)).jsonObject

    @Test fun `unique named adoption preserves retained and incoming card bindings while owner and ambiguity boundaries remain explicit`() {
        val pc = mapOf(card("pc-card", "Shared"), session("kept", "pc-card", "Retained conversation"))
        val phone = mapOf(card("phone-card", " shared "), session("incoming", "phone-card", "Incoming conversation"))
        val pcOriginal = pc.toString(); val phoneOriginal = phone.toString()
        val preview = DesktopMergePreview.create(pc, phone, emptyMap(), emptyMap())
        val conflict = preview.conflicts.single()
        assertEquals("character_cards", conflict.category)
        assertTrue(DesktopMergePreview.Choice.TAKE_PHONE in conflict.choices)
        val chosen = preview.select(mapOf(conflict.key to DesktopMergePreview.Choice.TAKE_PHONE))
        DesktopMergeDestinationPlan.create(chosen, emptyMap(), emptyMap()).use { plan ->
            val adopted = plan.entities.filterKeys { it.startsWith("entities/character_cards/") }.values.single()
            assertEquals("pc-card", adopted.getValue("id").jsonPrimitive.content)
            assertEquals(" shared ", adopted.getValue("name").jsonPrimitive.content)
            val sessions = plan.entities.filterKeys { it.startsWith("entities/chat_sessions/") }.values
            assertEquals(setOf("kept", "incoming"), sessions.map { it.getValue("id").jsonPrimitive.content }.toSet())
            assertTrue(sessions.all { it.getValue("characterCardId").jsonPrimitive.content == "pc-card" })
        }
        assertEquals(pcOriginal, pc.toString()); assertEquals(phoneOriginal, phone.toString())

        val unrelatedPc = mapOf(card("a", "Card A"), session("pc-session", "a", "New conversation"))
        val unrelatedPhone = mapOf(card("b", "Card B"), session("phone-session", "b", "New conversation"))
        assertTrue(DesktopMergePreview.create(unrelatedPc, unrelatedPhone, emptyMap(), emptyMap()).conflicts.isEmpty())
        val sameId = DesktopMergePreview.create(unrelatedPc,
            mapOf(card("b", "Card B"), session("pc-session", "b", "Different title")), emptyMap(), emptyMap())
        assertTrue(DesktopMergePreview.Reason.SAME_ID_DIFFERENT_CONTENT in sameId.conflicts.single().reasons)

        val ambiguous = DesktopMergePreview.create(mapOf(card("a", "Alice"), card("b", "ＡLICE")),
            mapOf(card("incoming", " alice ")), emptyMap(), emptyMap())
        val ambiguousConflict = ambiguous.conflicts.single()
        assertFalse(DesktopMergePreview.Choice.TAKE_PHONE in ambiguousConflict.choices)
        val copies = ambiguous.select(mapOf(ambiguousConflict.key to DesktopMergePreview.Choice.BOTH))
        DesktopMergeDestinationPlan.create(copies, emptyMap(), emptyMap()).use { plan ->
            assertEquals(3, plan.entities.keys.count { it.startsWith("entities/character_cards/") })
        }

        val doubleAdoption = DesktopMergePreview.create(mapOf(card("target", "Shared")),
            mapOf(card("first", "Shared"), card("second", "SHARED")), emptyMap(), emptyMap())
        val doubleChoice = doubleAdoption.conflicts.associate { it.key to DesktopMergePreview.Choice.TAKE_PHONE }
        assertFails { DesktopMergeDestinationPlan.create(doubleAdoption.select(doubleChoice), emptyMap(), emptyMap()).close() }
    }
}
