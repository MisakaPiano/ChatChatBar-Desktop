package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopChatPreparationFailureTest {
    private class Fixture {
        val root = Files.createTempDirectory("chat-preparation-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val server = MockWebServer().apply { start() }
        val usages = mutableListOf<DesktopUsageRecord>()
        var retrieval: suspend () -> DesktopChatKnowledge = { throw DesktopEmbeddingAuthenticationException() }
        private val planner = DesktopChatRequestPlanner(app.characterRepository, app.chatRepository,
            app.formatCardRepository, app.contextWindowManager, app.worldBookRequestPlanner,
            app.promptAssembler, app.mainChatRequestAssembler,
            retrieveKnowledge = { _, _, _, _, _, _ -> retrieval() })
        val runtime = DesktopTaskRuntime(DesktopRealChatRuntime(app.characterRepository, app.chatRepository,
            app.settingsRepository, app.characterSessionService, app.effectiveModelResolver, planner),
            DesktopTransportDiagnosticsOwner(usageSink = { synchronized(usages) { usages += it } }))
        suspend fun configure(): String {
            val card = CharacterCard.create("Synthetic")
            app.characterRepository.save(card)
            val id = app.characterSessionService.createSessionForCharacter(card.id)
            val model = ModelConfig("model", "Synthetic", server.url("/v1").toString(),
                "SYNTHETIC_CHAT_KEY", "synthetic-model", createdAt = 1)
            app.modelRepository.saveModel(model)
            app.settingsRepository.saveAppSettings(AppSettings(defaultModelId = model.id,
                allowCleartextModelApi = true, ragInjectionMode = "STANDARD"))
            return id
        }
        suspend fun terminal(id: String): DesktopTaskEntry = withTimeout(5_000) {
            runtime.tasks.first { rows -> rows.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } }
                .single { it.taskId == id }
        }
        suspend fun close() { runtime.closeAndDrain(); app.close(); server.shutdown(); root.toFile().deleteRecursively() }
    }

    @Test fun `saved user survives retrieval refusal and explicit continuation reuses it without duplicate or fake usage`(): Unit = runBlocking {
        val f = Fixture()
        try {
            val session = f.configure()
            val id = f.runtime.launchChat(session, "PRIVATE_SYNTHETIC_USER")
            val failed = f.terminal(id)
            assertEquals(DesktopTaskStatus.FAILED, failed.status)
            assertNotNull(failed.persistedUserMessageId)
            assertEquals(failed, desktopLatestChatFailure(f.runtime.tasks.value, session))
            val entry = f.runtime.diagnostics.entries.value.single()
            assertEquals(DesktopChatPreparationStage.KNOWLEDGE_RETRIEVAL, entry.preparationStage)
            assertEquals("DesktopEmbeddingAuthenticationException", entry.preparationFailureKind)
            assertNull(entry.requestAt); assertNull(entry.serializedRequestBody); assertNull(entry.usage)
            assertEquals(0, f.server.requestCount); assertTrue(f.usages.isEmpty())
            assertFalse(entry.toString().contains("PRIVATE_SYNTHETIC_USER"))
            assertFalse(entry.toString().contains("SYNTHETIC_CHAT_KEY"))
            val user = f.app.chatRepository.getMessage(failed.persistedUserMessageId!!, session)
            assertEquals("PRIVATE_SYNTHETIC_USER", user?.content)
            assertTrue(f.app.chatRepository.getMessages(session).none { it.role == MessageRole.ASSISTANT && it.content.startsWith("错误:") })

            f.retrieval = { DesktopChatKnowledge() }
            f.server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"synthetic reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(f.runtime.launchChat(session, "")).status)
            assertEquals(listOf(user), f.app.chatRepository.getMessages(session).filter { it.role == MessageRole.USER })
            assertEquals(1, f.server.requestCount)
            assertNull(desktopLatestChatFailure(f.runtime.tasks.value, session))
            val completed = f.runtime.diagnostics.entries.value.first()
            assertEquals(DesktopChatPreparationStage.MAIN_REPLY, completed.preparationStage)
            assertNotNull(completed.requestAt)
            assertEquals(2, f.runtime.diagnostics.entries.value.size)
            assertEquals(1, f.usages.map { it.id }.distinct().size)
        } finally { f.close() }
    }

    @Test fun `source integrity refusal stays terminal and raw exception content never enters preparation logs`(): Unit = runBlocking {
        val f = Fixture()
        try {
            val session = f.configure()
            f.retrieval = { error("PRIVATE_SOURCE_BODY PRIVATE_UNKNOWN_CREDENTIAL") }
            val id = f.runtime.launchChat(session, "synthetic")
            val task = f.terminal(id)
            assertEquals(DesktopTaskStatus.FAILED, task.status)
            assertFalse(task.message.contains("PRIVATE_"))
            val entry = f.runtime.diagnostics.entries.value.single()
            assertFalse(entry.toString().contains("PRIVATE_"))
            assertEquals("IllegalStateException", entry.preparationFailureKind)
            assertEquals(0, f.server.requestCount); assertTrue(f.usages.isEmpty())
            assertEquals(1, f.app.chatRepository.getMessages(session).count { it.role == MessageRole.USER })
        } finally { f.close() }
    }

    @Test fun `actual embedding HTTP failure has auxiliary usage but no main chat request and no silent continuation`(): Unit = runBlocking {
        val f = Fixture()
        try {
            val session = f.configure()
            MockWebServer().use { embedding ->
                val config = EmbeddingConfig("embedding", "Synthetic embedding", embedding.url("/v1").toString(),
                    "SYNTHETIC_EMBEDDING_KEY", "synthetic-embedding", 2)
                val gateway = DesktopEmbeddingGateway({ config }, allowCleartext = { true },
                    usageSink = { synchronized(f.usages) { f.usages += it } })
                try {
                    embedding.enqueue(MockResponse().setResponseCode(500).setBody("PRIVATE_PROVIDER_BODY"))
                    f.retrieval = {
                        gateway.embed(DesktopIndexEmbeddingBatch(DesktopRagEmbeddingIdentity.from(config), 0,
                            listOf("synthetic query" to emptyMap())))
                        DesktopChatKnowledge()
                    }
                    val id = f.runtime.launchChat(session, "synthetic")
                    assertEquals(DesktopTaskStatus.FAILED, f.terminal(id).status)
                    assertEquals(1, embedding.requestCount); assertEquals(0, f.server.requestCount)
                    val entry = f.runtime.diagnostics.entries.value.single()
                    assertEquals(DesktopChatPreparationStage.KNOWLEDGE_RETRIEVAL, entry.preparationStage)
                    assertNull(entry.requestAt); assertNull(entry.usage)
                    assertFalse(entry.toString().contains("PRIVATE_PROVIDER_BODY"))
                    assertEquals(setOf(DesktopUsageSource.CHAT), f.usages.map { it.source }.toSet())
                    assertEquals(setOf(id), f.usages.map { it.taskId }.toSet())
                    assertEquals(setOf("embedding"), f.usages.map { it.modelId }.toSet())
                    assertEquals(1, f.usages.map { it.id }.distinct().size)
                    assertEquals(DesktopUsageOutcome.FAILED, f.usages.last().outcome)
                } finally { gateway.closeAndDrain() }
            }
        } finally { f.close() }
    }

    @Test fun `stop during preparation remains user stopped without main dispatch or error reply`(): Unit = runBlocking {
        val f = Fixture()
        try {
            val session = f.configure()
            val entered = CompletableDeferred<Unit>()
            f.retrieval = { entered.complete(Unit); awaitCancellation() }
            val id = f.runtime.launchChat(session, "synthetic")
            withTimeout(5_000) { entered.await() }
            assertTrue(f.runtime.requestUserStop(id))
            assertEquals(DesktopTaskStatus.USER_STOPPED, f.terminal(id).status)
            val entry = f.runtime.diagnostics.entries.value.single()
            assertTrue(entry.cancellationObserved); assertNull(entry.error)
            assertNull(desktopLatestChatFailure(f.runtime.tasks.value, session))
            assertEquals(0, f.server.requestCount); assertTrue(f.usages.isEmpty())
        } finally { f.close() }
    }

    @Test fun `configuration refusal has local lifecycle even before recorder and unrelated image cannot hide failed send`(): Unit = runBlocking {
        val f = Fixture()
        try {
            val id = f.runtime.launchChat("missing-session", "synthetic")
            val task = f.terminal(id)
            val image = DesktopTaskEntry("image", DesktopTaskKind.NOVELAI, "missing-session", 2)
            assertEquals(task, desktopLatestChatFailure(listOf(image, task), "missing-session"))
            assertNull(desktopLatestChatFailure(listOf(task.copy(taskId = "newer", status = DesktopTaskStatus.RUNNING), task), "missing-session"))
            val entry = f.runtime.diagnostics.entries.value.single()
            assertEquals(DesktopChatPreparationStage.CONFIGURATION, entry.preparationStage)
            assertEquals(DesktopTaskStatus.FAILED, entry.status)
            assertNull(entry.requestAt); assertTrue(f.usages.isEmpty())
        } finally { f.close() }
    }
}
