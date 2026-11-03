package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.NovelAiTextStage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopAuxiliaryUsageTest {
    @Test fun `real task runtime image work supplies actual task and chat identity while standalone work has no invented session`() = runBlocking {
        val parent = Files.createTempDirectory("usage-image-owner-")
        val root = parent.resolve("data")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        val transport = OpenAiStreamingTransport { true }
        var closed = false
        try {
            MockWebServer().use { server ->
                val character = CharacterCard.create("Fixture", greeting = "Fixture")
                container.characterRepository.save(character)
                val session = container.characterSessionService.createSessionForCharacter(character.id)
                val model = ModelConfig("model", "Fixture", server.url("/v1").toString(), "synthetic-only-key", "fixture", createdAt = 0)
                val expected = mutableMapOf<String, String?>()
                for (chatSession in listOf(session, null)) {
                    server.enqueue(response())
                    val id = container.taskRuntime.launchNovelAi("fixture image work", chatSession) {
                        DesktopNovelAiTextTransport(transport, container.taskRuntime.diagnostics)
                            .streamText(NovelAiTextStage.PLAN, listOf(ChatApiMessage.text("user", "fixture")), model).toList()
                    }
                    expected[id] = chatSession
                    withTimeout(10_000) {
                        container.taskRuntime.tasks.first { tasks -> tasks.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } }
                    }
                    assertEquals(DesktopTaskStatus.COMPLETED, container.taskRuntime.tasks.value.single { it.taskId == id }.status)
                }
                transport.closeAndDrain(); container.close(); closed = true
                val records = Files.list(root.resolve("diagnostics/provider-usage")).use { files ->
                    files.filter { it.fileName.toString().endsWith(".json") }.map {
                        Json.decodeFromString(DesktopUsageRecord.serializer(), Files.readString(it))
                    }.toList()
                }
                assertEquals(2, server.requestCount); assertEquals(2, records.size)
                assertEquals(expected.keys, records.map { it.taskId }.toSet())
                records.forEach { record ->
                    assertEquals(expected[record.taskId], record.sessionId)
                    assertEquals(if (record.sessionId == null) DesktopUsageSource.OTHER else DesktopUsageSource.CHAT, record.source)
                    assertNull(record.editorSessionId); assertNull(record.cardId)
                }
            }
        } finally {
            transport.closeAndDrain(); if (!closed) container.close(); parent.toFile().deleteRecursively()
        }
    }

    private fun response(finish: String = "stop") = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"private auxiliary result\"},\"finish_reason\":\"$finish\"}],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n")

    @Test fun `existing design stages and linked vision persist actual attempts with inherited parent and no duplicate explicit capture`() = runBlocking {
        val root = Files.createTempDirectory("usage-auxiliary-")
        val gate = DesktopDataOperationCoordinator()
        val store = DesktopProviderUsageStore(root, gate)
        val usage = DesktopProviderUsageOwner(store)
        val logs = DesktopTransportDiagnosticsOwner(usageSink = usage::submit)
        val transport = OpenAiStreamingTransport { true }
        try {
            MockWebServer().use { server ->
                val model = ModelConfig("model", "Fixture", server.url("/v1").toString(), "private auxiliary key", "fixture", createdAt = 0)
                val design = DesktopNovelAiTextTransport(transport, logs)
                val vision = DesktopAuxiliaryImageUnderstanding(transport, logs)
                val parent = DesktopUsageContext(DesktopUsageSource.MOMENTS, "actual-task", "actual-session", "actual-card")
                withContext(DesktopUsageParent(parent)) {
                    for (stage in NovelAiTextStage.entries) {
                        server.enqueue(response())
                        design.streamText(stage, listOf(ChatApiMessage.text("user", "private auxiliary input")), model).toList()
                    }
                    server.enqueue(response())
                    assertEquals("private auxiliary result", vision.describe("data:image/png;base64,AAAA", model) {})
                }
                val explicit = logs.begin("explicit-vision", "editor-display", model,
                    DesktopUsageContext(DesktopUsageSource.CHARACTER_CARD, taskId = "editor-task", editorSessionId = "draft", personId = "person"))
                server.enqueue(response())
                vision.describe("data:image/png;base64,AAAA", model, {}, explicit)
                explicit.finish(DesktopTaskStatus.COMPLETED, null, null)
                server.enqueue(response("length"))
                assertFailsWith<DesktopDesignException> {
                    design.streamText(NovelAiTextStage.GENERATE, listOf(ChatApiMessage.text("user", "private auxiliary input")), model).toList()
                }
                transport.closeAndDrain()
                usage.closeAndDrain()
                val records = store.records()
                assertEquals(NovelAiTextStage.entries.size + 3, server.requestCount)
                val designEntries = logs.entries.value.filter { it.sessionId.startsWith("image-design:") }
                assertEquals(NovelAiTextStage.entries.map { it.name }.toSet(), designEntries.map { it.promptStages.single().stage }.toSet())
                assertTrue(designEntries.all { it.promptStages.single().kind in setOf("IMAGE_RESEARCH", "IMAGE_DESIGN") && it.templateVersion != null })
                assertEquals("IMAGE_DESCRIPTION", logs.entries.value.single { it.sessionId == "image-understanding" }.promptStages.single().kind)
                assertNull(logs.entries.value.single { it.taskId == "explicit-vision" }.templateVersion) // Unmarked caller remains explicitly unknown.
                assertEquals(server.requestCount, records.size)
                assertEquals(records.size, records.map { it.requestId }.distinct().size)
                assertTrue(records.all { it.attempt == 1 && it.counters.prompt == 0L && it.counters.completion == 2L })
                val inherited = records.filter { it.source == DesktopUsageSource.MOMENTS }
                assertEquals(NovelAiTextStage.entries.size + 1, inherited.size)
                assertTrue(inherited.all { it.taskId == parent.taskId && it.sessionId == parent.sessionId && it.cardId == parent.cardId && it.outcome == DesktopUsageOutcome.COMPLETED })
                val editor = records.single { it.source == DesktopUsageSource.CHARACTER_CARD }
                assertEquals("draft", editor.editorSessionId); assertEquals("person", editor.personId); assertNull(editor.sessionId)
                val failed = records.single { it.source == DesktopUsageSource.OTHER }
                assertEquals(DesktopUsageOutcome.FAILED, failed.outcome); assertNull(failed.taskId); assertNull(failed.sessionId)
                records.forEach { row ->
                    val json = Json.encodeToString(DesktopUsageRecord.serializer(), row)
                    assertFalse(json.contains("private auxiliary")); assertFalse(json.contains("data:image")); assertFalse(json.contains("image-design:"))
                }
            }
        } finally {
            transport.closeAndDrain(); usage.closeAndDrain(); gate.closeAndDrain(); root.toFile().deleteRecursively()
        }
    }

    @Test fun `cancelling actual auxiliary transport preserves one uncertain cancelled attempt without another request`() = runBlocking {
        MockWebServer().use { server ->
            val rows = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
            val logs = DesktopTransportDiagnosticsOwner(usageSink = { rows.add(it) })
            val transport = OpenAiStreamingTransport { true }
            try {
                val model = ModelConfig("model", "Fixture", server.url("/v1").toString(), "private auxiliary key", "fixture", createdAt = 0)
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val work = launch {
                    DesktopNovelAiTextTransport(transport, logs).streamText(NovelAiTextStage.PLAN,
                        listOf(ChatApiMessage.text("user", "fixture")), model).toList()
                }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                work.cancelAndJoin()
                val latest = rows.toList().groupBy { it.id }.values.map { group -> group.maxBy { it.revision } }
                assertEquals(1, server.requestCount); assertEquals(1, latest.size)
                assertEquals(DesktopUsageOutcome.CANCELLED, latest.single().outcome)
                assertEquals(DesktopUsageCounters(), latest.single().counters)
            } finally { transport.closeAndDrain() }
        }
    }
}
