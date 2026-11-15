package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.chat.OpenAiStreamingTransport
import com.example.chatbar.domain.memory.*
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import com.example.chatbar.domain.rag.ChatMemoryTurn
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

/** Characterization only: real memory service/gateway/transport, synthetic loopback responses.
 * It observes the existing raw-source boundary, not model semantic quality or a filtering fix.
 */
class DesktopMemorySourceWireAuditTest {
    private data class Reference(val name: String, val user: String, val image: Boolean = false)

    @Test fun `actual Episode and three HEAD modes preserve raw option sources and referenced USER text`(): Unit = runBlocking {
        val references = listOf(
            Reference("independent", "我们改为讨论明天城门开放的时间。"),
            Reference("numeric", "2"),
            Reference("pronoun", "Tomorrow we will do the same thing she suggested."),
            Reference("image", "请按这张图的做法安排接下来的行动。", image = true)
        )
        for (reference in references) {
            val f = Fixture(reference)
            try {
                f.setup()
                f.service.maintainHeadAutomatically(f.session.id, f.model)
                assertEquals(listOf(MemoryHeadUpdateMode.INITIALIZE), f.headModes)
                f.addTurn(3)
                val sources = f.chats.getMessages(f.session.id)
                val fingerprintBefore = f.fingerprints(sources)
                f.service.updateHeadAfterReply(f.session.id, f.model)
                assertEquals(listOf(MemoryHeadUpdateMode.INITIALIZE, MemoryHeadUpdateMode.UPDATE), f.headModes)
                val result = f.service.updateArchiveAfterReply(f.session.id, f.model, 0, maxEpisodeBatches = 1)
                assertEquals(1, result.committedEpisodes)
                assertEquals(1, f.episodes.size)
                assertTrue(f.episodes.single().contains("MEMORY_OPTION_0"))
                assertTrue(f.episodes.single().contains("MEMORY_OPTION_1"))
                assertTrue(f.episodes.single().contains(reference.user))
                f.service.regenerateHeadFromArchive(f.session.id, f.model)
                assertEquals(MemoryHeadUpdateMode.entries.toSet(), f.headModes.toSet())
                assertEquals(4, f.wires.size)
                val headInit = f.wires.single { it.first == "HEAD_INITIALIZE" }.second
                assertTrue(headInit.toString().contains("MEMORY_OPTION_0"))
                assertTrue(headInit.toString().contains("MEMORY_OPTION_1"))
                assertTrue(headInit.toString().contains(reference.user))
                for (stage in listOf("HEAD_UPDATE", "HEAD_BACKFILL")) {
                    assertTrue(f.wires.single { it.first == stage }.second.toString().contains("MEMORY_OPTION_2"))
                }
                val beforeState = f.repository.getState(f.session.id)!!
                val beforeNodes = f.service.activeNodes(f.session.id)
                assertFalse(beforeState.head.stale)
                val beforeFiles = f.files()
                val inspected = f.service.inspectPrompt(f.session.id)
                assertTrue(assertNotNull(inspected.prompt).headPresent)
                assertEquals(beforeFiles, f.files())
                assertEquals(4, f.server.requestCount)
                val coldStorage = JsonFileStorage(f.root, f.gate)
                val coldChats = ChatRepository(coldStorage)
                assertEquals(sources, coldChats.getMessages(f.session.id))
                assertEquals(fingerprintBefore, f.fingerprints(coldChats.getMessages(f.session.id)))
                val coldMemory = MemoryRepository(coldStorage, true, f.gate)
                assertEquals(beforeState, coldMemory.getState(f.session.id))
                assertEquals(beforeNodes, beforeNodes.map { coldMemory.getNode(it.id)!! })
                val imageMessage = sources.single { it.role == MessageRole.USER && it.sourceTurnId == "turn-1" }
                if (reference.image) {
                    assertEquals(listOf(f.image.toString()), imageMessage.images)
                    assertContentEquals(f.imageBytes, Files.readAllBytes(f.image))
                }
                f.saveEvidence(sources, beforeState, fingerprintBefore)
            } finally { f.close() }
        }
    }

    @Test fun `new RAG index input already strips fenced status and long dash options independently of raw memory body`() {
        val assistant = ChatMessage("assistant", "s", MessageRole.ASSISTANT,
            "RAG_STORY_FACT\n```status\nRAG_STATUS_MARKER\n```\n————————\n【行动选项】\n1. RAG_OPTION_MARKER\n2. 去车站\n————————",
            createdAt = 1, updatedAt = 1, sourceTurnId = "turn", sourceTurnOrder = 0)
        val user = ChatMessage("user", "s", MessageRole.USER, "2", createdAt = 2, updatedAt = 2,
            sourceTurnId = "turn", sourceTurnOrder = 0)
        val index = ChatMemoryIndexPolicy.contentForIndex(ChatMemoryTurn("turn", 0, listOf(assistant, user)))
        assertTrue(index.contains("RAG_STORY_FACT"))
        assertFalse(index.contains("RAG_STATUS_MARKER"))
        assertFalse(index.contains("RAG_OPTION_MARKER"))
        assertTrue(index.contains("user:\n2"))
        assertTrue(MemorySourceFingerprint.modelFacingBody(assistant).contains("RAG_OPTION_MARKER"))
        assertTrue(MemorySourceFingerprint.modelFacingBody(assistant).contains("RAG_STATUS_MARKER"))
    }

