package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.EditorDraftRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMergeRestoreModesTest {
    private fun <T> entity(type: String, id: String?, serializer: KSerializer<T>, value: T) =
        ("entities/$type" + (id?.let { "/$it" } ?: "") + ".json") to backupJson.encodeToJsonElement(serializer, value).jsonObject
    private fun card(id: String, name: String) = entity("character_cards", id, CharacterCard.serializer(),
        CharacterCard(id, name, greeting = "Synthetic greeting", createdAt = 1, updatedAt = 1))
    private fun scope(vararg options: String, studio: Boolean = false) = InspectionScope(options.toSet(), 0, 0, 0, 0, 0, false, studio, false, false)
    private fun preview(pc: Map<String, JsonObject>, incoming: Map<String, JsonObject>, scope: InspectionScope? = null) =
        DesktopMergePreview.create(pc, incoming, emptyMap(), emptyMap(), scope)

    @Test fun `copies freshen identical and unique owners embedded people messages and pending drafts while ordinary merge remains unchanged`() = runBlocking<Unit> {
        val person = CharacterInfo.create("Person").copy(id = "person")
        val card = CharacterCard("c", "Same", greeting = "Synthetic", characters = listOf(person), createdAt = 1, updatedAt = 1)
        val session = ChatSession("s", "c", "Chat", desktopPlayerRole = SessionPlayerRole("c", "person"), createdAt = 1, updatedAt = 1)
        val message = ChatMessage("m", "s", MessageRole.USER, "Unchanged original text", createdAt = 1, updatedAt = 1)
        val draft = EditorDraft(EditorDraftRepository.draftId(EditorDraftType.CHARACTER_CARD, "c"), EditorDraftType.CHARACTER_CARD,
            EditorDraftMode.EDIT, "c", "draft-session", baseHash = "original", characterPayload = card.copy(name = "Unsent changes"), createdAt = 1, updatedAt = 1)
        val records = mapOf(entity("character_cards", "c", CharacterCard.serializer(), card), entity("chat_sessions", "s", ChatSession.serializer(), session),
            entity("chat_messages", "s_m", ChatMessage.serializer(), message), entity("edit_drafts", draft.id, EditorDraft.serializer(), draft))
        val unique = card("unique", "Unique")
        val source = records + unique
        val p = preview(records, source)
        assertEquals(1, p.select(emptyMap()).phone.size)
        val original = source.toString()
        val result = p.modePlan(DesktopBackupRestoreMode.ADD_COPIES)
        DesktopMergeDestinationPlan.create(result.selection, emptyMap(), emptyMap()).use { plan ->
            val copiedCard = plan.identity.value("character_cards", "c"); val copiedUnique = plan.identity.value("character_cards", "unique")
            assertNotEquals("c", copiedCard); assertNotEquals("unique", copiedUnique)
            assertEquals(records.getValue("entities/character_cards/c.json"), plan.entities.getValue("entities/character_cards/c.json"))
            val copied = plan.entities.getValue("entities/character_cards/$copiedCard.json")
            assertEquals("Same（副本）", copied["name"]!!.jsonPrimitive.content)
            val personId = copied["characters"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
            assertNotEquals("person", personId)
            val copiedSession = plan.identity.value("chat_sessions", "s")
            val role = plan.entities.getValue("entities/chat_sessions/$copiedSession.json")["desktopPlayerRole"]!!.jsonObject
            assertEquals(copiedCard, role["cardId"]!!.jsonPrimitive.content); assertEquals(personId, role["personId"]!!.jsonPrimitive.content)
            val copiedMessage = plan.entities.getValue("entities/chat_messages/${copiedSession}_${plan.identity.value("chat_messages", "m")}.json")
            assertEquals(message.content, copiedMessage["content"]!!.jsonPrimitive.content)
            val newDraftId = EditorDraftRepository.draftId(EditorDraftType.CHARACTER_CARD, copiedCard)
            val newDraft = plan.entities.getValue("entities/edit_drafts/$newDraftId.json")
            assertEquals(copiedCard, newDraft["targetId"]!!.jsonPrimitive.content)
            assertNotEquals("draft-session", newDraft["draftSessionId"]!!.jsonPrimitive.content)
        }
        assertEquals(original, source.toString())
    }

    @Test fun `update follows unique normalized card and conversation domains but ambiguity makes an independent named copy`() {
        fun session(id: String, card: String) = entity("chat_sessions", id, ChatSession.serializer(), ChatSession(id, card, "Chat", createdAt = 1, updatedAt = 1))
        val pc = mapOf(card("a", "ＡＢＣ"), session("pc-chat", "a"))
        val phone = mapOf(card("b", "abc"), session("phone-chat", "b"))
        val p = preview(pc, phone).modePlan(DesktopBackupRestoreMode.UPDATE_EXISTING)
        DesktopMergeDestinationPlan.create(p.selection, emptyMap(), emptyMap()).use { plan ->
            assertEquals("a", plan.identity.value("character_cards", "b")); assertEquals("pc-chat", plan.identity.value("chat_sessions", "phone-chat"))
            assertEquals("abc", plan.entities.getValue("entities/character_cards/a.json")["name"]!!.jsonPrimitive.content)
            assertEquals(2, p.updated)
        }
        val ambiguous = preview(pc + card("other", "abc"), phone).modePlan(DesktopBackupRestoreMode.UPDATE_EXISTING)
        assertTrue(ambiguous.warnings.isNotEmpty())
        DesktopMergeDestinationPlan.create(ambiguous.selection, emptyMap(), emptyMap()).use { plan ->
            val copied = plan.identity.value("character_cards", "b")
            assertNotEquals("a", copied); assertNotEquals("b", copied)
            assertTrue(plan.entities.getValue("entities/character_cards/$copied.json")["name"]!!.jsonPrimitive.content.contains("副本"))
            assertTrue("entities/character_cards/a.json" in plan.entities && "entities/character_cards/other.json" in plan.entities)
        }
    }

    @Test fun `scoped empty replace clears only selected collections and keeps transitive dependencies of retained content`() {
        val card = CharacterCard("c", "Retained", defaultFormatCardId = "f", worldBookIds = listOf("w"), createdAt = 1, updatedAt = 1)
        val records = mapOf(entity("character_cards", "c", CharacterCard.serializer(), card),
            entity("chat_sessions", "s", ChatSession.serializer(), ChatSession("s", "c", "Retained session", modelId = "m", createdAt = 1, updatedAt = 1)),
            "entities/model_configs/m.json" to backupJson.parseToJsonElement("""{"id":"m","displayName":"Needed","baseUrl":"https://synthetic.invalid/v1","apiKey":"","modelName":"model","createdAt":1}""").jsonObject,
            "entities/model_configs/unused.json" to backupJson.parseToJsonElement("""{"id":"unused","displayName":"Unused","baseUrl":"https://synthetic.invalid/v1","apiKey":"","modelName":"model","createdAt":1}""").jsonObject,
            "entities/format_cards/f.json" to backupJson.parseToJsonElement("""{"id":"f","name":"Needed format","createdAt":1,"updatedAt":1}""").jsonObject,
            "entities/world_books/w.json" to backupJson.parseToJsonElement("""{"id":"w","name":"Needed book","createdAt":1,"updatedAt":1}""").jsonObject,
            "entities/world_books/unused.json" to backupJson.parseToJsonElement("""{"id":"unused","name":"Unused book","createdAt":1,"updatedAt":1}""").jsonObject,
            "entities/app_settings.json" to buildJsonObject { put("themeMode", "DARK"); put("chatBubbleFontScale", 1.4) })
        val p = preview(records, emptyMap(), scope("models", "resources", "themeMode")).modePlan(DesktopBackupRestoreMode.REPLACE_SELECTED)
        DesktopMergeDestinationPlan.create(p.selection, emptyMap(), emptyMap()).use { plan ->
            assertTrue("entities/character_cards/c.json" in plan.entities)
            assertTrue("entities/model_configs/m.json" in plan.entities && "entities/format_cards/f.json" in plan.entities && "entities/world_books/w.json" in plan.entities)
            assertFalse("entities/model_configs/unused.json" in plan.entities || "entities/world_books/unused.json" in plan.entities)
            val settings = plan.entities.getValue("entities/app_settings.json")
            assertEquals(1.4, settings["chatBubbleFontScale"]!!.jsonPrimitive.double)
            assertEquals(backupJson.encodeToJsonElement(AppSettings.serializer(), AppSettings()).jsonObject["themeMode"], settings["themeMode"])
            assertTrue(p.warnings.size >= 3)
        }
        val legacy = preview(records, emptyMap()).modePlan(DesktopBackupRestoreMode.REPLACE_SELECTED)
        assertTrue(legacy.selection.computer.isEmpty()); assertFalse(legacy.selection.keepComputerMedia)
    }

    @Test fun `update keeps other session domain and remaps cross session message collisions while media scope and empty catalog reset remain explicit`() {
        fun session(id: String, owner: String) = entity("chat_sessions", id, ChatSession.serializer(), ChatSession(id, owner, "Chat", createdAt = 1, updatedAt = 1))
        fun message(session: String) = entity("chat_messages", "${session}_m", ChatMessage.serializer(), ChatMessage("m", session, MessageRole.USER, "Text", createdAt = 1, updatedAt = 1))
        val pc = mapOf(card("a", "A"), session("s", "a"), message("s"))
        val phone = mapOf(card("b", "B"), session("t", "b"), message("t"))
        val p = preview(pc, phone).modePlan(DesktopBackupRestoreMode.UPDATE_EXISTING)
        DesktopMergeDestinationPlan.create(p.selection, emptyMap(), emptyMap()).use { plan ->
            assertTrue("entities/chat_messages/s_m.json" in plan.entities)
            assertNotEquals("m", plan.identity.value("chat_messages", "m"))
            assertEquals(2, plan.entities.keys.count { DesktopBackupPolicy.entityType(it) == "chat_messages" })
        }
        val catalog = mapOf(com.example.chatbar.desktop.DesktopNovelAiCatalogTransfer.DATABASE to "a".repeat(64),
            com.example.chatbar.desktop.DesktopNovelAiCatalogTransfer.METADATA to "b".repeat(64))
        val replace = DesktopMergePreview.create(emptyMap(), emptyMap(), catalog, emptyMap(), scope("tagCatalog"))
            .modePlan(DesktopBackupRestoreMode.REPLACE_SELECTED)
        assertFalse(replace.selection.retainComputerCatalog)
        val update = DesktopMergePreview.create(emptyMap(), emptyMap(), catalog, emptyMap(), scope("tagCatalog"))
            .modePlan(DesktopBackupRestoreMode.UPDATE_EXISTING)
        assertTrue(update.selection.retainComputerCatalog)
    }

    @Test fun `history update matches unique content without source ID and ambiguous fingerprints preserve both originals`() {
        fun history(id: String) = entity("novelai_generation_history", id,
            com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry.serializer(),
            com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry(id = id, createdAt = 1))
        val pc = mapOf(history("existing")); val incoming = mapOf(history("incoming"))
        val update = preview(pc, incoming).modePlan(DesktopBackupRestoreMode.UPDATE_EXISTING)
        DesktopMergeDestinationPlan.create(update.selection, emptyMap(), emptyMap()).use { plan ->
            assertEquals("existing", plan.identity.value("novelai_generation_history", "incoming"))
            assertEquals(1, plan.entities.size)
        }
        val ambiguous = preview(pc + history("duplicate"), incoming).modePlan(DesktopBackupRestoreMode.UPDATE_EXISTING)
        DesktopMergeDestinationPlan.create(ambiguous.selection, emptyMap(), emptyMap()).use { plan ->
            assertEquals(3, plan.entities.size)
            assertNotEquals("incoming", plan.identity.value("novelai_generation_history", "incoming"))
            assertTrue(ambiguous.warnings.isNotEmpty())
        }
    }

    @Test fun `selected absent default model field no longer preserves a dependency while copies count new and duplicate identities separately`() {
        val pc = mapOf("entities/model_configs/m.json" to backupJson.parseToJsonElement("""{"id":"m","displayName":"Old","baseUrl":"https://synthetic.invalid/v1","apiKey":"","modelName":"model","createdAt":1}""").jsonObject,
            "entities/app_settings.json" to buildJsonObject { put("defaultModelId", "m"); put("chatBubbleFontScale", 1.3) })
        val result = preview(pc, emptyMap(), scope("models", "defaultModelId")).modePlan(DesktopBackupRestoreMode.REPLACE_SELECTED)
        DesktopMergeDestinationPlan.create(result.selection, emptyMap(), emptyMap()).use { plan ->
            assertFalse("entities/model_configs/m.json" in plan.entities)
            assertEquals(JsonNull, plan.entities.getValue("entities/app_settings.json")["defaultModelId"])
            assertEquals(1.3, plan.entities.getValue("entities/app_settings.json")["chatBubbleFontScale"]!!.jsonPrimitive.double)
        }
        val copy = preview(mapOf(card("same", "Same")), mapOf(card("same", "Same"), card("unique", "Unique")))
            .modePlan(DesktopBackupRestoreMode.ADD_COPIES)
        assertEquals(1, copy.added); assertEquals(1, copy.copied)
    }
}
