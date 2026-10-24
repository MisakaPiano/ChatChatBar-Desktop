package com.example.chatbar.desktop.backup

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopMergePreviewTest {
    private fun obj(value: String) = backupJson.parseToJsonElement(value).jsonObject
    private fun card(id: String, name: String, extra: String = "") = "entities/character_cards/$id.json" to obj("""{"id":"$id","name":"$name"$extra}""")
    private fun session(id: String, message: String, text: String) = mapOf(
        "entities/chat_sessions/$id.json" to obj("""{"id":"$id","characterCardId":"c","title":"Synthetic $id"}"""),
        "entities/chat_messages/${id}_$message.json" to obj("""{"id":"$message","sessionId":"$id","content":"$text","createdAt":1,"updatedAt":1}"""))
    private fun preview(pc: Map<String, JsonObject>, phone: Map<String, JsonObject>) = DesktopMergePreview.create(pc, phone, emptyMap(), emptyMap())

    @Test fun bothUniqueSidesSurviveWithoutAnyConflictOrSourceMutation() {
        val pc = mapOf(card("pc", "Computer")); val phone = mapOf(card("phone", "Phone"))
        val plan = preview(pc, phone)
        val result = plan.select(emptyMap())
        assertEquals(1, result.computer.size); assertEquals(1, result.phone.size)
        assertTrue(result.forkIncomingUnits.isEmpty())
        assertEquals("pc", pc.values.single().getValue("id").jsonPrimitive.content)
        assertEquals("phone", phone.values.single().getValue("id").jsonPrimitive.content)
    }

    @Test fun identicalInputIsNotAddedTwiceAndObjectOrderDoesNotChangeItsIdentity() {
        val pc = mapOf(card("c", "Synthetic"))
        val phone = mapOf("entities/character_cards/c.json" to obj("""{"name":"Synthetic","id":"c"}"""))
        val plan = preview(pc, phone)
        assertTrue(plan.conflicts.isEmpty())
        assertTrue(plan.select(emptyMap()).phone.isEmpty())
        assertEquals(plan.pcFingerprint, preview(phone, emptyMap()).pcFingerprint)
    }

    @Test fun sameIdDifferentContentRequiresExplicitApplicableChoiceAndNeverLastWriteWins() {
        val plan = preview(mapOf(card("c", "Computer")), mapOf(card("c", "Phone")))
        val conflict = plan.conflicts.single()
        assertTrue(DesktopMergePreview.Reason.SAME_ID_DIFFERENT_CONTENT in conflict.reasons)
        assertFails { plan.select(emptyMap()) }
        assertFails { plan.select(mapOf("forged" to DesktopMergePreview.Choice.TAKE_PHONE)) }
        for (choice in listOf(DesktopMergePreview.Choice.KEEP_PC, DesktopMergePreview.Choice.SKIP)) {
            val result = plan.select(mapOf(conflict.key to choice))
            assertEquals(1, result.computer.size); assertTrue(result.phone.isEmpty())
        }
        val take = plan.select(mapOf(conflict.key to DesktopMergePreview.Choice.TAKE_PHONE))
        assertTrue(take.computer.isEmpty()); assertEquals(1, take.phone.size)
        val both = plan.select(mapOf(conflict.key to DesktopMergePreview.Choice.BOTH))
        assertEquals(setOf(conflict.key), both.forkIncomingUnits)
        assertEquals(1, both.computer.size); assertEquals(1, both.phone.size)
    }

    @Test fun sameNameDifferentIdentityIsVisibleAndKeepingBothDoesNotRequirePromptOrNameEdits() {
        val plan = preview(mapOf(card("a", "Same name")), mapOf(card("b", "Same name")))
        val conflict = plan.conflicts.single()
        assertEquals(setOf(DesktopMergePreview.Reason.SAME_NAME), conflict.reasons)
        val both = plan.select(mapOf(conflict.key to DesktopMergePreview.Choice.BOTH))
        assertTrue(both.forkIncomingUnits.isEmpty())
        assertEquals("Same name", both.phone.single().records.values.single().getValue("name").jsonPrimitive.content)
    }

    @Test fun forkedConversationsAreWholeBundlesRatherThanTimestampSplices() {
        val pc = session("s", "m", "Computer branch")
        val phone = session("s", "m", "Phone branch") + ("entities/chat_drafts/s.json" to obj("""{"sessionId":"s","content":"Unsent"}"""))
        val plan = preview(pc, phone); val conflict = plan.conflicts.single()
        assertEquals("chat_sessions", conflict.category)
        val result = plan.select(mapOf(conflict.key to DesktopMergePreview.Choice.BOTH))
        assertEquals(2, result.computer.single().records.size)
        assertEquals(3, result.phone.single().records.size)
        assertEquals(setOf("session:s"), result.forkIncomingUnits)
        assertEquals("Computer branch", result.computer.single().records.getValue("entities/chat_messages/s_m.json")["content"]?.jsonPrimitive?.content)
    }

    @Test fun messageIdCollisionAcrossDifferentSessionsRequiresRemapAndCannotDeleteTheOtherConversation() {
        val plan = preview(session("a", "shared-message", "A"), session("b", "shared-message", "B"))
        val conflict = plan.conflicts.single()
        assertTrue(DesktopMergePreview.Reason.OWNED_ID_COLLISION in conflict.reasons)
        assertFalse(DesktopMergePreview.Choice.TAKE_PHONE in conflict.choices)
        assertFails { plan.select(mapOf(conflict.key to DesktopMergePreview.Choice.TAKE_PHONE)) }
        assertEquals(setOf("session:b"), plan.select(mapOf(conflict.key to DesktopMergePreview.Choice.BOTH)).forkIncomingUnits)
    }

    @Test fun sameModelIdDifferentEndpointIsAnAccountConflict() {
        val path = "entities/model_configs/m.json"
        val pc = obj(CensusWireFixtures.model(key = "\"\""))
        val phone = JsonObject(pc + ("baseUrl" to JsonPrimitive("https://different.invalid/v1")))
        val plan = preview(mapOf(path to pc), mapOf(path to phone))
        assertTrue(DesktopMergePreview.Reason.ACCOUNT_IDENTITY in plan.conflicts.single().reasons)
        assertTrue(DesktopMergePreview.Choice.BOTH in plan.conflicts.single().choices)
    }

    @Test fun selectiveScopeCannotOverwriteUnselectedComputerSettingsOrInventBothForSingletonFields() {
        val path = "entities/app_settings.json"
        val pc = mapOf(path to obj("""{"themeMode":"DARK","defaultModelId":"pc","themeColorHistory":[]}"""))
        val phone = mapOf(path to obj("""{"themeMode":"LIGHT","defaultModelId":null,"themeColorHistory":[]}"""))
        val scope = InspectionScope(setOf("themeMode"), 0, 0, 0, 0, 0, false, false, false, false)
        val plan = DesktopMergePreview.create(pc, phone, emptyMap(), emptyMap(), scope)
        val conflict = plan.conflicts.single()
        assertEquals("setting:themeMode", conflict.key)
        assertFalse(DesktopMergePreview.Choice.BOTH in conflict.choices)
        val selected = plan.select(mapOf(conflict.key to DesktopMergePreview.Choice.TAKE_PHONE))
        assertTrue(selected.computer.any { it.key == "setting:defaultModelId" })
        assertEquals(listOf("setting:themeMode"), selected.phone.map { it.key })
    }

    @Test fun equalMediaBytesCompareEquallyAcrossDifferentSourcePaths() {
        val pc = mapOf(card("c", "Synthetic", ",\"avatar\":\"chatbar-backup-file:images/pc.png\""))
        val phone = mapOf(card("c", "Synthetic", ",\"avatar\":\"chatbar-backup-file:images/phone.png\""))
        val plan = DesktopMergePreview.create(pc, phone, mapOf("images/pc.png" to "a".repeat(64)), mapOf("images/phone.png" to "a".repeat(64)))
        assertTrue(plan.conflicts.isEmpty()); assertTrue(plan.select(emptyMap()).phone.isEmpty())
    }
}
