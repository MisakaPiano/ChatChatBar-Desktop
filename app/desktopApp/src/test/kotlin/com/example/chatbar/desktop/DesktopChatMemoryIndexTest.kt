package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopChatMemoryIndexTest {
    @Test fun `cancel after actual alternative deletion cannot recall its old raw vector through a cold reader`(): Unit = runBlocking {
        val f = Fixture(); f.setup()
        try {
            val old = assertNotNull(f.app.chatRepository.getMessage("message-2", f.session.id))
            val target = f.app.chatRepository.updateMessageReturningSnapshot(old.copy(
                alternatives = listOf(old.displayContent, "另一备选：旅人取消了原定行程。"),
                alternativeVersionIds = listOf("a", "b"), currentAlternativeVersionId = "a"))
            assertEquals(2, f.index.rebuild(f.session.id, 1))
            val embeddingRequests = f.server.requestCount
            val active = setOf("message-5", "message-6", "message-7", "message-8")
            val identity = DesktopRagEmbeddingIdentity.from(f.config)
            val before = f.index.candidates(f.session.id, active, identity)
            assertEquals(2, before.size)
            val stale = before.single { it.messageId == target.id }
            val service = DesktopSaveSlotService(f.app.appDataRoot, f.app.dataOperationCoordinator, f.maintenance,
                object : DesktopSessionReservation {
                    override suspend fun <T> run(sessionId: String, operation: suspend () -> T): T = operation()
                }, f.app.chatRepository, f.app.voiceRepository, { null }, mediaSource = { error("NO_MEDIA") })
            lateinit var operation: Deferred<Boolean>
            f.app.characterRepository.save(CharacterCard.create("Synthetic").copy(id = f.session.characterCardId))
            val primary = DesktopPrimaryChatController(f.app.characterRepository, f.app.chatRepository,
                f.app.settingsRepository, f.app.effectiveModelResolver, f.app.formatCardRepository,
                f.app.worldBookRepository, f.app.characterSessionService, f.app.characterResourceStore,
                taskRuntime = f.app.taskRuntime, alternativeRemoval = { previous, replacement, staleMessage ->
                    service.commitRemovedAlternatives(previous, replacement, staleMessage).also {
                        operation.cancel(CancellationException("Synthetic page closes after durable deletion"))
                    }
                })
            try {
                primary.refresh(); primary.selectSession(f.session.id)
                operation = async(start = CoroutineStart.LAZY) { primary.deleteAssistantAlternative(target, "a") }
                operation.start()
                assertFailsWith<CancellationException> { operation.await() }
                val coldStorage = com.example.chatbar.data.local.JsonFileStorage(f.app.appDataRoot)
                val current = assertNotNull(com.example.chatbar.data.repository.ChatRepository(coldStorage)
                    .getMessage(target.id, f.session.id))
                assertEquals(listOf("另一备选：旅人取消了原定行程。"), current.alternatives)
                // Keep the stale persisted vector deliberately: reader safety must not depend on
                // a page-owned rebuild/cleanup completing after the source transaction.
                assertEquals(stale, coldStorage.loadEntity("vector_chunks", stale.id, VectorChunk.serializer()))
                val coldIndex = DesktopChatMemoryIndex(f.app.appDataRoot, coldStorage, f.app.dataOperationCoordinator,
                    f.maintenance, { f.config }, f.gateway)
                val after = coldIndex.candidates(f.session.id, active, identity)
                assertEquals(before.filterNot { it.id == stale.id }, after)
                assertTrue(after.none { it.id == stale.id })
                assertEquals(embeddingRequests, f.server.requestCount, "Reading after deletion must not require re-embedding")
                assertFalse(DesktopSessionCommitJournal(f.app.appDataRoot).hasPending())
            } finally { primary.closeDraftPersistence() }
        } finally { f.close() }
    }

    @Test fun `late history stop keeps committed raw turn vectors and reports pending verification without changing messages`(): Unit = runBlocking {
        val f = Fixture(); f.setup()
        val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        val held = java.util.concurrent.atomic.AtomicBoolean()
        val index = DesktopChatMemoryIndex(f.app.appDataRoot, f.app.jsonFileStorage, f.app.dataOperationCoordinator,
            f.maintenance, { f.config }, f.gateway, DesktopIndexCommitJournal(f.app.appDataRoot) {
                if (held.compareAndSet(false, true)) { entered.complete(Unit); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            })
        val controller = DesktopRagController(f.app.modelRepository, f.app.settingsRepository,
            historyIndex = { session -> f.app.taskRuntime.withDocumentPreparation("chat-memory:$session", "history",
                usageContext = DesktopUsageContext(DesktopUsageSource.CHAT, sessionId = session)) { index.rebuild(session, 1) } },
            index = { _, _, _ -> error("NO_DOCUMENT_INDEX") })
        try {
            val messages = f.app.chatRepository.getMessagesReadOnly(f.session.id)
            controller.startHistory(f.session.id)
            withTimeout(5000) { entered.await() }
            val row = withTimeout(5000) { f.app.taskCenter.state.first { it.rows.any { row -> row.kind == DesktopWorkKind.HISTORY_INDEX && row.active } } }
                .rows.single { it.kind == DesktopWorkKind.HISTORY_INDEX && it.active }
            assertEquals(f.session.id, row.target!!.sessionId)
            assertTrue(f.app.taskRuntime.requestUserStop(row.id.substringAfter('/')))
            assertTrue(f.app.taskRuntime.hasDocumentPreparation("chat-memory:${f.session.id}", "history"))
            release.countDown()
            withTimeout(5000) { f.app.taskCenter.state.first { it.rows.any { item -> item.id == row.id && item.phase == DesktopWorkPhase.UNCONFIRMED } } }
            val progress = withTimeout(5000) { controller.progress.first { it[controller.historyKey(f.session.id)]?.running == false } }
                .getValue(controller.historyKey(f.session.id))
            assertTrue(progress.message.contains("待核对")); assertFalse(progress.message.contains("原有索引保留"))
            assertTrue(f.app.taskRuntime.ownedWork.value.single().publicationUnconfirmed)
            assertEquals(2, f.app.jsonFileStorage.loadAll("vector_chunks", VectorChunk.serializer()).size)
            assertEquals(messages, f.app.chatRepository.getMessagesReadOnly(f.session.id))
            assertFalse(DesktopIndexCommitJournal(f.app.appDataRoot).hasPending())
        } finally { release.countDown(); controller.closeAndDrain(); f.close() }
    }

    private class Fixture {
        val base = Files.createTempDirectory("chat-memory-index-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(base.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val server = MockWebServer()
        val session = ChatSession.create("card", "Synthetic")
        val focus = DesktopAudioFocus()
        val maintenance = DesktopAudioMaintenance({ focus }) { error("No playback in fixture") }
        lateinit var owner: DesktopDataRootOwnership
        lateinit var config: EmbeddingConfig
        lateinit var gateway: DesktopEmbeddingGateway
        lateinit var index: DesktopChatMemoryIndex
        suspend fun setup() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val count = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("input").jsonArray.size
                    return MockResponse().setBody(buildJsonObject { put("data", buildJsonArray {
                        repeat(count) { i -> add(buildJsonObject { put("index", i); put("embedding", buildJsonArray { add(1); add(0) }) }) }
                    }) }.toString())
                }
            }
            server.start()
            config = EmbeddingConfig("default", "Synthetic", server.url("/v1").toString(), "synthetic", "test", 2)
            gateway = DesktopEmbeddingGateway({ config }, client = OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            app.chatRepository.createSession(session)
            repeat(4) { i -> for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                val time = i * 2L + if (role == MessageRole.USER) 1 else 2
                app.chatRepository.addMessage(ChatMessage("message-$time", session.id, role,
                    "合成第${i + 1}轮：旅人在公园准备旅行。", createdAt = time, updatedAt = time,
                    sourceTurnId = "turn-$i", sourceTurnOrder = i.toLong()))
            } }
            owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(app.resolvedRoot)).ownership
            index = DesktopChatMemoryIndex(app.appDataRoot, app.jsonFileStorage, app.dataOperationCoordinator,
                maintenance, { config }, gateway)
        }
        suspend fun close() { gateway.closeAndDrain(); server.close(); app.close(); owner.close(); base.toFile().deleteRecursively() }
    }
    @Test fun `real automatic index omits menu alternatives carries choice provenance and rejects changed source menus`() = runBlocking {
        val f = Fixture(); f.setup()
        try {
            val repository = f.app.chatRepository
            val format = ActionOptionsFormat("[choices]", "[/choices]", "Choices", ActionOptionLabels.LETTERS)
            val original = repository.getMessage("message-2", f.session.id)!!
            val menu = original.copy(content = "星图展开了。\n[choices]\nChoices\nA. 封好地图\nB. 把铜钥匙交给天文学家\n[/choices]",
                actionOptionsFormat = format)
            repository.updateMessage(menu)
            val user = repository.getMessage("message-3", f.session.id)!!
            repository.updateMessage(user.copy(content = "B"))
            // Source time is fixed before the user; ordinary edit timestamps cannot prove that relation.
            val storedMenu = repository.getMessage(menu.id, f.session.id)!!
            val storedUser = repository.getMessage(user.id, f.session.id)!!
            f.app.jsonFileStorage.saveEntity("chat_messages", "${f.session.id}_${storedMenu.id}",
                storedMenu.copy(createdAt = 2, updatedAt = 2), ChatMessage.serializer())
            f.app.jsonFileStorage.saveEntity("chat_messages", "${f.session.id}_${storedUser.id}",
                storedUser.copy(createdAt = 3, updatedAt = 3), ChatMessage.serializer())
            assertEquals(2, f.index.rebuild(f.session.id, 1))
            val identity = DesktopRagEmbeddingIdentity.from(f.config)
            val candidates = f.index.candidates(f.session.id, emptySet(), identity)
            assertTrue(candidates.none { "封好地图" in it.content || "[choices]" in it.content }, "Unselected menu leaked into automatic retrieval")
            val selected = candidates.single { it.metadata["sourceTurnId"] == "turn-1" }
            assertTrue("把铜钥匙交给天文学家" in selected.content, "Confirmed selection was lost")
            assertEquals(menu.id, selected.metadata["actionChoiceSourceIds"])
            repository.updateMessage(storedMenu.copy(content = "星图展开了。\n[choices]\nChoices\nA. 交出铜钥匙\nB. 改走地下通道\n[/choices]", updatedAt = 99))
            assertTrue(f.index.candidates(f.session.id, emptySet(), identity).none { "把铜钥匙交给天文学家" in it.content },
                "A derived selection must not survive the menu identity changing")
        } finally { f.close() }
    }

    @Test fun `raw turn publication is idempotent excludes active context and rejects changed sources`(): Unit = runBlocking {
        val f = Fixture(); f.setup()
        try {
            assertEquals(2, f.index.rebuild(f.session.id, 1))
            assertEquals(2, f.server.requestCount)
            val identity = DesktopRagEmbeddingIdentity.from(f.config)
            val matches = f.index.candidates(f.session.id, setOf("message-5", "message-6"), identity)
            assertEquals(2, matches.size)
            assertTrue(matches.all { it.metadata["indexMode"] == "timeline_turn" && it.metadata["contentVersion"] == com.example.chatbar.domain.rag.ChatMemoryIndexPolicy.CONTENT_VERSION })
            assertEquals(0, f.index.rebuild(f.session.id, 1))
            assertEquals(2, f.server.requestCount)
            val changed = f.app.chatRepository.getMessage("message-1", f.session.id)!!
            f.app.chatRepository.updateMessage(changed.copy(content = "合成历史已修改：旅行取消。", updatedAt = 99))
            assertEquals(1, f.index.candidates(f.session.id, setOf("message-5", "message-6"), identity).size)
            assertEquals(1, f.index.rebuild(f.session.id, 1))
            assertEquals(3, f.server.requestCount)
            val records = f.app.jsonFileStorage.loadAll("vector_chunks", VectorChunk.serializer())
            assertEquals(2, records.size)
            assertTrue(records.any { "旅行取消" in it.content })
            assertFalse(DesktopIndexCommitJournal(f.app.appDataRoot).hasPending())
        } finally { f.close() }
    }
    @Test fun `busy audio reservation rejects indexing before network and later retry succeeds`(): Unit = runBlocking {
        val f = Fixture(); f.setup()
        try {
            f.maintenance.withMaintenance(false, { false }) {
                assertFailsWith<DesktopAudioMaintenanceBusyException> { f.index.rebuild(f.session.id, 1) }
            }
            assertEquals(0, f.server.requestCount)
            assertEquals(2, f.index.rebuild(f.session.id, 1))
            f.maintenance.withMaintenance(false, { false }) {
                f.app.dataOperationCoordinator.withNormalOperation {
                    assertEquals(2, f.index.candidates(f.session.id, setOf("message-5", "message-6", "message-7", "message-8"),
                        DesktopRagEmbeddingIdentity.from(f.config)).size)
                }
            }
            f.app.dataOperationCoordinator.withExclusiveMaintenance { }
        } finally { f.close() }
    }
}
