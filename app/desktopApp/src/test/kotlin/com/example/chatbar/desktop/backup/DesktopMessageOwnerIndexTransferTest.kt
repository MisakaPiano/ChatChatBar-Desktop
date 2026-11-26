package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMessageOwnerIndexTransferTest {
    @Test fun `strict backup accepts typed owner proof but materialization invalidates it without changing source`() = runBlocking {
        val base = Files.createTempDirectory("synthetic-owner-index-")
        try {
            val root = Files.createDirectory(base.resolve("target")); val work = Files.createDirectory(base.resolve("work"))
            val index = ChatMessageIndex("s", ownerScopeVersion = 1)
            val raw = backupJson.encodeToString(ChatMessageIndex.serializer(), index).toByteArray()
            val relative = "entities/chat_message_indexes/s.json"
            assertEquals(1, DesktopBackupPolicy.entity(relative, raw, false, modern = true)["ownerScopeVersion"]?.jsonPrimitive?.int)
            val file = Files.write(base.resolve("index.json"), raw)
            DesktopFullBackupMapping(root).restoreMerged(mapOf(relative to file), work, BackupPreferences()).use { mapped ->
                val value = backupJson.decodeFromString(ChatMessageIndex.serializer(), Files.readString(mapped.files.getValue(relative)))
                assertEquals(0, value.ownerScopeVersion)
                assertEquals(index.entries, value.entries)
            }
            assertContentEquals(raw, Files.readAllBytes(file))
        } finally { base.toFile().deleteRecursively() }
    }

    @Test fun `incoming merge index loses proof while retained computer index stays unchanged`() = runBlocking {
        fun records(owner: String) = linkedMapOf(
            "entities/chat_sessions/$owner.json" to backupJson.encodeToJsonElement(ChatSession.serializer(),
                ChatSession(owner, "card", "Synthetic $owner", createdAt = 1, updatedAt = 1)).jsonObject,
            "entities/chat_message_indexes/$owner.json" to backupJson.encodeToJsonElement(ChatMessageIndex.serializer(),
                ChatMessageIndex(owner, ownerScopeVersion = 1)).jsonObject)
        val pc = records("pc"); val incoming = records("phone")
        val before = pc.toString() to incoming.toString()
        val preview = DesktopMergePreview.create(pc, incoming, emptyMap(), emptyMap())
        assertTrue(preview.conflicts.isEmpty())
        DesktopMergeDestinationPlan.create(preview.select(emptyMap()), emptyMap(), emptyMap()).use { plan ->
            val pcIndex = plan.entities.getValue("entities/chat_message_indexes/pc.json")
            val phoneIndex = plan.entities.getValue("entities/chat_message_indexes/phone.json")
            assertEquals(1, pcIndex["ownerScopeVersion"]?.jsonPrimitive?.int)
            assertEquals(0, phoneIndex["ownerScopeVersion"]?.jsonPrimitive?.int)
        }
        assertEquals(before, pc.toString() to incoming.toString())
    }
}
