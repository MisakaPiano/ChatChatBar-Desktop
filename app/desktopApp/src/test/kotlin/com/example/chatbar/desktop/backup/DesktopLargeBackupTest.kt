package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import java.io.InputStream
import java.nio.file.*
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class DesktopLargeBackupTest {
    @Test fun fileViewsReadOnDemandAndRejectChangesWithoutTouchingSourceFiles() {
        val base = Files.createTempDirectory("ccb-large-view-").toAbsolutePath().normalize()
        try {
            val source = Files.writeString(base.resolve("source.json"), "{\"id\":\"first\"}")
            val hash = DesktopBackupPolicy.digest(Files.readAllBytes(source))
            var loads = 0
            val view = DesktopBackupRecords.view(listOf("a", "b")) { loads++; JsonObject(mapOf("id" to JsonPrimitive(it))) }
            assertEquals(2, view.size); assertEquals(setOf("a", "b"), view.keys); assertEquals(0, loads)
            assertEquals("a", view.getValue("a").getValue("id").jsonPrimitive.content); assertEquals(1, loads)
            val files = DesktopBackupRecords.files(mapOf("record" to source), mapOf("record" to hash))
            assertEquals("first", files.getValue("record").getValue("id").jsonPrimitive.content)
            Files.writeString(source, "{\"id\":\"other\"}")
            assertFailsWith<IllegalStateException> { files.getValue("record") }
            DesktopBackupScratch.create(base, "records-").use { scratch ->
                val store = DesktopBackupRecordStore(scratch)
                store["a"] = JsonObject(mapOf("id" to JsonPrimitive("kept")))
                val records = DesktopMergeRecord.from(store)
                store["a"] = JsonObject(mapOf("id" to JsonPrimitive("updated")))
                assertEquals("updated", records.single().text("id"))
            }
            assertTrue(Files.exists(source))
            assertEquals(listOf(source), Files.list(base).use { it.toList() })
        } finally { base.toFile().deleteRecursively() }
    }

    @Test fun modernExportStagingAndPreviewRetainVectorsAndInlineArchivesAboveOldBudgets() = runBlocking {
        val base = Files.createTempDirectory("ccb-large-roundtrip-").toAbsolutePath().normalize()
        try {
            val files = linkedMapOf<String, Path>()
            fun add(name: String, value: JsonObject) { files[name] = Files.writeString(base.resolve("input-" + files.size), value.toString()) }
            fun <T> addTyped(name: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) = add(name, backupJson.encodeToJsonElement(serializer, value).jsonObject)
            addTyped("entities/character_cards/card.json", CharacterCard.serializer(), CharacterCard("card", "Synthetic", createdAt = 1, updatedAt = 1))
            addTyped("entities/chat_sessions/session.json", ChatSession.serializer(), ChatSession("session", "card", "Synthetic", createdAt = 1, updatedAt = 1))
            val random = Random(11031)
            val block = CharArray(65536) { ('a'.code + random.nextInt(26)).toChar() }.concatToString()
            val seed = VectorChunk("v", ChunkSourceType.CHAT_MEMORY, "session", content = block.repeat(10), embedding = List(256) { it / 257f }, createdAt = 1)
            repeat(104) { index -> addTyped("entities/vector_chunks/v" + index + ".json", VectorChunk.serializer(), seed.copy(id = "v" + index)) }
            val slot = SaveSlot(id = "slot", sessionId = "session", name = "Synthetic", vectorChunks = List(16) { seed.copy(id = "arch" + it) }, createdAt = 1)
            addTyped("entities/save_slots/slot.json", SaveSlot.serializer(), slot)
            assertTrue(files.values.sumOf { Files.size(it) } > 64L * 1024 * 1024)
            assertTrue(Files.size(files.getValue("entities/save_slots/slot.json")) > 8L * 1024 * 1024)
            val archive = base.resolve("large.cbbackup")
            DesktopFullBackupWriter.export(files, archive, null)
            DesktopFullBackupStaging.prepare(archive, null, base).use { staged ->
                assertEquals(files.keys, staged.files.keys)
                val preview = DesktopMergePreview.create(emptyMap(), staged.entities, emptyMap(), emptyMap(), staged.source.manifest.scope)
                val selection = preview.select(emptyMap())
                assertEquals(107, selection.phone.sumOf { it.records.size }) // card plus one complete session unit
                val selected = selection.phone.flatMap { it.records.keys }
                assertEquals(files.keys, selected.toSet())
                assertEquals(slot.vectorChunks.size, backupJson.decodeFromJsonElement(SaveSlot.serializer(), staged.entities.getValue("entities/save_slots/slot.json")).vectorChunks.size)
                val exported = base.resolve("again.cbbackup")
                DesktopFullBackupWriter.export(staged.files, exported, null)
                val verified = DesktopFullBackupSource.verify(exported, null, object : DesktopFullBackupSource.TentativeSink {
                    override fun entity(path: String, original: JsonObject, raw: ByteArray) {}
                    override fun preferences(original: JsonObject) {}
                    override fun media(path: String, input: InputStream) {}
                })
                assertEquals(staged.source.inventory, verified.inventory)
            }
            val slotWork = Files.createDirectory(base.resolve("slot-work"))
            val slotArchive = com.example.chatbar.desktop.DesktopSaveSlotArchive(slotWork)
            val prepared = slotArchive.prepare(files.getValue("entities/save_slots/slot.json"))
            assertEquals(16, prepared.slot.vectorChunks.size)
            val live = Files.createDirectory(base.resolve("separate-live"))
            val restored = com.example.chatbar.desktop.DesktopSaveSlotRestorePlan(live) { _, _ -> false }.prepare(slotArchive, prepared,
                ChatSession("restored", "card", "Synthetic", createdAt = 1, updatedAt = 1))
            assertEquals(16, restored.files.keys.count { it.startsWith("entities/vector_chunks/") })
            assertTrue(files.values.all { Files.exists(it) })
        } finally { base.toFile().deleteRecursively() }
    }
}
