package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopTaskRuntimeTest {
    @Test
    fun `primary route switches do not cancel application-owned active chat task`() = runBlocking {
        withContainer { container ->
            HoldingTaskSseServer(
                """{"choices":[{"delta":{"content":"route-independent"}}]}""",
            ).use { server ->
                val sessionId = prepare(container, server.baseUrl)
                val runtime = container.taskRuntime
                val taskId = runtime.launchChat(sessionId, "hello")
                awaitTask(runtime, taskId) { it.contentPreview == "route-independent" }
                val rootState = DesktopDataRootSwitchState.Idle(
                    currentRoot = container.appDataRoot,
                    provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                    supported = true,
                )
                val navigation = DesktopPrimaryNavigationController()
                DesktopPrimaryRoute.entries.forEach { route ->
                    assertTrue(navigation.navigate(route, rootState))
                    assertEquals(DesktopTaskStatus.RUNNING, task(runtime, taskId).status)
                    assertEquals(route, navigation.currentRoute(rootState))
                }
                assertTrue(runtime.requestUserStop(taskId))
                awaitTask(runtime, taskId) { it.status == DesktopTaskStatus.USER_STOPPED }
            }
        }
    }

    @Test
    fun `same session admission rejects overlap and explicit stop persists draft`() = runBlocking {
        withContainer { container ->
            HoldingTaskSseServer(
                """{"choices":[{"delta":{"content":"partial-task"}}]}""",
            ).use { server ->
                val sessionId = prepare(container, server.baseUrl)
                val tasks = container.taskRuntime
                val taskId = tasks.launchChat(sessionId, "user-message")
                awaitTask(tasks, taskId) { it.contentPreview == "partial-task" }

                assertFailsWith<DesktopTaskAdmissionException> {
                    tasks.launchChat(sessionId, "competing-message")
                }
                assertTrue(tasks.requestUserStop(taskId))
                val stopped = awaitTask(tasks, taskId) { it.status == DesktopTaskStatus.USER_STOPPED }
                val persisted = container.chatRepository.getMessages(sessionId)

                assertNotNull(stopped.completedAt)
                assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT), persisted.map { it.role })
                assertEquals("partial-task", persisted.last().content)
                assertFalse(persisted.any { it.content == "competing-message" })
                assertEquals(DesktopTaskStatus.USER_STOPPED, tasks.diagnostics.entries.value.single().status)
            }
        }
    }

    @Test
    fun `container shutdown cancels task generically before storage coordinator closes`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-task-close-")
        val container = container(parent)
        try {
            HoldingTaskSseServer(
                """{"choices":[{"delta":{"content":"partial-shutdown"}}]}""",
            ).use { server ->
                val sessionId = prepare(container, server.baseUrl)
                val taskId = container.taskRuntime.launchChat(sessionId, "shutdown-user")
                awaitTask(container.taskRuntime, taskId) { it.contentPreview == "partial-shutdown" }

                container.close()

                assertEquals(DesktopTaskStatus.CANCELLED, task(container.taskRuntime, taskId).status)
                assertEquals(
                    DesktopTaskStatus.CANCELLED,
                    container.taskRuntime.diagnostics.entries.value.single().status,
                )
                assertEquals(DesktopDataOperationCoordinatorState.CLOSED, container.dataOperationCoordinator.state)
                val reopened = container(parent)
                val persisted = try {
                    reopened.chatRepository.getMessages(sessionId)
                } finally {
                    reopened.close()
                }
                assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), persisted.map { it.role })
                assertFailsWith<DesktopTaskAdmissionException> {
                    container.taskRuntime.launchChat(sessionId, "after-close")
                }
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
        Unit
    }

    @Test
    fun `close helper waits for task drain and leaves data runtimes open when drain fails`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val closing = async {
            closeDesktopDataRuntimes(
                taskRuntimeClose = {
                    events += "task"
                    entered.complete(Unit)
                    release.await()
                },
                runtimeClose = { events += "backup" },
                coordinatorClose = { events += "coordinator" },
                migrationServiceClose = { events += "migration" },
            )
        }
        entered.await()
        assertEquals(listOf("task"), events)
        release.complete(Unit)
        closing.await()
        assertEquals(listOf("task", "backup", "coordinator", "migration"), events)

        val failedEvents = mutableListOf<String>()
        assertFailsWith<DesktopTaskDrainTimeoutException> {
            closeDesktopDataRuntimes(
                taskRuntimeClose = { throw DesktopTaskDrainTimeoutException("still active") },
                runtimeClose = { failedEvents += "backup" },
                coordinatorClose = { failedEvents += "coordinator" },
                migrationServiceClose = { failedEvents += "migration" },
            )
        }
        assertTrue(failedEvents.isEmpty())
    }

    @Test
    fun `shutdown before task dispatch retires admission without persisting user`() = runTest {
        val parent = Files.createTempDirectory("desktop-task-queued-close-")
        val container = container(parent)
        try {
            val sessionId = prepare(container, "http://127.0.0.1:1/v1")
            val runtime = DesktopTaskRuntime(
                realChat = container.createRealChatRuntime(),
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            val taskId = runtime.launchChat(sessionId, "never-dispatched")

            runtime.closeAndDrain()

            assertEquals(DesktopTaskStatus.CANCELLED, runtime.tasks.value.single { it.taskId == taskId }.status)
            assertEquals(listOf(MessageRole.ASSISTANT), container.chatRepository.getMessages(sessionId).map { it.role })
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `blank continue task sends provider request without another user entity`() = runBlocking {
        withContainer { container ->
            MockWebServer().use { server ->
                val sessionId = prepare(container, server.url("/v1").toString().trimEnd('/'))
                server.enqueue(success("first-answer"))
                server.enqueue(success("second-answer"))
                val first = container.taskRuntime.launchChat(sessionId, "first-user")
                awaitTask(container.taskRuntime, first) { it.status == DesktopTaskStatus.COMPLETED }
                val usersBefore = container.chatRepository.getMessages(sessionId).count { it.role == MessageRole.USER }

                val second = container.taskRuntime.launchChat(sessionId, "")
                awaitTask(container.taskRuntime, second) { it.status == DesktopTaskStatus.COMPLETED }
                val persisted = container.chatRepository.getMessages(sessionId)

                assertEquals(1, usersBefore)
                assertEquals(usersBefore, persisted.count { it.role == MessageRole.USER })
                assertEquals("second-answer", persisted.last().content)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test
    fun `provider error result maps to failed task after durable assistant error`() = runBlocking {
        withContainer { container ->
            MockWebServer().use { server ->
                val sessionId = prepare(container, server.url("/v1").toString().trimEnd('/'))
                server.enqueue(MockResponse().setResponseCode(500).setBody("fake-failure"))

                val taskId = container.taskRuntime.launchChat(sessionId, "provider-error")
                val failed = awaitTask(container.taskRuntime, taskId) { it.status == DesktopTaskStatus.FAILED }
                val persisted = container.chatRepository.getMessages(sessionId)

                assertTrue(failed.message.contains("500"))
                assertEquals(1, persisted.count { it.role == MessageRole.USER })
                assertEquals(1, persisted.count { it.content.startsWith("错误:") })
                assertEquals(DesktopTaskStatus.FAILED, container.taskRuntime.diagnostics.entries.value.single().status)
            }
        }
    }

    @Test
    fun `transport diagnostics retain actual final request and real retry evidence`() = runBlocking {
        withContainer { container ->
            MockWebServer().use { server ->
                val sessionId = prepare(container, server.url("/v1").toString().trimEnd('/'))
                repeat(2) { server.enqueue(MockResponse().setResponseCode(400).setBody("fake code 20015")) }
                server.enqueue(success("after-retry"))

                val taskId = container.taskRuntime.launchChat(sessionId, "retry-user")
                awaitTask(container.taskRuntime, taskId) { it.status == DesktopTaskStatus.COMPLETED }
                val diagnostic = container.taskRuntime.diagnostics.entries.value.single()
                val actual = server.takeRequest().body.readUtf8()
                val roles = Json.parseToJsonElement(actual).jsonObject.getValue("messages").jsonArray.map {
                    it.jsonObject.getValue("role").jsonPrimitive.content
                }

                assertEquals(taskId, diagnostic.taskId)
                assertEquals(sessionId, diagnostic.sessionId)
                assertEquals(actual, diagnostic.serializedRequestBody)
                assertEquals(3, server.requestCount)
                assertEquals(2, diagnostic.retryEvents.size)
                assertEquals(DesktopTaskStatus.COMPLETED, diagnostic.status)
                assertEquals("stop", diagnostic.finishReason)
                assertTrue(roles.contains("assistant"))
                assertFalse(diagnostic.toString().contains("fake-task-key"))
                assertFalse(diagnostic.toString().contains("Authorization"))
            }
        }
    }

    @Test
    fun `completed task history is bounded while newer tasks remain visible`() = runBlocking {
        withContainer { container ->
            MockWebServer().use { server ->
                val sessionId = prepare(container, server.url("/v1").toString().trimEnd('/'))
                repeat(3) { server.enqueue(success("answer-$it")) }
                val runtime = DesktopTaskRuntime(
                    realChat = container.createRealChatRuntime(),
                    completedHistoryLimit = 2,
                )
                try {
                    val ids = (1..3).map { index ->
                        runtime.launchChat(sessionId, "turn-$index").also { id ->
                            awaitTask(runtime, id) { it.status == DesktopTaskStatus.COMPLETED }
                        }
                    }
                    assertEquals(ids.drop(1).reversed(), runtime.tasks.value.map { it.taskId })
                    assertTrue(runtime.tasks.value.all { it.startedAt != null && it.completedAt != null })
                } finally {
                    runtime.closeAndDrain()
                }
            }
        }
    }

    private suspend fun prepare(container: DesktopAppContainer, baseUrl: String): String {
        val character = CharacterCard.create("Character", greeting = "Greeting")
        container.characterRepository.save(character)
        val sessionId = container.characterSessionService.createSessionForCharacter(character.id)
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = baseUrl,
            apiKey = "fake-task-key",
            modelName = "fake-model",
            createdAt = 1,
        )
        container.modelRepository.saveModel(model)
        container.settingsRepository.saveAppSettings(
            AppSettings(defaultModelId = model.id, allowCleartextModelApi = true, ragInjectionMode = "OFF"),
        )
        return sessionId
    }

    private suspend fun awaitTask(
        runtime: DesktopTaskRuntime,
        taskId: String,
        predicate: (DesktopTaskEntry) -> Boolean,
    ): DesktopTaskEntry = withTimeout(5_000) {
        runtime.tasks.first { entries -> entries.any { it.taskId == taskId && predicate(it) } }
            .single { it.taskId == taskId }
    }

    private fun task(runtime: DesktopTaskRuntime, taskId: String): DesktopTaskEntry =
        runtime.tasks.value.single { it.taskId == taskId }

    private fun success(content: String) = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        )

    private suspend fun withContainer(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("desktop-task-test-")
        val container = container(parent)
        try {
            block(container)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun container(parent: java.nio.file.Path) = DesktopAppContainer(
        resolvedRoot = DesktopDataRootResolution.Resolved(
            appDataRoot = parent.resolve("app-data"),
            provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
            bootstrapPath = parent.resolve("bootstrap.json"),
        ),
        secretStoreFactory = { InMemoryDesktopSecretStore() },
    )
}

private class HoldingTaskSseServer(payload: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val activeSocket = AtomicReference<Socket?>()
    private val release = CountDownLatch(1)
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    private val worker = thread(name = "holding-task-sse", isDaemon = true) {
        runCatching {
            server.accept().use { socket ->
                activeSocket.set(socket)
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                while (!reader.readLine().isNullOrEmpty()) Unit
                socket.getOutputStream().apply {
                    write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                    write("data: $payload\n\n".toByteArray())
                    flush()
                }
                release.await(10, TimeUnit.SECONDS)
            }
        }
    }

    override fun close() {
        release.countDown()
        activeSocket.getAndSet(null)?.close()
        server.close()
        worker.join(1_000)
    }
}
