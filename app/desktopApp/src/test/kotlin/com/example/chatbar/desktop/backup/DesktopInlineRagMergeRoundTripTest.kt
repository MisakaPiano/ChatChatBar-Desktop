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
        for (choice in listOf(DesktopMergePreview.Choice.BOTH, DesktopMergePreview.Choice.TAKE_PHONE)) {
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
                    embedding = listOf(1f, 0f), metadata = mapOf("messageIds" to "m1,m2", "indexMode" to "message_pair", "note" to "m1,m2"), createdAt = 1)
                val manual = VectorChunk("manual", ChunkSourceType.CHAT_MEMORY, "s", content = "Independent manual summary",
                    embedding = listOf(0f, 1f), metadata = mapOf("origin" to "manual", "note" to "m1,m2"), createdAt = 1)
                val legacy = SaveSlot(schemaVersion = 7, id = "slot", sessionId = "s", name = "Original inline archive", createdAt = 1,
                    messages = listOf(first, second), vectorChunks = listOf(pair, manual))
                val legacyBytes = raw(SaveSlot.serializer(), legacy)
                val legacyFile = Files.write(base.resolve("original-inline.cbsave"), legacyBytes)
                assertEquals(2, DesktopSaveSlotInspector(true).inspect(legacyFile).counts?.messages)
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
                            val selected = preview.select(preview.conflicts.associate { it.key to choice })
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
