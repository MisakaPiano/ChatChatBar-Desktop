package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPrimaryChatControllerTest {
    @Test
    fun `fresh root construction and initial empty refresh write no business files`() = runBlocking {
        val parent = Files.createTempDirectory("primary-chat-empty-")
        val container = container(parent)
        try {
            val controller = container.primaryChatController
            assertFalse(Files.exists(container.appDataRoot))
            controller.refresh()
            val files = Files.walk(container.appDataRoot).use { it.filter(Files::isRegularFile).toList() }
            assertTrue(files.isEmpty(), "Unexpected business files: $files")
            assertNull(controller.state.value.selectedSession)
            assertFalse(controller.state.value.modelUsable)
            assertEquals("Select or create a session", controller.state.value.configurationMessage)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `creation gates on default model and service persists greeting once`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Ada", greeting = "Hello once")
            container.characterRepository.save(character)
            val controller = container.primaryChatController
            controller.refresh()
            controller.createSession(character.id)
            assertTrue(controller.state.value.sessions.isEmpty())
            assertFalse(controller.state.value.modelUsable)
            assertNotNull(controller.state.value.configurationMessage)

            configure(container)
            controller.createSession(character.id)
            val id = assertNotNull(controller.state.value.selectedSession?.id)
            assertEquals(character.id, controller.state.value.selectedSession?.characterCardId)
            assertEquals(listOf("Hello once"), container.chatRepository.getMessages(id).map(ChatMessage::content))
            controller.refresh()
            assertEquals(1, controller.state.value.messages.size)
            assertEquals(1, controller.state.value.totalMessageCount)
        }
    }

    @Test
    fun `failed default-model creation does not disable an existing usable explicit session`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Explicit")
            container.characterRepository.save(character)
            configure(container)
            val id = container.characterSessionService.createSessionForCharacter(character.id)
            val session = assertNotNull(container.chatRepository.getSession(id))
            container.chatRepository.updateSession(session.copy(modelId = "model"))
            container.modelRepository.saveModel(
                ModelConfig(
                    id = "bad-default", displayName = "Unconfigured", modelName = "fake-model",
                    baseUrl = "https://example.invalid/v1", apiKey = "", createdAt = 2L,
                ),
            )
            container.settingsRepository.saveAppSettings(
                AppSettings(defaultModelId = "bad-default", allowCleartextModelApi = true, ragInjectionMode = "OFF"),
            )
            val controller = container.primaryChatController
            controller.refresh()
            assertTrue(controller.state.value.modelUsable)
            controller.createSession(character.id)
            assertTrue(controller.state.value.modelUsable)
            assertEquals(id, controller.state.value.selectedSession?.id)
            assertEquals(1, controller.state.value.sessions.size)
            assertNotNull(controller.state.value.error)
        }
    }

    @Test
    fun `selection reads bounded latest page and older pages prepend without duplication`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Paged", greeting = "Greeting")
            container.characterRepository.save(character)
            configure(container)
            val id = container.characterSessionService.createSessionForCharacter(character.id)
            repeat(90) { index ->
                container.chatRepository.addMessage(ChatMessage.create(id, MessageRole.USER, "message-$index"))
            }
            val controller = container.primaryChatController
            controller.refresh()
            val initial = controller.state.value
            assertTrue(initial.hasOlderMessages)
            assertEquals(91, initial.totalMessageCount)
            assertTrue(initial.messages.size < initial.totalMessageCount)
            assertEquals("message-89", initial.messages.last().content)
            val initialIds = initial.messages.map(ChatMessage::id).toSet()

            controller.loadOlder()
            val loaded = controller.state.value.messages
            assertTrue(loaded.size > initial.messages.size)
            assertEquals(loaded.size, loaded.map(ChatMessage::id).toSet().size)
            assertTrue(loaded.map(ChatMessage::id).containsAll(initialIds))
            assertEquals(loaded.sortedWith(ChatMessage.TimelineComparator), loaded)
            assertEquals("Greeting", loaded.first().content)
            assertFalse(controller.state.value.hasOlderMessages)
        }
    }

    @Test
    fun `pin title and missing character keep authoritative session and readable history`() = runBlocking {
        withContainer { container ->
            val first = CharacterCard.create("First", greeting = "First greeting")
            val second = CharacterCard.create("Second", greeting = "Second greeting")
            container.characterRepository.save(first)
            container.characterRepository.save(second)
            configure(container)
            val firstId = container.characterSessionService.createSessionForCharacter(first.id)
            val secondId = container.characterSessionService.createSessionForCharacter(second.id)
            val controller = container.primaryChatController
            controller.refresh()
            controller.togglePin(firstId)
            assertEquals(firstId, controller.state.value.sessions.first().id)
            assertTrue(container.chatRepository.getSession(firstId)?.isPinned == true)
            controller.setDisplayTitle(firstId, "Personal title")
            assertEquals("Personal title", controller.state.value.sessions.first().title)
            assertEquals("First", container.characterRepository.getById(first.id)?.name)
            controller.setDisplayTitle(firstId, "   ")
            assertEquals("First", controller.state.value.sessions.first().title)
            controller.togglePin(firstId)
            assertFalse(container.chatRepository.getSession(firstId)?.isPinned == true)
            assertTrue(controller.state.value.sessions.any { it.id == secondId })

            container.characterRepository.delete(first.id)
            controller.selectSession(firstId)
            assertTrue(controller.state.value.selectedCharacterMissing)
            assertEquals("First greeting", controller.state.value.messages.single().displayContent)
            controller.editComposer("must not send")
            assertNull(controller.send())
            assertTrue(container.taskRuntime.tasks.value.isEmpty())
            assertEquals(1, container.chatRepository.getMessages(firstId).size)
        }
    }

    @Test
    fun `settings merge concurrent fields and leave stale references until explicit edit`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Settings")
            container.characterRepository.save(character)
            configure(container)
            val id = container.characterSessionService.createSessionForCharacter(character.id)
            val original = assertNotNull(container.chatRepository.getSession(id))
            container.chatRepository.updateSession(original.copy(modelId = "missing-model", formatCardId = "missing-format"))
            val controller = container.primaryChatController
            controller.refresh()
            assertEquals("missing-model", controller.state.value.sessionSettingsDraft?.modelId)
            assertEquals("missing-format", controller.state.value.sessionSettingsDraft?.formatCardId)
            controller.editSessionSettings { it.copy(replyLanguage = "Japanese", playerName = "Player") }
            assertNull(container.chatRepository.getSession(id)?.replyLanguage)
            assertNull(container.chatRepository.getSession(id)?.playerName)
            val concurrent = assertNotNull(container.chatRepository.getSession(id))
            container.chatRepository.updateSession(concurrent.copy(roleplayStyle = "concurrent", contextWindowSize = 77))
            controller.refreshAfterTerminalTask(id)
            controller.saveSessionSettings()
            val saved = assertNotNull(container.chatRepository.getSession(id))
            assertEquals("Japanese", saved.replyLanguage)
            assertEquals("Player", saved.playerName)
            assertEquals("concurrent", saved.roleplayStyle)
            assertEquals(77, saved.contextWindowSize)
            assertEquals("missing-model", saved.modelId)
            assertEquals("missing-format", saved.formatCardId)
        }
    }

    @Test
    fun `per-session drafts survive switching and container restart`() = runBlocking {
        val parent = Files.createTempDirectory("primary-chat-draft-")
        var container = container(parent)
        try {
            val first = CharacterCard.create("A")
            val second = CharacterCard.create("B")
            container.characterRepository.save(first)
            container.characterRepository.save(second)
            configure(container)
            val a = container.characterSessionService.createSessionForCharacter(first.id)
            val b = container.characterSessionService.createSessionForCharacter(second.id)
            val controller = container.primaryChatController
            controller.refresh()
            controller.selectSession(a)
            controller.editComposer("draft-A")
            val earlierSave = async { controller.persistComposer() }
            controller.editComposer("draft-A-latest")
            val latestSave = async { controller.persistComposer() }
            earlierSave.await()
            latestSave.await()
            controller.selectSession(b)
            controller.editComposer("draft-B")
            controller.persistComposer()
            controller.selectSession(a)
            assertEquals("draft-A-latest", controller.state.value.composerDraft)
            controller.selectSession(b)
            assertEquals("draft-B", controller.state.value.composerDraft)
            container.close()

            container = container(parent)
            val reopened = container.primaryChatController
            reopened.refresh()
            reopened.selectSession(a)
            assertEquals("draft-A-latest", reopened.state.value.composerDraft)
            reopened.selectSession(b)
            assertEquals("draft-B", reopened.state.value.composerDraft)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `accepted send clears draft while rejected same-session admission preserves it`() = runBlocking {
        withContainer { container ->
            MockWebServer().use { server ->
                val id = prepare(container, server)
                server.enqueue(success("reply"))
                val controller = container.primaryChatController
                controller.refresh()
                controller.editComposer("first user")
                controller.persistComposer()
                val taskId = assertNotNull(controller.send())
                assertEquals("", controller.state.value.composerDraft)
                assertEquals("", container.chatRepository.getSessionDraft(id))
                awaitTask(container, taskId) { it.status != DesktopTaskStatus.RUNNING }

                server.enqueue(MockResponse().setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS).setBody("data: [DONE]\n\n"))
                val blocking = assertNotNull(controller.continueReply())
                controller.editComposer("keep after rejection")
                controller.persistComposer()
                assertNull(controller.send())
                assertEquals("keep after rejection", controller.state.value.composerDraft)
                assertEquals("keep after rejection", container.chatRepository.getSessionDraft(id))
                controller.stop(blocking)
                awaitTask(container, blocking) { it.status != DesktopTaskStatus.RUNNING }
            }
        }
    }

    @Test
    fun `continue creates no user entity and terminal refresh shows one persisted assistant`() = runBlocking {
        withContainer { container ->
            MockWebServer().use { server ->
                val id = prepare(container, server)
                val controller = container.primaryChatController
                controller.refresh()
                server.enqueue(success("continued"))
                val taskId = assertNotNull(controller.continueReply())
                awaitTask(container, taskId) { it.status == DesktopTaskStatus.COMPLETED }
                controller.refreshAfterTerminalTask(id)
                controller.refreshAfterTerminalTask(id)
                assertEquals(0, container.chatRepository.getMessages(id).count { it.role == MessageRole.USER })
                assertEquals(1, controller.state.value.messages.count { it.content == "continued" })
                assertEquals(controller.state.value.messages.size, controller.state.value.messages.map(ChatMessage::id).toSet().size)
            }
        }
    }

    @Test
    fun `session and route switching retain independent running previews and user stop persistence`() = runBlocking {
        withContainer { container ->
            HoldingPrimarySseServer("partial-A").use { serverA ->
                HoldingPrimarySseServer("partial-B").use { serverB ->
                    val characterA = CharacterCard.create("A", greeting = "Hello A")
                    val characterB = CharacterCard.create("B", greeting = "Hello B")
                    container.characterRepository.save(characterA)
                    container.characterRepository.save(characterB)
                    configure(container, serverA.baseUrl)
                    container.modelRepository.saveModel(
                        ModelConfig(
                            id = "model-B", displayName = "Model B", modelName = "fake-model",
                            baseUrl = serverB.baseUrl, apiKey = "fake-primary-key-B", createdAt = 2L,
                        ),
                    )
                    val sessionA = container.characterSessionService.createSessionForCharacter(characterA.id)
                    val sessionB = container.characterSessionService.createSessionForCharacter(characterB.id)
                    val sessionBEntity = assertNotNull(container.chatRepository.getSession(sessionB))
                    container.chatRepository.updateSession(sessionBEntity.copy(modelId = "model-B"))
                    val controller = container.primaryChatController
                    controller.refresh()
                    controller.selectSession(sessionA)
                    controller.editComposer("user A")
                    val taskA = assertNotNull(controller.send())
                    awaitTask(container, taskA) { it.contentPreview == "partial-A" }

                    controller.selectSession(sessionB)
                    assertEquals(DesktopTaskStatus.RUNNING, container.taskRuntime.tasks.value.single { it.taskId == taskA }.status)
                    controller.editComposer("user B")
                    val taskB = assertNotNull(controller.send())
                    awaitTask(container, taskB) { it.contentPreview == "partial-B" }
                    controller.selectSession(sessionA)
                    assertEquals("partial-A", container.taskRuntime.tasks.value.single { it.taskId == taskA }.contentPreview)
                    assertEquals(DesktopTaskStatus.RUNNING, container.taskRuntime.tasks.value.single { it.taskId == taskB }.status)

                    val rootState = DesktopDataRootSwitchState.Idle(
                        currentRoot = container.appDataRoot,
                        provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                        supported = true,
                    )
                    val navigation = DesktopPrimaryNavigationController()
                    DesktopPrimaryRoute.entries.forEach { route ->
                        assertTrue(navigation.navigate(route, rootState))
                        assertEquals(DesktopTaskStatus.RUNNING, container.taskRuntime.tasks.value.single { it.taskId == taskA }.status)
                    }
                    DesktopShellSize.entries.forEach { size ->
                        assertNotNull(size)
                        assertEquals(DesktopTaskStatus.RUNNING, container.taskRuntime.tasks.value.single { it.taskId == taskB }.status)
                    }

                    assertTrue(controller.stop(taskA))
                    assertTrue(controller.stop(taskB))
                    awaitTask(container, taskA) { it.status == DesktopTaskStatus.USER_STOPPED }
                    awaitTask(container, taskB) { it.status == DesktopTaskStatus.USER_STOPPED }
                    controller.selectSession(sessionB)
                    controller.refreshAfterTerminalTask(sessionA)
                    assertEquals(sessionB, controller.state.value.selectedSession?.id)
                    assertTrue(controller.state.value.sessions.any { it.id == sessionA && it.lastMessagePreview?.contains("partial-A") == true })
                    controller.selectSession(sessionA)
                    assertEquals(1, controller.state.value.messages.count { it.content == "partial-A" })
                    assertEquals(1, container.chatRepository.getMessages(sessionA).count { it.role == MessageRole.USER })
                    assertEquals(1, container.chatRepository.getMessages(sessionB).count { it.content == "partial-B" })
                }
            }
        }
    }

    private suspend fun prepare(container: DesktopAppContainer, server: MockWebServer): String {
        val character = CharacterCard.create("Network test", greeting = "Greeting")
        container.characterRepository.save(character)
        configure(container, server.url("/v1").toString().trimEnd('/'))
        return container.characterSessionService.createSessionForCharacter(character.id)
    }

    private suspend fun configure(container: DesktopAppContainer, baseUrl: String = "http://127.0.0.1:12345/v1") {
        val model = ModelConfig(
            id = "model", displayName = "Model", modelName = "fake-model", baseUrl = baseUrl,
            apiKey = "fake-primary-key", createdAt = 1L,
        )
        container.modelRepository.saveModel(model)
        container.settingsRepository.saveAppSettings(
            AppSettings(defaultModelId = model.id, allowCleartextModelApi = true, ragInjectionMode = "OFF"),
        )
    }

    private suspend fun awaitTask(
        container: DesktopAppContainer,
        id: String,
        predicate: (DesktopTaskEntry) -> Boolean,
    ): DesktopTaskEntry = withTimeout(7_000) {
        container.taskRuntime.tasks.first { entries -> entries.any { it.taskId == id && predicate(it) } }
            .single { it.taskId == id }
    }

    private fun success(content: String) = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$content\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

    private suspend fun withContainer(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("primary-chat-controller-")
        val container = container(parent)
        try { block(container) } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun container(parent: Path) = DesktopAppContainer(
        resolvedRoot = DesktopDataRootResolution.Resolved(
            appDataRoot = parent.resolve("app-data"),
            provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
            bootstrapPath = parent.resolve("bootstrap.json"),
        ),
        secretStoreFactory = { InMemoryDesktopSecretStore() },
    )
}

private class HoldingPrimarySseServer(content: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val socket = AtomicReference<Socket?>()
    private val release = CountDownLatch(1)
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    private val worker = thread(name = "holding-primary-chat-sse", isDaemon = true) {
        runCatching {
            server.accept().use { connection ->
                socket.set(connection)
                val reader = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                while (!reader.readLine().isNullOrEmpty()) Unit
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    write("data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n".toByteArray())
                    flush()
                }
                release.await(10, TimeUnit.SECONDS)
            }
        }
    }

    override fun close() {
        release.countDown()
        socket.getAndSet(null)?.close()
        server.close()
        worker.join(1_000)
    }
}
