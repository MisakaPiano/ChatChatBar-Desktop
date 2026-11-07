package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.SessionPlayerRole
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMergeNamedAdoptionTest {
    private fun card(id: String, name: String) = "entities/character_cards/$id.json" to
        backupJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, name, greeting = "Synthetic greeting", createdAt = 1, updatedAt = 1)).jsonObject
    private fun session(id: String, card: String, title: String) = "entities/chat_sessions/$id.json" to
        backupJson.encodeToJsonElement(ChatSession.serializer(), ChatSession(id, card, title, createdAt = 1, updatedAt = 1)).jsonObject

    @Test fun `adopted and kept named cards preserve explicit person bindings without rewriting names`() {
        val oldPerson = CharacterInfo.create("Alice").copy(id = "pc-person")
        val incomingPerson = oldPerson.copy(id = "phone-person", name = "ＡLICE")
        val oldCard = CharacterCard("a", "Shared", characters = listOf(oldPerson), createdAt = 1, updatedAt = 1)
        val incomingCard = oldCard.copy(id = "b", name = "SHARED", characters = listOf(incomingPerson))
        val oldSession = ChatSession("s", oldCard.id, "Retained", desktopPlayerRole = SessionPlayerRole(oldCard.id, oldPerson.id), createdAt = 1, updatedAt = 1)
        val incomingSession = oldSession.copy(id = "t", characterCardId = incomingCard.id, title = "Incoming",
            desktopPlayerRole = SessionPlayerRole(incomingCard.id, incomingPerson.id))
        val pc = mapOf("entities/character_cards/a.json" to backupJson.encodeToJsonElement(CharacterCard.serializer(), oldCard).jsonObject,
            "entities/chat_sessions/s.json" to backupJson.encodeToJsonElement(ChatSession.serializer(), oldSession).jsonObject)
        val phone = mapOf("entities/character_cards/b.json" to backupJson.encodeToJsonElement(CharacterCard.serializer(), incomingCard).jsonObject,
            "entities/chat_sessions/t.json" to backupJson.encodeToJsonElement(ChatSession.serializer(), incomingSession).jsonObject)
        for (choice in listOf(DesktopMergePreview.Choice.TAKE_PHONE, DesktopMergePreview.Choice.KEEP_PC, DesktopMergePreview.Choice.SKIP)) {
            val preview = DesktopMergePreview.create(pc, phone, emptyMap(), emptyMap())
            DesktopMergeDestinationPlan.create(preview.select(mapOf(preview.conflicts.single().key to choice)), emptyMap(), emptyMap()).use { plan ->
                val card = plan.entities.filterKeys { it.startsWith("entities/character_cards/") }.values.single()
                val person = card.getValue("characters").jsonArray.single().jsonObject
                assertEquals("pc-person", person.getValue("id").jsonPrimitive.content)
                assertEquals(if (choice == DesktopMergePreview.Choice.TAKE_PHONE) "ＡLICE" else "Alice", person.getValue("name").jsonPrimitive.content)
                for (session in plan.entities.filterKeys { it.startsWith("entities/chat_sessions/") }.values) {
                    val role = session.getValue("desktopPlayerRole").jsonObject
                    assertEquals("a", role.getValue("cardId").jsonPrimitive.content)
                    assertEquals("pc-person", role.getValue("personId").jsonPrimitive.content)
                }
            }
        }
        assertEquals("phone-person", phone.getValue("entities/character_cards/b.json").getValue("characters").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test fun `named conversation replacement keeps omitted images with their mapped owner`() {
        fun message(session: String, text: String, images: List<String>) = "entities/chat_messages/${session}_m.json" to
            buildJsonObject { put("id", "m"); put("sessionId", session); put("role", "ASSISTANT"); put("content", text)
                put("images", JsonArray(images.map(::JsonPrimitive))); put("createdAt", 1); put("updatedAt", 1) }
        val pc = mapOf(card("c", "Card"), session("s", "c", "Same title"), message("s", "Old text", listOf(DesktopBackupPolicy.PREFIX + "images/old.png")))
        val phone = mapOf(card("c", "Card"), session("t", "c", "Same title"), message("t", "New text", emptyList()))
        val scope = InspectionScope(emptySet(), 1, 1, 0, 0, 0, false, false, false, false,
            sessionChoices = mapOf("t" to InspectionSessionChoice(images = false, voice = false, archives = false)))
        val media = mapOf("images/old.png" to "a".repeat(64))
        val preview = DesktopMergePreview.create(pc, phone, media, emptyMap(), scope)
        val conflict = preview.conflicts.single()
        assertTrue(DesktopMergePreview.Choice.TAKE_PHONE in conflict.choices)
        DesktopMergeDestinationPlan.create(preview.select(mapOf(conflict.key to DesktopMergePreview.Choice.TAKE_PHONE)), media, emptyMap()).use { plan ->
            val message = plan.entities.filterKeys { it.startsWith("entities/chat_messages/") }.values.single()
            assertEquals("s", message.getValue("sessionId").jsonPrimitive.content)
            assertEquals("New text", message.getValue("content").jsonPrimitive.content)
            val reference = message.getValue("images").jsonArray.single().jsonPrimitive.content.removePrefix(DesktopBackupPolicy.PREFIX)
            assertEquals("images/old.png", plan.resources.getValue(reference).original)
            assertEquals(DesktopMergeDestinationPlan.Side.COMPUTER, plan.resources.getValue(reference).side)
        }
    }

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

        val exactPc = mapOf(card("unrelated", "Shared"), card("target", "Shared"), session("kept", "target", "Kept"))
        val exactPhone = mapOf(card("target", "SHARED"))
        val exactPreview = DesktopMergePreview.create(exactPc, exactPhone, emptyMap(), emptyMap())
        val exactConflict = exactPreview.conflicts.single()
        DesktopMergeDestinationPlan.create(exactPreview.select(mapOf(exactConflict.key to DesktopMergePreview.Choice.TAKE_PHONE)),
            emptyMap(), emptyMap()).use { plan ->
            assertEquals(setOf("unrelated", "target"), plan.entities.filterKeys { it.startsWith("entities/character_cards/") }
                .values.map { it.getValue("id").jsonPrimitive.content }.toSet())
        }
    }

    @Test fun `card choices derive session conflicts and preserve unaffected explicit choices without rebinding unrelated owners`() {
        val pc = mapOf(card("a", "Shared"), card("c", "Other"), session("s", "a", "Same title"))
        val phone = mapOf(card("b", "SHARED"), card("d", "OTHER"), session("t", "b", "Same title"))
        val preview = DesktopMergePreview.create(pc, phone, emptyMap(), emptyMap())
        assertEquals(2, preview.conflicts.size)
        assertTrue(preview.conflicts.all { it.category == "character_cards" })
        val cardKey = preview.conflicts.single { it.label == "SHARED" }.key
        val independentKey = preview.conflicts.single { it.label == "OTHER" }.key
        var choices = preview.choose(emptyMap(), independentKey, DesktopMergePreview.Choice.KEEP_PC)
        choices = preview.choose(choices, cardKey, DesktopMergePreview.Choice.TAKE_PHONE)
        assertEquals(DesktopMergePreview.Choice.KEEP_PC, choices[independentKey])
        val sessionConflict = preview.effectiveConflicts(choices).single { it.category == "chat_sessions" }
        assertFails { preview.select(choices) }
        choices = preview.choose(choices, sessionConflict.key, DesktopMergePreview.Choice.TAKE_PHONE)
        DesktopMergeDestinationPlan.create(preview.select(choices), emptyMap(), emptyMap()).use { plan ->
            val session = plan.entities.filterKeys { it.startsWith("entities/chat_sessions/") }.values.single()
            assertEquals("s", session.getValue("id").jsonPrimitive.content)
            assertEquals("a", session.getValue("characterCardId").jsonPrimitive.content)
        }
        choices = preview.choose(choices, cardKey, DesktopMergePreview.Choice.BOTH)
        assertFalse(sessionConflict.key in choices)
        assertEquals(DesktopMergePreview.Choice.KEEP_PC, choices[independentKey])
        assertTrue(preview.effectiveConflicts(choices).none { it.category == "chat_sessions" })
        DesktopMergeDestinationPlan.create(preview.select(choices), emptyMap(), emptyMap()).use { plan ->
            val sessions = plan.entities.filterKeys { it.startsWith("entities/chat_sessions/") }.values
            assertEquals(setOf("a", "b"), sessions.map { it.getValue("characterCardId").jsonPrimitive.content }.toSet())
        }
        choices = preview.choose(choices, cardKey, DesktopMergePreview.Choice.KEEP_PC)
        assertFalse(sessionConflict.key in choices)
        choices = preview.choose(choices, sessionConflict.key, DesktopMergePreview.Choice.BOTH)
        DesktopMergeDestinationPlan.create(preview.select(choices), emptyMap(), emptyMap()).use { plan ->
            val sessions = plan.entities.filterKeys { it.startsWith("entities/chat_sessions/") }.values
            assertEquals(2, sessions.size)
            assertTrue(sessions.all { it.getValue("characterCardId").jsonPrimitive.content == "a" })
        }
        assertEquals("SHARED", phone.getValue("entities/character_cards/b.json").getValue("name").jsonPrimitive.content)
    }
}
