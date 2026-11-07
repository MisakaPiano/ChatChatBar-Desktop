package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.EditorDraftRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMergeResourceEditorDraftTest {
    @Test fun `format and world book pending edits remain discoverable after named adoption and exact ID copies`() = runBlocking<Unit> {
        for (type in listOf(EditorDraftType.FORMAT_CARD, EditorDraftType.WORLD_BOOK)) for (sameId in listOf(false, true)) {
            val sourceId = if (sameId) "a" else "b"
            fun owner(id: String, name: String): Pair<String, JsonObject> = if (type == EditorDraftType.FORMAT_CARD)
                "entities/format_cards/$id.json" to backupJson.encodeToJsonElement(FormatCard.serializer(), FormatCard(id, name, "Synthetic text $id", createdAt = 1)).jsonObject
            else "entities/world_books/$id.json" to backupJson.encodeToJsonElement(WorldBook.serializer(), WorldBook(id, name, description = "Synthetic text $id")).jsonObject
            val pending = EditorDraft(EditorDraftRepository.draftId(type, sourceId), type, EditorDraftMode.EDIT, sourceId,
                "original-draft-session", baseHash = "original-base-proof",
                formatPayload = if (type == EditorDraftType.FORMAT_CARD) FormatCard(sourceId, "Pending", "Literal b remains unchanged", createdAt = 1) else null,
                worldBookPayload = if (type == EditorDraftType.WORLD_BOOK) WorldBook(sourceId, "Pending", description = "Literal b remains unchanged") else null,
                createdAt = 1, updatedAt = 2)
            val pc = mapOf(owner("a", "Shared"))
            val phone = mapOf(owner(sourceId, "SHARED"), "entities/edit_drafts/${pending.id}.json" to backupJson.encodeToJsonElement(EditorDraft.serializer(), pending).jsonObject)
            for (choice in listOf(DesktopMergePreview.Choice.TAKE_PHONE, DesktopMergePreview.Choice.BOTH)) {
                val preview = DesktopMergePreview.create(pc, phone, emptyMap(), emptyMap())
                DesktopMergeDestinationPlan.create(preview.select(mapOf(preview.conflicts.single().key to choice)), emptyMap(), emptyMap()).use { plan ->
                    val root = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/merge-editor-draft").toAbsolutePath()), "resource-")
                    try {
                        for ((relative, value) in plan.entities) {
                            val path = root.resolve(relative); Files.createDirectories(path.parent); Files.writeString(path, value.toString())
                        }
                        val category = if (type == EditorDraftType.FORMAT_CARD) "format_cards" else "world_books"
                        val target = plan.identity.value(category, sourceId)
                        val loaded = assertNotNull(EditorDraftRepository(JsonFileStorage(root)).getForTarget(type, target))
                        assertEquals(target, loaded.targetId); assertEquals(EditorDraftRepository.draftId(type, target), loaded.id)
                        assertEquals(pending.baseHash, loaded.baseHash); assertEquals(pending.draftSessionId, loaded.draftSessionId)
                        if (type == EditorDraftType.FORMAT_CARD) {
                            assertEquals(target, loaded.formatPayload!!.id); assertEquals(pending.formatPayload!!.content, loaded.formatPayload!!.content)
                        } else {
                            assertEquals(target, loaded.worldBookPayload!!.id); assertEquals(pending.worldBookPayload!!.description, loaded.worldBookPayload!!.description)
                        }
                        if (choice == DesktopMergePreview.Choice.TAKE_PHONE) assertEquals("a", target)
                        else if (sameId) assertNotEquals("a", target) else assertEquals("b", target)
                    } finally { check(root.toFile().deleteRecursively()) }
                }
            }
        }
    }
}
