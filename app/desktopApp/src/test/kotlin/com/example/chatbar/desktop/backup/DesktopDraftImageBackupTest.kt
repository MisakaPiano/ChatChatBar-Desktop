package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopDraftImageBackupTest {
    @Test fun `selected full backup projects draft images and original text and source stay unchanged`() = runBlocking {
        val base = Files.createTempDirectory("synthetic-draft-backup-")
        try {
            val root = Files.createDirectory(base.resolve("root"))
            fun write(path: String, value: JsonObject) { val p = root.resolve(path); Files.createDirectories(p.parent); Files.writeString(p, value.toString()) }
            write("entities/character_cards/c.json", backupJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard("c", "Synthetic", createdAt = 1, updatedAt = 1)).jsonObject)
            write("entities/chat_sessions/s.json", backupJson.encodeToJsonElement(ChatSession.serializer(), ChatSession("s", "c", "Synthetic", createdAt = 1, updatedAt = 1)).jsonObject)
            val image = root.resolve("images/draft.png"); Files.createDirectories(image.parent); val bytes = byteArrayOf(1, 2, 3, 4); Files.write(image, bytes)
            val draft = ChatDraft("s", "unsent text", 7, listOf(image.toString()))
            write("entities/chat_drafts/s.json", backupJson.encodeToJsonElement(ChatDraft.serializer(), draft).jsonObject)
            val original = Files.readAllBytes(root.resolve("entities/chat_drafts/s.json"))
            for (include in listOf(false, true)) {
                val selection = AppBackupSelection(characterIds = setOf("c"), sessions = mapOf("s" to BackupSessionChoice(images = include)))
                val work = Files.createDirectory(base.resolve("work-$include"))
                DesktopFullBackupMapping(root).export(work, 88, StrictBackupZipReader.Limits().expanded, selection = selection).use { exported ->
                    val raw = Files.readString(exported.files.getValue("entities/chat_drafts/s.json"))
                    val result = backupJson.decodeFromString(ChatDraft.serializer(), raw)
                    assertEquals("unsent text", result.content)
                    assertEquals(if (include) 1 else 0, result.images.size)
                    assertEquals(include, exported.files.keys.any { it.startsWith("images/") })
                    if (include) {
                        val resource = result.images.single().removePrefix(DesktopBackupPolicy.PREFIX)
                        assertContentEquals(bytes, Files.readAllBytes(exported.files.getValue(resource)))
                    }
                }
            }
            assertContentEquals(original, Files.readAllBytes(root.resolve("entities/chat_drafts/s.json")))
            assertContentEquals(bytes, Files.readAllBytes(image))
        } finally { base.toFile().deleteRecursively() }
    }

    @Test fun `merge retains omitted draft media for exact computer session but not a fork`() = runBlocking {
        fun records(draft: ChatDraft) = mapOf(
            "entities/chat_sessions/s.json" to backupJson.encodeToJsonElement(ChatSession.serializer(), ChatSession("s", "c", "Synthetic", createdAt = 1, updatedAt = 1)).jsonObject,
            "entities/chat_drafts/s.json" to backupJson.encodeToJsonElement(ChatDraft.serializer(), draft).jsonObject)
        val pc = records(ChatDraft("s", "pc body", 1, listOf(DesktopBackupPolicy.PREFIX + "images/pc.png")))
        val incoming = records(ChatDraft("s", "phone body", 2))
        val media = mapOf("images/pc.png" to "a".repeat(64))
        val scope = InspectionScope(emptySet(), 1, 1, 0, 0, 0, false, false, false, false, false,
            sessionDefaultChoice = InspectionSessionChoice(images = false))
        for (choice in listOf(DesktopMergePreview.Choice.TAKE_PHONE, DesktopMergePreview.Choice.BOTH)) {
            val preview = DesktopMergePreview.create(pc, incoming, media, emptyMap(), scope)
            DesktopMergeDestinationPlan.create(preview.select(preview.conflicts.associate { it.key to choice }), media, emptyMap()).use { plan ->
                val target = plan.entities.values.single { it["content"] == JsonPrimitive("phone body") }
                assertEquals(if (choice == DesktopMergePreview.Choice.TAKE_PHONE) 1 else 0, (target["images"] as? JsonArray)?.size ?: 0)
            }
        }
    }
    @Test fun `omitted images retain computer image draft even when phone has no draft record`() = runBlocking {
        val sessionPath = "entities/chat_sessions/s.json"; val draftPath = "entities/chat_drafts/s.json"
        val session = backupJson.encodeToJsonElement(ChatSession.serializer(), ChatSession("s", "c", "Synthetic", createdAt = 1, updatedAt = 1)).jsonObject
        val draft = backupJson.encodeToJsonElement(ChatDraft.serializer(), ChatDraft("s", "unsent computer", 1,
            listOf(DesktopBackupPolicy.PREFIX + "images/pc.png"))).jsonObject
        val pc = mapOf(sessionPath to session, draftPath to draft)
        val phone = mapOf(sessionPath to session)
        val media = mapOf("images/pc.png" to "a".repeat(64))
        for (include in listOf(false, true)) {
            val scope = InspectionScope(emptySet(), 1, 1, if (include) 1 else 0, 0, 0, false, false, false, false, false,
                sessionDefaultChoice = InspectionSessionChoice(images = include))
            val preview = DesktopMergePreview.create(pc, phone, media, emptyMap(), scope)
            DesktopMergeDestinationPlan.create(preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.TAKE_PHONE }), media, emptyMap()).use { plan ->
                assertEquals(!include, draftPath in plan.entities)
                if (!include) {
                    assertEquals(JsonPrimitive("unsent computer"), plan.entities.getValue(draftPath)["content"])
                    assertEquals(1, plan.entities.getValue(draftPath).getValue("images").jsonArray.size)
                    assertEquals(DesktopMergeDestinationPlan.Side.COMPUTER, plan.resources.values.single().side)
                }
            }
        }
    }
}
