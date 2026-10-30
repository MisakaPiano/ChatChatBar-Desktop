package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopChatMaintenanceControllerTest {
    private class Fixture {
        val base = Files.createTempDirectory("chat-maintenance-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(base.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val session = ChatSession("session", "card", "Synthetic", createdAt = 1, updatedAt = 1)
        var reserved = false
        var refuse = false
        var detections = 0
        var refreshes = 0
        var detectionFailure = false
        var rebuildCalls = 0
        var rebuild: suspend () -> Int = { 2 }
        val controller = DesktopChatMaintenanceController(app.chatRepository, app.dataOperationCoordinator,
            object : DesktopSessionReservation {
                override suspend fun <T> run(sessionId: String, operation: suspend () -> T): T {
                    if (refuse) throw DesktopTaskAdmissionException("synthetic busy")
                    check(!reserved); reserved = true
                    try { return operation() } finally { reserved = false }
                }
            }, rebuildDocuments = { _, progress -> rebuildCalls++; progress(1, 2); rebuild() },
            detectSourceChanges = { check(reserved); detections++; if (detectionFailure) error("synthetic failure") },
            refreshChat = { check(!reserved); refreshes++ })
        suspend fun seed() {
            app.chatRepository.createSession(session)
            app.chatRepository.replaceMessagesForSession(session.id, listOf(
                msg("u1", MessageRole.USER, 1, "turn1", 0),
                msg("a1", MessageRole.ASSISTANT, 2, "turn1", 0),
                msg("u2", MessageRole.USER, 3, "turn2", 1),
                msg("a2", MessageRole.ASSISTANT, 4, "turn2", 1),
                msg("image", MessageRole.ASSISTANT, 5, "turn1", 0).copy(generatedFromMessageId = "a1", images = listOf("synthetic.png"))))
        }
        private fun msg(id: String, role: MessageRole, order: Long, turn: String, sourceOrder: Long) =
            ChatMessage(id, session.id, role, "synthetic-$id", createdAt = order, updatedAt = order,
                orderKey = order * MESSAGE_ORDER_STEP, sourceTurnId = turn, sourceTurnOrder = sourceOrder)
        suspend fun idle() { withTimeout(5000) { while (controller.state.value.busy) delay(10) } }
        suspend fun close() { controller.closeAndDrain(); app.close(); base.toFile().deleteRecursively() }
    }

    @Test fun `preview is read only and apply undo preserve source content media and timestamps`() = runBlocking {
        val f = Fixture(); f.seed()
        try {
            val original = f.app.chatRepository.getMessages(f.session.id)
            f.controller.inspectOrder(f.session.id); f.idle()
            assertTrue(f.controller.state.value.preview!!.requiresRepair)
            assertEquals(original, f.app.chatRepository.getMessages(f.session.id))
            assertFalse(f.app.chatRepository.hasMessageOrderBackup(f.session.id))
            assertEquals(0, f.detections)
            f.controller.applyOrder(f.session.id); f.idle()
            assertNull(f.controller.state.value.error)
            val repaired = f.app.chatRepository.getMessages(f.session.id)
            assertEquals(listOf("u1", "a1", "image", "u2", "a2"), repaired.map { it.id })
            repaired.forEach { value -> assertEquals(original.single { it.id == value.id }, value.copy(orderKey = original.single { it.id == value.id }.orderKey)) }
            assertTrue(f.controller.state.value.backupAvailable)
            assertEquals(1, f.detections); assertEquals(1, f.refreshes)
            f.controller.undoOrder(f.session.id); f.idle()
            assertEquals(original, f.app.chatRepository.getMessages(f.session.id))
            assertFalse(f.controller.state.value.backupAvailable)
            assertEquals(2, f.detections); assertEquals(2, f.refreshes)
        } finally { f.close() }
    }

    @Test fun `stale preview and busy session refuse without writes and changed content refuses undo`() = runBlocking {
        val f = Fixture(); f.seed()
        try {
            f.refuse = true
            f.controller.inspectOrder(f.session.id); f.idle()
            assertNotNull(f.controller.state.value.error); assertNull(f.controller.state.value.preview)
            f.refuse = false
            f.controller.inspectOrder(f.session.id); f.idle()
            val first = f.app.chatRepository.getMessages(f.session.id).first()
            f.app.chatRepository.updateMessage(first.copy(content = "changed", updatedAt = 99))
            val changed = f.app.chatRepository.getMessages(f.session.id)
            f.controller.applyOrder(f.session.id); f.idle()
            assertNotNull(f.controller.state.value.error)
            assertEquals(changed, f.app.chatRepository.getMessages(f.session.id))
            assertFalse(f.app.chatRepository.hasMessageOrderBackup(f.session.id))
            f.controller.inspectOrder(f.session.id); f.idle()
            f.controller.applyOrder(f.session.id); f.idle()
            assertNull(f.controller.state.value.error)
            f.app.chatRepository.updateMessage(f.app.chatRepository.getMessages(f.session.id).first().copy(content = "changed again", updatedAt = 100))
            val after = f.app.chatRepository.getMessages(f.session.id)
            f.controller.undoOrder(f.session.id); f.idle()
            assertNotNull(f.controller.state.value.error)
            assertEquals(after, f.app.chatRepository.getMessages(f.session.id))
            assertTrue(f.app.chatRepository.hasMessageOrderBackup(f.session.id))
        } finally { f.close() }
    }

    @Test fun `source check failure reports committed order and retains undo`() = runBlocking {
        val f = Fixture(); f.seed()
        try {
            f.controller.inspectOrder(f.session.id); f.idle()
            f.detectionFailure = true
            f.controller.applyOrder(f.session.id); f.idle()
            assertTrue(f.controller.state.value.message.contains("已保存"))
            assertTrue(f.controller.state.value.error!!.contains("来源检查未完成"))
            assertTrue(f.controller.state.value.backupAvailable)
            assertEquals(1, f.refreshes)
        } finally { f.close() }
    }

    @Test fun `RAG is explicit survives page absence and stop remains busy until cleanup joins`() = runBlocking {
        val f = Fixture(); f.seed()
        val entered = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            assertEquals(0, f.rebuildCalls)
            f.rebuild = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
            }
            f.controller.rebuildRag(f.session.id); entered.await()
            f.controller.rebuildRag("other")
            assertEquals(1, f.rebuildCalls)
            f.controller.stop(); cleanup.await()
            assertTrue(f.controller.state.value.busy)
            f.controller.inspectOrder("other")
            assertEquals(f.session.id, f.controller.state.value.sessionId)
            release.complete(Unit); f.idle()
            assertTrue(f.controller.state.value.message.contains("已停止"))
            f.rebuild = { 2 }; f.controller.rebuildRag(f.session.id); f.idle()
            assertEquals(2, f.rebuildCalls)
            assertTrue(f.controller.state.value.message.contains("2 份"))
            f.controller.closeAndDrain()
            f.controller.rebuildRag(f.session.id); assertEquals(2, f.rebuildCalls)
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun `container rebuild uses current cards reference documents and preserves history chunks`() = runBlocking {
        val f = Fixture(); f.seed()
        val server = MockWebServer()
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(f.app.resolvedRoot)).ownership
        try {
            server.start()
            val documents = listOf("one", "two").map { DocumentInfo(it, "$it.txt", "documents/$it.txt", "txt", 1) }
            val card = CharacterCard.create("Synthetic").copy(id = "card", customDocuments = documents)
            f.app.characterRepository.save(card)
            Files.createDirectories(f.app.appDataRoot.resolve("documents"))
            documents.forEach { Files.writeString(f.app.appDataRoot.resolve(it.filePath), "Synthetic ${it.id} reference text.") }
            f.app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true) }
            f.app.modelRepository.saveEmbeddingModel(EmbeddingConfig("default", "Synthetic", server.url("/v1").toString(), "fake-embedding-key", "test", 2))
            val history = VectorChunk("history", ChunkSourceType.CHAT_MEMORY, f.session.id,
                content = "synthetic history", embedding = listOf(1f, 0f), createdAt = 1)
            f.app.jsonFileStorage.saveEntity("vector_chunks", history.id, history, VectorChunk.serializer())
            repeat(2) { server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1,0]}]}""")) }
            val maintenance = f.app.chatMaintenanceController
            assertEquals(0, server.requestCount)
            maintenance.rebuildRag(f.session.id)
            withTimeout(10_000) { while (maintenance.state.value.busy) delay(10) }
            assertNull(maintenance.state.value.error)
            assertEquals(2, server.requestCount)
            assertTrue(f.app.characterRepository.getById(card.id)!!.customDocuments.all { it.ragStatus == "INDEXED" })
            assertEquals(history, f.app.jsonFileStorage.loadEntity("vector_chunks", history.id, VectorChunk.serializer()))
            assertFalse(DesktopIndexCommitJournal(f.app.appDataRoot).hasPending())
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.app.dataOperationCoordinator.state)
        } finally { server.close(); f.close(); owner.close() }
    }
}
