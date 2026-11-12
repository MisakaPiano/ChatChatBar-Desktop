package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.UUID
import kotlin.test.*

class LanSyncConversationTest {
    private class Fixture {
        val root = Files.createTempDirectory("ccb-lan-conversation-").toRealPath()
        val state = Files.createTempDirectory("ccb-lan-conversation-state-").toRealPath()
        val storage = JsonFileStorage(root)
        val chats = ChatRepository(storage)
        val memory = MemoryRepository(storage, strictReads = true)
        val workspace = LanSyncWorkspace(root, state, { Path.of(it) })
        suspend fun seed() {
            storage.saveEntity("character_cards", "card", CharacterCard("card", "合成角色", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            chats.createSession(ChatSession("session", "card", "合成会话", modelId = "device-local-model", createdAt = 1, updatedAt = 1))
            val image = root.resolve("images/original.png"); Files.createDirectories(image.parent)
            Files.write(image, java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="))
            chats.addMessage(ChatMessage("message", "session", MessageRole.ASSISTANT, "初始正文", images = listOf(image.toString()),
                alternatives = listOf("旧版本", "初始正文"), alternativeVersionIds = listOf("version-0", "version-1"),
                currentAlternativeIndex = 1, currentAlternativeVersionId = "version-1", sourceTurnId = "source-turn", sourceTurnOrder = 1,
                createdAt = 1, updatedAt = 1, orderKey = 12345))
            memory.saveNodes(listOf(MemoryNode("node", "session", MemoryTier.EPISODE, sourceTurnIds = listOf("source-turn"), content = "合成记忆正文", createdAt = 1)))
            memory.saveState(MemorySessionState("session", episodePage = MemoryPageState(MemoryTier.EPISODE, activeNodeIds = listOf("node"))))
            storage.saveEntity("voice_anchor_states", UUID.nameUUIDFromBytes("message\u0000version-1".toByteArray()).toString(),
                VoiceAnchorState("message", "version-1", "session", "初始正文", listOf(VoiceAnchor("anchor", "DIALOGUE", "合成角色", "初始正文", 0, 4, 1)), updatedAt = 1), VoiceAnchorState.serializer())
        }
        suspend fun edit(text: String) {
            val old = requireNotNull(chats.getMessages("session").singleOrNull())
            storage.saveEntity("chat_messages", "session_message", old.copy(content = text, alternatives = listOf("旧版本", text), updatedAt = 2), ChatMessage.serializer())
        }
    }

    @Test fun realChatStorageBothWayForkRetainsVersionsMemoryMediaAndOrigin() = runBlocking {
        val a = Fixture(); val b = Fixture(); a.seed(); b.seed()
        // Use the same exact initial memory DTO, including its non-semantic creation metadata.
        Files.copy(a.root.resolve("entities/memory_states/session.json"), b.root.resolve("entities/memory_states/session.json"), StandardCopyOption.REPLACE_EXISTING)
        val before = a.workspace.capture("computer")
        try {
            a.edit("电脑修改正文"); b.edit("手机修改正文")
            val left = a.workspace.capture("computer"); val right = b.workspace.capture("phone")
            try {
                val baseline = LanSyncBaseline("phone", "previous", before.snapshot.hashes(), ownerId = "computer")
                val comparison = LanSyncCompare.compare(left.snapshot, right.snapshot, baseline)
                assertEquals(listOf("conversation/session"), comparison.conflicts.map { it.unit })
                val transaction = UUID.randomUUID().toString()
                val plan = LanSyncPlanner.plan(left.snapshot, right.snapshot, baseline, mapOf("conversation/session" to LanSyncChoice.KEEP_BOTH), transaction)
                val repeated = LanSyncPlanner.plan(left.snapshot, right.snapshot, baseline, mapOf("conversation/session" to LanSyncChoice.KEEP_BOTH), transaction)
                assertEquals(plan.snapshot.hashes(), repeated.snapshot.hashes())
                val blobs = left.blobs + right.blobs
                a.workspace.apply(plan, left.snapshot.hashes(), "computer", blobs)
                b.workspace.apply(plan, right.snapshot.hashes(), "phone", blobs)
                a.chats.reloadAfterLanSync(); b.chats.reloadAfterLanSync()
                val sessions = a.chats.getAllSessions()
                assertEquals(2, sessions.size)
                val fork = sessions.single { it.id != "session" }
                assertEquals("session", fork.lanSyncOrigin?.parentSessionId)
                assertEquals("device-local-model", a.chats.getSession("session")?.modelId)
                assertNull(fork.modelId)
                val copied = a.chats.getMessages(fork.id).single()
                assertEquals("手机修改正文", copied.displayContent)
                assertEquals(listOf("version-0", "version-1"), copied.alternativeVersionIds)
                assertEquals("version-1", copied.currentAlternativeVersionId)
                assertEquals("source-turn", copied.sourceTurnId); assertEquals(12345L, copied.orderKey)
                val originalImage = requireNotNull(a.chats.getMessages("session").singleOrNull()).images.single()
                assertContentEquals(Files.readAllBytes(Path.of(originalImage)), Files.readAllBytes(Path.of(copied.images.single())))
                val memoryState = requireNotNull(a.memory.getState(fork.id))
                val node = requireNotNull(a.memory.getNode(memoryState.episodePage.activeNodeIds.single()))
                assertEquals(fork.id, node.sessionId); assertEquals("合成记忆正文", node.content)
                val anchors = a.storage.loadAll("voice_anchor_states", VoiceAnchorState.serializer())
                assertTrue(anchors.any { it.sessionId == fork.id && it.messageId == copied.id && it.messageVersionId == "version-1" && it.anchors.single().id == "anchor" })
                assertEquals(a.workspace.capture("computer").use { it.snapshot.hashes() }, b.workspace.capture("phone").use { it.snapshot.hashes() })
                val fresh = ChatRepository(JsonFileStorage(a.root)); fresh.initialize()
                assertEquals("手机修改正文", fresh.getMessages(fork.id).single().displayContent)
            } finally { left.close(); right.close() }
        } finally { before.close() }
    }

    @Test fun actualV8LibraryTransferForkLoadsOriginalMessagesAndMedia() = runBlocking {
        val a = Fixture(); val b = Fixture(); a.seed(); b.seed()
        Files.copy(a.root.resolve("entities/memory_states/session.json"), b.root.resolve("entities/memory_states/session.json"), StandardCopyOption.REPLACE_EXISTING)
        val storage = SaveSlotPackageStorage(b.root.toFile(), { _, _ -> error("original images only") })
        val messages = b.chats.getMessages("session")
        val saved = storage.createPackage(SaveSlot(id = "slot", sessionId = "session", name = "合成存档", createdAt = 1),
            SaveSlotImagePolicy.ORIGINAL, false, { emit -> messages.forEach { emit(it) } }, {}, emptyList())
        b.storage.saveEntity("save_slots", saved.id, saved, SaveSlot.serializer())
        val left = a.workspace.capture("computer"); val right = b.workspace.capture("phone")
        try {
            val plan = LanSyncPlanner.plan(left.snapshot, right.snapshot, LanSyncBaseline("phone"),
                mapOf("conversation/session" to LanSyncChoice.KEEP_BOTH), UUID.randomUUID().toString())
            // A first-pair conversation differs because only the phone owns the slot; preserve both graphs.
            a.workspace.apply(plan, left.snapshot.hashes(), "computer", left.blobs + right.blobs)
            a.chats.reloadAfterLanSync()
            val fork = a.chats.getAllSessions().single { it.id != "session" }
            val incoming = a.storage.loadAll("save_slots", SaveSlot.serializer()).single()
            assertEquals(fork.id, incoming.sessionId); assertNotEquals("slot", incoming.id)
            val sourceBytes = Files.readAllBytes(b.root.resolve("save_slot_packages/${saved.packageRef!!.fileName}"))
            assertContentEquals(sourceBytes, Files.readAllBytes(a.root.resolve("save_slot_packages/${incoming.packageRef!!.fileName}")))
            val destinationStorage = SaveSlotPackageStorage(a.root.toFile(), { _, _ -> error("original images only") })
            val restored = mutableListOf<ChatMessage>()
            destinationStorage.openReader(incoming).use { reader -> reader.validate(); reader.streamMessages(fork.id) { restored += it } }
            assertEquals(listOf("version-0", "version-1"), restored.single().alternativeVersionIds)
            assertEquals("初始正文", restored.single().displayContent)
            assertEquals(fork.id, restored.single().sessionId)
            assertContentEquals(Files.readAllBytes(Path.of(messages.single().images.single())), Files.readAllBytes(Path.of(restored.single().images.single())))
        } finally { left.close(); right.close() }
    }
}
