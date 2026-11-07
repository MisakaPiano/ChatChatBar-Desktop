package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.rag.RagRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMergeDocumentRagTest {
    @Test fun `card choices retain the correct document index and real retrieval follows adopted card and document identities`() = runBlocking<Unit> {
        fun card(id: String, name: String, doc: String) = "entities/character_cards/$id.json" to backupJson.encodeToJsonElement(
            CharacterCard.serializer(), CharacterCard(id, name,
                customDocuments = listOf(DocumentInfo(doc, "Synthetic guide", "", "txt", 1)), createdAt = 1, updatedAt = 1)).jsonObject
        fun chunk(id: String, owner: String, doc: String?, kind: ChunkSourceType = ChunkSourceType.DOCUMENT) =
            "entities/vector_chunks/$id.json" to backupJson.encodeToJsonElement(VectorChunk.serializer(), VectorChunk(id, kind, owner,
                content = "Synthetic $id", embedding = listOf(1f, 0f), createdAt = 1,
                metadata = if (doc == null) emptyMap() else mapOf("documentId" to doc, "originalDocId" to doc,
                    "note" to doc, "sourceId" to owner))).jsonObject
        // Vectors precede the primary card deliberately; grouping must select the card's identity.
        val pc = linkedMapOf(chunk("pc-doc", "a", "p"), chunk("pc-setting", "a", null, ChunkSourceType.CHARACTER_SETTING),
            chunk("unrelated-doc", "u", "unrelated"), chunk("memory", "s", null, ChunkSourceType.CHAT_MEMORY),
            card("a", "Shared", "p"), card("u", "Unrelated", "unrelated"),
            "entities/chat_sessions/s.json" to backupJson.encodeToJsonElement(ChatSession.serializer(), ChatSession("s", "a", "Synthetic", createdAt = 1, updatedAt = 1)).jsonObject)
        val phone = linkedMapOf(chunk("phone-doc", "b", "q"), chunk("phone-setting", "b", null, ChunkSourceType.CHARACTER_SETTING), card("b", "SHARED", "q"))
        val pcBefore = pc.toString(); val phoneBefore = phone.toString()
        for (choice in DesktopMergePreview.Choice.entries) {
            val preview = DesktopMergePreview.create(pc, phone, emptyMap(), emptyMap())
            val conflict = preview.conflicts.single()
            assertEquals("character_cards", conflict.category)
            DesktopMergeDestinationPlan.create(preview.select(mapOf(conflict.key to choice)), emptyMap(), emptyMap()).use { plan ->
                val base = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/merge-document-rag").toAbsolutePath()), "case-")
                try {
                    for ((relative, value) in plan.entities) {
                        val destination = base.resolve(relative); Files.createDirectories(destination.parent)
                        Files.writeString(destination, value.toString())
                    }
                    val repository = RagRepository(JsonFileStorage(base))
                    val adopted = choice == DesktopMergePreview.Choice.TAKE_PHONE
                    val keptDoc = if (adopted) "phone-doc" else "pc-doc"
                    assertEquals(setOf(keptDoc, "memory"), repository.getChunksForRetrieval("a", "s").map { it.id }.toSet())
                    assertEquals(setOf(if (adopted) "phone-setting" else "pc-setting"),
                        repository.getChunksBySource(ChunkSourceType.CHARACTER_SETTING, "a").map { it.id }.toSet())
                    assertEquals(setOf("unrelated-doc"), repository.getChunksForRetrieval("u", null).map { it.id }.toSet())
                    val copy = repository.getChunksForRetrieval("b", null)
                    assertEquals(if (choice == DesktopMergePreview.Choice.BOTH) setOf("phone-doc") else emptySet(), copy.map { it.id }.toSet())
                    val selected = repository.getChunkById(keptDoc)!!
                    assertEquals("p", selected.metadata["originalDocId"]); assertEquals("p", selected.metadata["documentId"])
                    assertEquals(if (adopted) "q" else "p", selected.metadata["note"])
                    assertEquals(if (adopted) "b" else "a", selected.metadata["sourceId"])
                    repository.deleteChunksByDocumentId("p")
                    assertEquals(setOf("memory"), repository.getChunksForRetrieval("a", "s").map { it.id }.toSet())
                    assertEquals(if (choice == DesktopMergePreview.Choice.BOTH) setOf("phone-doc") else emptySet(),
                        repository.getChunksForRetrieval("b", null).map { it.id }.toSet())
                } finally { check(base.toFile().deleteRecursively()) }
            }
        }
        assertEquals(pcBefore, pc.toString()); assertEquals(phoneBefore, phone.toString())
    }
}
