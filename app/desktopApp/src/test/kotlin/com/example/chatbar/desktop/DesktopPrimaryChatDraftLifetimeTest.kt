package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopPrimaryChatDraftLifetimeTest {
    @Test
    fun `ordinary writer failure is visible without exposing exception content`() = runTest {
        withContainer { container ->
            prepare(container)
            val controller = controller(container, StandardTestDispatcher(testScheduler)) { _, _ ->
                error("private draft and fake-api-key")
            }
            controller.refresh()
            controller.editComposer("private draft")
            controller.state.first { it.error != null }
            assertEquals("Unable to save chat draft", controller.state.value.error)
            assertFailsWith<DesktopDraftPersistenceException> { controller.drain() }
        }
    }

    @Test
    fun `edit alone persists without a UI flush or task entry`() = runTest {
        withContainer { container ->
            val id = prepare(container)
            val written = CompletableDeferred<Unit>()
            val controller = controller(container, StandardTestDispatcher(testScheduler)) { session, text ->
                container.chatRepository.updateSessionDraft(session, text)
                written.complete(Unit)
            }
            try {
                controller.refresh()
                controller.editComposer("last edit")
                written.await()
                assertEquals("last edit", container.chatRepository.getSessionDraft(id))
                assertTrue(container.taskRuntime.tasks.value.isEmpty())
            } finally { controller.drain() }
        }
    }

    @Test
    fun `rapid edits finish durably at latest value`() = runTest {
        withContainer { container ->
            val id = prepare(container)
            val writes = mutableListOf<String>()
            val controller = controller(container, StandardTestDispatcher(testScheduler)) { session, text ->
                writes += text
                container.chatRepository.updateSessionDraft(session, text)
            }
            controller.refresh()
            controller.editComposer("a")
            controller.editComposer("ab")
            controller.editComposer("abc")
            controller.drain()
            assertEquals(listOf("abc"), writes)
            assertEquals("abc", container.chatRepository.getSessionDraft(id))
        }
    }

    @Test
    fun `immediate session switch waits for latest outgoing draft and keeps sessions independent`() = runTest {
        withContainer { container ->
            val a = prepare(container)
            val cardB = CharacterCard.create("B")
            container.characterRepository.save(cardB)
            val b = container.characterSessionService.createSessionForCharacter(cardB.id)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller = controller(container, StandardTestDispatcher(testScheduler)) { session, text ->
                entered.complete(Unit)
                release.await()
                container.chatRepository.updateSessionDraft(session, text)
            }
            try {
                controller.refresh()
                controller.selectSession(a)
                controller.editComposer("A first")
                entered.await()
                val switching = async { controller.selectSession(b) }
                runCurrent()
                assertEquals(a, controller.state.value.selectedSession?.id)
                controller.editComposer("A latest")
                release.complete(Unit)
                switching.await()
                assertEquals("A latest", container.chatRepository.getSessionDraft(a))
                assertEquals("", controller.state.value.composerDraft)
                controller.editComposer("B only")
                controller.selectSession(a)
                assertEquals("B only", container.chatRepository.getSessionDraft(b))
                assertEquals("A latest", controller.state.value.composerDraft)
            } finally { release.complete(Unit); controller.drain() }
        }
    }

    @Test
    fun `pending edit survives route change and cancellation of the entire panel scope`() = runTest {
        withContainer { container ->
            val id = prepare(container)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val written = CompletableDeferred<Unit>()
            val controller = controller(container, StandardTestDispatcher(testScheduler)) { session, text ->
                entered.complete(Unit)
                release.await()
                container.chatRepository.updateSessionDraft(session, text)
                written.complete(Unit)
            }
            val panelJob = SupervisorJob()
            try {
                controller.refresh()
                CoroutineScope(panelJob + StandardTestDispatcher(testScheduler)).launch {
                    controller.editComposer("survives disposal")
                    awaitCancellation()
                }
                entered.await()
                val navigation = DesktopPrimaryNavigationController()
                assertTrue(navigation.navigate(DesktopPrimaryRoute.MANAGE, DesktopDataRootSwitchState.Idle(
                    currentRoot = container.appDataRoot,
                    provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                    supported = true,
                )))
                panelJob.cancelAndJoin()
                release.complete(Unit)
                written.await()
                controller.refresh()
                assertEquals("survives disposal", controller.state.value.composerDraft)
                assertEquals("survives disposal", container.chatRepository.getSessionDraft(id))
            } finally { panelJob.cancelAndJoin(); release.complete(Unit); controller.drain() }
        }
    }

    @Test
    fun `final edit immediately followed by container close survives restart`() = runTest {
        val parent = Files.createTempDirectory("primary-draft-close-")
        val first = container(parent)
        try {
            val id = prepare(first)
            val controller = first.primaryChatController
            controller.refresh()
            controller.editComposer("final keystroke")
            first.closeReal()
            assertEquals(DesktopDataOperationCoordinatorState.CLOSED, first.dataOperationCoordinator.state)
            val reopened = container(parent)
            try {
                reopened.primaryChatController.refresh()
                assertEquals(id, reopened.primaryChatController.state.value.selectedSession?.id)
                assertEquals("final keystroke", reopened.primaryChatController.state.value.composerDraft)
            } finally { reopened.closeReal() }
        } finally { first.closeReal(); parent.toFile().deleteRecursively() }
    }

    @Test
    fun `accepted Send clear survives caller disposal and old text never returns`() = runTest {
        withContainer { container ->
            MockWebServer().use { server ->
                val id = prepare(container, server.url("/v1").toString())
                server.enqueue(success())
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val writes = mutableListOf<String>()
                val controller = controller(container, StandardTestDispatcher(testScheduler)) { session, text ->
                    entered.complete(Unit)
                    release.await()
                    container.chatRepository.updateSessionDraft(session, text)
                    writes += text
                }
                try {
                    controller.refresh()
                    controller.editComposer("older in-flight")
                    entered.await()
                    controller.editComposer("text to send")
                    val sending = async { controller.send() }
                    val taskId = container.taskRuntime.tasks.first { it.isNotEmpty() }.first().taskId
                    sending.cancelAndJoin()
                    release.complete(Unit)
                    controller.drain()
                    assertEquals(listOf("older in-flight", ""), writes)
                    assertEquals("", controller.state.value.composerDraft)
                    assertEquals("", container.chatRepository.getSessionDraft(id))
                    awaitTerminal(container, taskId)
                } finally { release.complete(Unit); controller.drain() }
            }
        }
    }

    @Test
    fun `rejected admission keeps the current draft durable`() = runTest {
        withContainer { container ->
            MockWebServer().use { server ->
                val id = prepare(container, server.url("/v1").toString())
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val controller = container.primaryChatController
                controller.refresh()
                val active = container.taskRuntime.launchChat(id, "first")
                controller.editComposer("preserve rejected draft")
                assertNull(controller.send())
                controller.drain()
                assertEquals("preserve rejected draft", container.chatRepository.getSessionDraft(id))
                assertEquals("preserve rejected draft", controller.state.value.composerDraft)
                controller.stop(active)
                awaitTerminal(container, active)
            }
        }
    }

    @Test
    fun `Continue preserves composer draft and creates no extra user`() = runTest {
        withContainer { container ->
            MockWebServer().use { server ->
                val id = prepare(container, server.url("/v1").toString())
                server.enqueue(success())
                val controller = container.primaryChatController
                controller.refresh()
                controller.editComposer("not submitted")
                val task = assertNotNull(controller.continueReply())
                awaitTerminal(container, task)
                controller.drain()
                assertEquals("not submitted", controller.state.value.composerDraft)
                assertEquals("not submitted", container.chatRepository.getSessionDraft(id))
                assertEquals(0, container.chatRepository.getMessages(id).count { it.role == MessageRole.USER })
            }
        }
    }

    private fun controller(
        container: DesktopAppContainer,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        write: suspend (String, String) -> Unit,
    ) = DesktopPrimaryChatController(
        characters = container.characterRepository, chats = container.chatRepository,
        settings = container.settingsRepository, models = container.effectiveModelResolver,
        formats = container.formatCardRepository, worldBooks = container.worldBookRepository,
        sessionService = container.characterSessionService, characterResources = container.characterResourceStore,
        taskRuntime = container.taskRuntime, draftDispatcher = dispatcher, draftWriter = write,
    )

    private suspend fun prepare(container: DesktopAppContainer, url: String = "http://127.0.0.1:12345/v1"): String {
        val card = CharacterCard.create("Draft test", greeting = "Hello")
        container.characterRepository.save(card)
        container.modelRepository.saveModel(ModelConfig(
            id = "draft-model", displayName = "Fake model", modelName = "fake", baseUrl = url,
            apiKey = "fake-draft-key", createdAt = 1,
        ))
        container.settingsRepository.saveAppSettings(AppSettings(
            defaultModelId = "draft-model", allowCleartextModelApi = true, ragInjectionMode = "OFF",
        ))
        return container.characterSessionService.createSessionForCharacter(card.id)
    }

    private suspend fun awaitTerminal(container: DesktopAppContainer, taskId: String) = withContext(Dispatchers.Default) {
        withTimeout(10_000) {
            container.taskRuntime.tasks.first { entries -> entries.any { it.taskId == taskId && it.status != DesktopTaskStatus.RUNNING } }
        }
    }

    // Storage and HTTP use real IO; their shutdown deadlines must not use the test scheduler's clock.
    private suspend fun DesktopPrimaryChatController.drain() = withContext(Dispatchers.Default) { closeDraftPersistence() }
    private suspend fun DesktopAppContainer.closeReal() = withContext(Dispatchers.Default) { close() }

    private fun success() = MockResponse().addHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

    private suspend fun withContainer(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("primary-draft-lifetime-")
        val container = container(parent)
        try { block(container) } finally { container.closeReal(); parent.toFile().deleteRecursively() }
    }

    private fun container(parent: Path) = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() },
    )
}
