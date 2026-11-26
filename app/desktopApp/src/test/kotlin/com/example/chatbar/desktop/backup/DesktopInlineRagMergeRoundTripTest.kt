package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.*
import com.example.chatbar.domain.rag.RagRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopInlineRagMergeRoundTripTest {
    private fun <T> raw(serializer: KSerializer<T>, value: T) = backupJson.encodeToJsonElement(serializer, value).toString().toByteArray()
    private fun own(root: Path, base: Path) = (DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
        DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired).ownership

    @Test fun `real legacy file merge restore prune and cold read preserve per-message CSV dependencies`() = runBlocking {
        exercise(listOf(DesktopBackupRestoreMode.MERGE_CHOICES to DesktopMergePreview.Choice.BOTH,
            DesktopBackupRestoreMode.MERGE_CHOICES to DesktopMergePreview.Choice.TAKE_PHONE))
    }

    @Test fun `independent ADD_COPIES restores per-message CSV and prunes only the related chunk after cold read`() = runBlocking {
        exercise(listOf(DesktopBackupRestoreMode.ADD_COPIES to DesktopMergePreview.Choice.BOTH))
    }

    @Test fun `legacy spaced CSV survives independent copies merge restore duplicate and cold cleanup`() = runBlocking {
        exercise(listOf(DesktopBackupRestoreMode.ADD_COPIES to DesktopMergePreview.Choice.BOTH), spacedCsv = true)
    }

    private suspend fun exercise(cases: List<Pair<DesktopBackupRestoreMode, DesktopMergePreview.Choice>>, spacedCsv: Boolean = false) {
        for ((mode, choice) in cases) {
            val base = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/inline-rag-merge").toAbsolutePath()), "case-")
            try {
                val pcRoot = Files.createDirectory(base.resolve("pc")); val store = JsonFileStorage(pcRoot)
                val session = ChatSession("s", "card", "Computer", longTermMemoryEnabled = false, createdAt = 1, updatedAt = 1)
                store.saveEntity("character_cards", "card", CharacterCard("card", "Synthetic", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
                val pcChats = ChatRepository(store); pcChats.createSession(session)
                pcChats.addMessage(ChatMessage("pc-message", "s", MessageRole.USER, "Computer stays original", createdAt = 1, updatedAt = 1))
                val beforePc = Files.walk(pcRoot.resolve("entities")).use { paths -> paths.filter { Files.isRegularFile(it) }.toList()
                    .associate { pcRoot.relativize(it).toString() to Files.readAllBytes(it) } }
                val first = ChatMessage("m1", "s", MessageRole.USER, "Original user body", createdAt = 2, updatedAt = 2)
                val second = ChatMessage("m2", "s", MessageRole.ASSISTANT, "Original reply body", createdAt = 3, updatedAt = 3)
                val pair = VectorChunk("pair", ChunkSourceType.CHAT_MEMORY, "s", messageId = "m1", content = "Original paired summary",
                    embedding = listOf(1f, 0f), metadata = mapOf("messageIds" to if (spacedCsv) "m1, m2" else "m1,m2", "indexMode" to "message_pair", "note" to "m1,m2"), createdAt = 1)
                val manual = VectorChunk("manual", ChunkSourceType.CHAT_MEMORY, "s", content = "Independent manual summary",
                    embedding = listOf(0f, 1f), metadata = mapOf("origin" to "manual", "note" to "m1,m2"), createdAt = 1)
                val legacy = SaveSlot(schemaVersion = 7, id = "slot", sessionId = "s", name = "Original inline archive", createdAt = 1,
                    messages = listOf(first, second), vectorChunks = listOf(pair, manual))
                val legacyBytes = raw(SaveSlot.serializer(), legacy)
                val legacyFile = Files.write(base.resolve("original-inline.cbsave"), legacyBytes)
                val inspection = DesktopSaveSlotInspector(retainHistoricalVoices = true, retainHistoricalReferences = true).inspect(legacyFile)
                assertEquals(SlotInspectStatus.STRUCTURE_VALID, inspection.status)
                assertEquals(SlotInspectCode.OK, inspection.code)
                assertEquals(2, inspection.counts?.messages)
                val incoming = mapOf(
                    "entities/character_cards/card.json" to raw(CharacterCard.serializer(), CharacterCard("card", "Synthetic", createdAt = 1, updatedAt = 1)),
                    "entities/chat_sessions/s.json" to raw(ChatSession.serializer(), session.copy(title = "Phone")),
                    "entities/chat_messages/s_m1.json" to raw(ChatMessage.serializer(), first),
                    "entities/chat_messages/s_m2.json" to raw(ChatMessage.serializer(), second),
                    "entities/save_slots/slot.json" to legacyBytes)
                val scope = OfficialWireScope(emptySet(), 1, 1, 0, 0, 1, false, false, false, false,
                    sessionDefaultChoice = OfficialWireSessionChoice(images = false, voice = false, archives = true))
                val phoneFile = Files.write(base.resolve("phone.cbbackup"), CensusWireFixtures.bytes(prefs = "{}", files = incoming, code = 88, version = 2, scope = scope))
                val originalPhone = Files.readAllBytes(phoneFile)
                own(pcRoot, base).use {
                    DesktopFullBackupMapping(pcRoot).export(Files.createDirectory(base.resolve("pc-work")), 88, StrictBackupZipReader.Limits().expanded, forMerge = true).use { pc ->
                        DesktopFullBackupStaging.prepare(phoneFile, null, Files.createDirectory(base.resolve("phone-work"))).use { phone ->
                            val pcMedia = pc.proof.filterKeys { !it.startsWith("entities/") }
                            val phoneMedia = phone.source.inventory.filterKeys { !it.startsWith("entities/") }.mapValues { it.value.sha256 }
                            val preview = DesktopMergePreview.create(DesktopMergeRootPreparation.computerEntities(pc), phone.entities, pcMedia, phoneMedia, phone.source.manifest.scope)
                            assertTrue(preview.conflicts.isNotEmpty())
                            val selected = if (mode == DesktopBackupRestoreMode.MERGE_CHOICES)
                                preview.select(preview.conflicts.associate { it.key to choice }) else preview.modePlan(mode).selection
                            val root = Files.createDirectory(base.resolve("merged"))
                            val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("keys")), MergeRootSyntheticProtector)
                            own(root, base).use {
                                val pending = authority.begin()
                                var target = ""; var targetSecond = ""
                                DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected,
                                    Files.createDirectory(base.resolve("prepare-work"))).use { prepared ->
                                    authority.complete(pending, prepared.files); authority.verifyAndResolve()
                                    target = prepared.plan.identity.value("chat_sessions", "s")
                                    targetSecond = prepared.plan.identity.value("chat_messages", "m2")
                                    if (mode == DesktopBackupRestoreMode.ADD_COPIES || choice == DesktopMergePreview.Choice.BOTH) {
                                        assertNotEquals("s", target)
                                        assertEquals("Computer stays original", ChatRepository(JsonFileStorage(root)).getMessagesReadOnly("s").single().content)
                                    } else assertEquals("s", target)
                                }
                                val gate = DesktopDataOperationCoordinator(); val storage = JsonFileStorage(root, gate)
                                val chats = ChatRepository(storage); val voices = VoiceMessageRepository(storage, true)
                                val focus = DesktopAudioFocus(); val audio = DesktopAudioMaintenance({ focus }) { }
                                try {
                                    val slots = mutableListOf<SaveSlot>(); storage.visitEntitiesStrict("save_slots", SaveSlot.serializer()) { _, value -> if (value.sessionId == target) slots += value }
                                    val slot = slots.single()
                                    assertEquals(setOf(chats.getMessagesReadOnly(target).first().id, targetSecond), slot.vectorChunks.first().metadata.getValue("messageIds").split(',').toSet())
                                    val service = DesktopSaveSlotService(root, gate, audio, object : DesktopSessionReservation {
                                        override suspend fun <T> run(sessionId: String, operation: suspend () -> T): T = operation()
                                    }, chats, voices, { null }, { root.resolve(it) })
                                    service.restore(slot.id, target)
                                    val rag = RagRepository(storage)
                                    val restoredPair = rag.getAllChunksForSession(target).single { it.content == pair.content }
                                    assertEquals(setOf(chats.getMessagesReadOnly(target).first().id, targetSecond), restoredPair.metadata.getValue("messageIds").split(',').toSet())
                                    assertEquals("m1,m2", restoredPair.metadata["note"])
                                    if (spacedCsv) {
                                        val spaced = restoredPair.copy(metadata = restoredPair.metadata +
                                            ("messageIds" to restoredPair.metadata.getValue("messageIds").split(',').joinToString(", ")))
                                        rag.saveChunks(listOf(spaced))
                                        val originalChunkPath = root.resolve("entities/vector_chunks/${spaced.id}.json")
                                        val originalChunkBytes = Files.readAllBytes(originalChunkPath)
                                        val duplicate = service.duplicate(target)
                                        val duplicatePair = RagRepository(JsonFileStorage(root)).getAllChunksForSession(duplicate.id).single { it.content == pair.content }
                                        assertEquals(chats.getMessagesReadOnly(duplicate.id).map { it.id }.toSet(), duplicatePair.metadata.getValue("messageIds").split(',').toSet())
                                        assertEquals(pair.embedding, duplicatePair.embedding)
                                        assertEquals("m1,m2", duplicatePair.metadata["note"])
                                        assertContentEquals(originalChunkBytes, Files.readAllBytes(originalChunkPath))
                                        val duplicateSecond = chats.getMessagesReadOnly(duplicate.id).single { it.content == second.content }.id
                                        RagRepository(JsonFileStorage(root)).deleteChunksByMessageId(duplicateSecond)
                                        val coldDuplicate = RagRepository(JsonFileStorage(root)).getAllChunksForSession(duplicate.id)
                                        assertEquals(listOf(manual.content), coldDuplicate.map { it.content })
                                        assertContentEquals(originalChunkBytes, Files.readAllBytes(originalChunkPath))
                                    }
                                    rag.deleteChunksByMessageId(targetSecond)
                                    val cold = RagRepository(JsonFileStorage(root)).getAllChunksForSession(target)
                                    assertEquals(listOf(manual.content), cold.map { it.content })
                                    assertEquals("m1,m2", cold.single().metadata["note"])
                                } finally { focus.closeAndDrain(); gate.closeAndDrain() }
                            }
                        }
                    }
                }
                assertContentEquals(originalPhone, Files.readAllBytes(phoneFile)); assertContentEquals(legacyBytes, Files.readAllBytes(legacyFile))
                beforePc.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(pcRoot.resolve(path))) }
            } finally { base.toFile().deleteRecursively() }
        }
    }
}
