package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopChatMemoryIndexTest {
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
    @Test fun `raw turn publication is idempotent excludes active context and rejects changed sources`(): Unit = runBlocking {
        val f = Fixture(); f.setup()
        try {
            assertEquals(2, f.index.rebuild(f.session.id, 1))
            assertEquals(2, f.server.requestCount)
            val identity = DesktopRagEmbeddingIdentity.from(f.config)
            val matches = f.index.candidates(f.session.id, setOf("message-5", "message-6"), identity)
            assertEquals(2, matches.size)
            assertTrue(matches.all { it.metadata["indexMode"] == "timeline_turn" && it.metadata["contentVersion"] == "7" })
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