    private class Fixture(val reference: Reference) {
        val root = Files.createTempDirectory("memory-source-wire-")
        val gate = DesktopDataOperationCoordinator()
        val storage = JsonFileStorage(root, gate)
        val chats = ChatRepository(storage)
        val settings = SettingsRepository(storage)
        val repository = MemoryRepository(storage, true, gate)
        val session = ChatSession.create("synthetic-card", "Synthetic memory source").copy(longTermMemoryEnabled = true)
        val server = MockWebServer().also { it.start() }
        val transport = OpenAiStreamingTransport { true }
        val model = ModelConfig.create("Synthetic", server.url("/v1").toString(), "synthetic-key", "synthetic-model")
            .copy(supportsJsonMode = true)
        val gateway = MemoryAiGateway(DesktopMemoryRagTextClient(transport))
        val headModes = mutableListOf<MemoryHeadUpdateMode>()
        val episodes = mutableListOf<String>()
        val wires = mutableListOf<Pair<String, JsonObject>>()
        val image = root.resolve("synthetic-reference.png")
        lateinit var imageBytes: ByteArray
        val ai = object : MemoryAiClient by gateway {
            override suspend fun episode(model: ModelConfig, renderedTurns: String, summaryPromptMaxChars: Int,
                onStreamingSummary: ((String) -> Unit)?, validate: (EpisodeResponse) -> Unit): EpisodeResponse {
                episodes += renderedTurns
                enqueue(buildJsonObject { put("summary", "合成旅人在广场商议路线，尚未执行待选行动。") })
                return gateway.episode(model, renderedTurns, summaryPromptMaxChars, onStreamingSummary, validate)
                    .also { capture("EPISODE") }
            }
            override suspend fun head(model: ModelConfig, mode: MemoryHeadUpdateMode, throughT: Long,
                currentHead: String, archive: String, sourceTurns: String, validate: (HeadResponse) -> Unit): HeadResponse {
                headModes += mode
                enqueue(buildJsonObject { put("throughT", throughT); put("location", "合成广场"); put("goals", "商议路线") })
                return gateway.head(model, mode, throughT, currentHead, archive, sourceTurns, validate)
                    .also { capture("HEAD_${mode.name}") }
            }
        }
        val service = LongTermMemoryService(chats, repository, settings, ai, ContextWindowManager(), gate,
            DesktopMemorySources(root, storage, chats)::ensure)

        suspend fun setup() {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", image.toFile())
            imageBytes = Files.readAllBytes(image)
            chats.createSession(session)
            settings.saveAppSettings(AppSettings(defaultContextWindowSize = 0, episodeMaxSourceTurns = 2))
            service.ensureMigrated(session.id)
            repeat(3) { addTurn(it) }
        }

        suspend fun addTurn(index: Int) {
            val user = if (index == 1) reference.user else "合成旅程第${index + 1}轮，请继续描述广场。"
            for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                val time = index * 2L + if (role == MessageRole.USER) 1 else 2
                val body = if (role == MessageRole.USER) user else
                    "MEMORY_STORY_$index 合成旅人仍在广场商议路线。\n————————\n【行动选项】\n1. MEMORY_OPTION_$index 去车站\n2. 留在广场\n————————\n```status\nMEMORY_STATUS_$index 位置：广场\n```"
                chats.addMessage(ChatMessage("message-$time", session.id, role, body,
                    images = if (index == 1 && role == MessageRole.USER && reference.image) listOf(image.toString()) else emptyList(),
                    createdAt = time, updatedAt = time, sourceTurnId = "turn-$index", sourceTurnOrder = index.toLong()))
            }
        }

        fun enqueue(body: JsonObject) {
            val delta = buildJsonObject { putJsonArray("choices") { addJsonObject {
                putJsonObject("delta") { put("content", body.toString()) }; put("finish_reason", "stop")
            } } }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $delta\n\ndata: [DONE]\n\n"))
        }

        fun capture(stage: String) {
            val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/v1/chat/completions", request.path)
            val wire = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("json_object", wire.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
            wires += stage to wire
        }

        suspend fun fingerprints(messages: List<ChatMessage>): Map<String, String> {
            val current = chats.getSession(session.id)!!
            return messages.mapNotNull { it.sourceTurnId }.distinct().associateWith {
                MemorySourceFingerprint.semantic(it, messages, current)
            }
        }

        fun files(): Map<String, List<Byte>> = Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate { root.relativize(it).toString() to Files.readAllBytes(it).toList() }
        }

        fun saveEvidence(messages: List<ChatMessage>, state: MemorySessionState, fingerprints: Map<String, String>) {
            val dir = System.getenv("CCB_MEMORY_SOURCE_AUDIT_DIR")?.let { Files.createDirectories(Path.of(it).resolve(reference.name)) } ?: return
            wires.forEach { (stage, wire) -> Files.writeString(dir.resolve("$stage.actual-request.json"), wire.toString()) }
            Files.writeString(dir.resolve("synthetic-persisted-sources.json"), Json.encodeToString(ListSerializer(ChatMessage.serializer()), messages))
            Files.writeString(dir.resolve("verified-boundaries.json"), buildJsonObject {
                put("kind", "EXISTING_MEMORY_RAW_SOURCE_CHARACTERIZATION")
                put("sourceAndFingerprintsColdUnchanged", true); put("existingNodesAndStateColdUnchanged", true)
                put("inspectionNoWritesOrRequests", true); put("requests", wires.size)
                put("imageReferencePreserved", reference.image); put("modelSemanticQualityTested", false)
                put("productionChanged", false); put("headStale", state.head.stale)
                putJsonObject("sourceFingerprints") { fingerprints.forEach { (key, value) -> put(key, value) } }
            }.toString())
        }

        suspend fun close() { transport.closeAndDrain(); server.shutdown(); gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }
}
