package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.MemoryRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.memory.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopMemoryInjectionTransportTest {
    @Test fun `real provider serialization includes one owning archive and head in order while disabled and stale sources stay out`(): Unit = runBlocking {
        val parent = Files.createTempDirectory("memory-final-wire-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { path -> check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        val repository = MemoryRepository(app.jsonFileStorage, strictReads = true, dataGate = app.dataOperationCoordinator)
        var aiCalls = 0
        val noAi = object : MemoryAiClient {
            override suspend fun episode(model: ModelConfig, renderedTurns: String, summaryPromptMaxChars: Int,
                onStreamingSummary: ((String) -> Unit)?, validate: (EpisodeResponse) -> Unit): EpisodeResponse { aiCalls++; error("Fixture does not generate memories") }
            override suspend fun compression(model: ModelConfig, kind: MemoryCompressionKind, forcedConsumedChildIds: List<String>,
                renderedChildren: String, onStreamingSummary: ((String) -> Unit)?, validate: (CompressionResponse) -> Unit): CompressionResponse { aiCalls++; error("Fixture does not compress memories") }
            override suspend fun head(model: ModelConfig, mode: MemoryHeadUpdateMode, throughT: Long, currentHead: String,
                archive: String, sourceTurns: String, validate: (HeadResponse) -> Unit): HeadResponse { aiCalls++; error("Fixture does not generate HEAD") }
        }
        val sources = DesktopMemorySources(app.appDataRoot, app.jsonFileStorage, app.chatRepository)
        val service = LongTermMemoryService(app.chatRepository, repository, app.settingsRepository, noAi,
            app.contextWindowManager, app.dataOperationCoordinator, sources::ensure, sources::read)
        val memory = DesktopMemoryController(app.jsonFileStorage, app.chatRepository, app.settingsRepository,
            app.effectiveModelResolver, service, repository, app.dataOperationCoordinator)
        val planner = DesktopChatRequestPlanner(app.characterRepository, app.chatRepository, app.formatCardRepository,
            app.contextWindowManager, app.worldBookRequestPlanner, app.promptAssembler, app.mainChatRequestAssembler,
            memoryView = memory::promptView)
        val runtime = DesktopRealChatRuntime(app.characterRepository, app.chatRepository, app.settingsRepository,
            app.characterSessionService, app.effectiveModelResolver, planner)
        try {
            MockWebServer().use { server ->
                val model = ModelConfig("model", "Synthetic", server.url("/v1").toString(), "synthetic-key", "synthetic-model", createdAt = 1)
                app.modelRepository.saveModel(model)
                app.settingsRepository.saveAppSettings(AppSettings(defaultModelId = model.id, allowCleartextModelApi = true,
                    defaultContextWindowSize = 2, ragInjectionMode = "OFF"))
                val card = CharacterCard.create("Synthetic card")
                app.characterRepository.save(card)
                suspend fun seed(label: String): ChatSession {
                    val session = app.chatRepository.createSession(ChatSession.create(card.id, label).copy(longTermMemoryEnabled = true))
                    repeat(3) { index ->
                        listOf(MessageRole.USER, MessageRole.ASSISTANT).forEachIndexed { offset, role ->
                            val time = index * 2L + offset + 1
                            app.chatRepository.addMessage(ChatMessage("$label-message-$time", session.id, role,
                                "$label source $index ${role.name}", createdAt = time, updatedAt = time,
                                sourceTurnId = "$label-turn-$index", sourceTurnOrder = index.toLong()))
                        }
                    }
                    service.ensureMigrated(session.id)
                    val messages = app.chatRepository.getMessages(session.id)
                    val hashes = (0..1).associate { "$label-turn-$it" to MemorySourceFingerprint.semantic("$label-turn-$it", messages, session) }
                    val node = MemoryNode("$label-node", session.id, MemoryTier.EPISODE, sourceTurnIds = listOf("$label-turn-0"),
                        content = "WIRE_ARCHIVE_$label", sourceFingerprints = hashes.filterKeys { it.endsWith("-0") })
                    repository.saveNodes(listOf(node))
                    val state = assertNotNull(repository.getState(session.id))
                    repository.saveState(state.copy(episodePage = state.episodePage.copy(activeNodeIds = listOf(node.id)),
                        head = MemoryHead(throughSourceTurnId = "$label-turn-1", location = "WIRE_HEAD_$label", sourceFingerprints = hashes)))
                    return session
                }
                val a = seed("A"); val b = seed("B")
                suspend fun wire(session: ChatSession, text: String, regenerate: String? = null): Pair<JsonArray, DesktopRealChatResult> {
                    server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"synthetic reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                    // Freeze this committed synthetic snapshot; ordinary maintenance ownership is tested separately.
                    val result = memory.withSessionQuiesced(session.id) {
                        withTimeout(10_000) { if (regenerate == null) runtime.sendText(session.id, text) else runtime.regenerate(session.id, regenerate) }
                    }
                    val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    assertEquals("/v1/chat/completions", request.path)
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    assertEquals(model.modelName, body.getValue("model").jsonPrimitive.content)
                    return body.getValue("messages").jsonArray to result
                }
                fun contents(messages: JsonArray) = messages.map { it.jsonObject.getValue("content").jsonPrimitive.content }
                fun owning(messages: JsonArray, label: String) {
                    val text = contents(messages)
                    assertEquals(1, text.count { it.contains("WIRE_ARCHIVE_$label") })
                    assertEquals(1, text.count { it.contains("WIRE_HEAD_$label") })
                    val archive = text.indexOfFirst { it.contains("WIRE_ARCHIVE_$label") }
                    val head = text.indexOfFirst { it.contains("WIRE_HEAD_$label") }
                    val previous = text.indexOfLast { it.contains("$label source 2") }
                    assertTrue(archive < head)
                    assertTrue(head < previous, "HEAD must precede the retained complete previous turn")
                    // Real local HTTP transport uses the existing chat-template role adaptation.
                    assertEquals("assistant", messages[archive].jsonObject.getValue("role").jsonPrimitive.content)
                    assertFalse(text.any { it.contains("WIRE_ARCHIVE_${if (label == "A") "B" else "A"}") || it.contains("WIRE_HEAD_${if (label == "A") "B" else "A"}") })
                }
                val (first, result) = wire(a, "WIRE_USER_A")
                owning(first, "A")
                val (second, _) = wire(b, "WIRE_USER_B")
                owning(second, "B")
                val (retry, _) = wire(a, "", regenerate = result.assistant.id)
                owning(retry, "A")
                val current = assertNotNull(app.chatRepository.getSession(a.id))
                app.chatRepository.saveSessionSettingsDraft(current, current.copy(longTermMemoryEnabled = false))
                val (off, _) = wire(a, "WIRE_DISABLED")
                assertFalse(contents(off).any { it.contains("WIRE_ARCHIVE_A") || it.contains("WIRE_HEAD_A") })
                val disabled = assertNotNull(app.chatRepository.getSession(a.id))
                app.chatRepository.saveSessionSettingsDraft(disabled, disabled.copy(longTermMemoryEnabled = true))
                val source = app.chatRepository.getMessages(a.id).first { it.id == "A-message-2" }
                app.chatRepository.updateMessage(source.copy(content = "Changed source with different facts"))
                val (stale, _) = wire(a, "WIRE_SOURCE_CHANGED")
                assertFalse(contents(stale).any { it.contains("WIRE_ARCHIVE_A") || it.contains("WIRE_HEAD_A") })
                assertNotNull(repository.getNode("A-node"))
                assertEquals("WIRE_HEAD_B", repository.getState(b.id)!!.head.location)
                assertEquals(5, server.requestCount)
                assertEquals(0, aiCalls)
            }
        } finally { memory.closeAndDrain(); app.close(); parent.toFile().deleteRecursively() }
    }
}
