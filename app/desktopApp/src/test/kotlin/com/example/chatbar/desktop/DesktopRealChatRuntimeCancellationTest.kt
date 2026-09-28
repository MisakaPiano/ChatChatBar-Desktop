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
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopRealChatRuntimeCancellationTest {
    @Test
    fun `explicit stop after content partial persists interrupted assistant`() = runBlocking {
        assertStoppedDraft(
            payload = """{"choices":[{"delta":{"content":"partial-content"}}]}""",
            expectedContent = "partial-content",
            expectedReasoning = null,
        )
    }

    @Test
    fun `explicit stop after reasoning-only partial persists interrupted assistant`() = runBlocking {
        assertStoppedDraft(
            payload = """{"choices":[{"delta":{"reasoning_content":"partial-reasoning"}}]}""",
            expectedContent = "",
            expectedReasoning = "partial-reasoning",
        )
    }

    @Test
    fun `explicit stop before meaningful delta persists no assistant placeholder`() = runBlocking {
        withRuntime { container ->
            ControlledDesktopSseServer(payload = null).use { server ->
                val sessionId = prepare(container, server.baseUrl)
                val control = DesktopChatGenerationControl()
                val operation = async {
                    container.createRealChatRuntime().sendText(
                        sessionId = sessionId,
                        content = "stop-before-delta",
                        control = control,
                    )
                }
                assertTrue(server.awaitReady())

                assertTrue(control.requestUserStop())
                assertFailsWith<DesktopUserStoppedChatException> { operation.await() }

                val messages = container.chatRepository.getMessages(sessionId)
                assertEquals(1, messages.count { it.role == MessageRole.USER })
                assertEquals(1, messages.count { it.role == MessageRole.ASSISTANT })
                assertFalse(messages.any { it.content == "..." })
            }
        }
    }

    @Test
    fun `generic cancellation after reasoning and content partial persists neither draft nor error assistant`() = runBlocking {
        withRuntime { container ->
            ControlledDesktopSseServer(
                """{"choices":[{"delta":{"reasoning_content":"generic-thought","content":"generic-partial"}}]}""",
            ).use { server ->
                val sessionId = prepare(container, server.baseUrl)
                val partialSeen = CompletableDeferred<Unit>()
                val operation = async {
                    container.createRealChatRuntime().sendText(
                        sessionId = sessionId,
                        content = "generic-cancel",
                        observer = DesktopRealChatObserver { partialSeen.complete(Unit) },
                    )
                }
                withTimeout(3_000) { partialSeen.await() }

                operation.cancel()
                assertFailsWith<CancellationException> { operation.await() }

                val messages = container.chatRepository.getMessages(sessionId)
                assertEquals(1, messages.count { it.role == MessageRole.USER })
                assertEquals(1, messages.count { it.role == MessageRole.ASSISTANT })
                assertFalse(messages.any {
                    it.content.contains("generic-partial") ||
                        it.content.startsWith("错误:") ||
                        it.reasoningContent?.contains("generic-thought") == true
                })
            }
        }
    }

    @Test
    fun `early eof and provider http failure persist one error assistant after durable user`() = runBlocking {
        listOf(
            sse("""{"choices":[{"delta":{"content":"orphan-partial"}}]}""") to "未收到 finish_reason",
            MockResponse().setResponseCode(500).setBody("fake-provider-failure") to "500",
            sse("not-json{{") to "解析 SSE 数据失败",
        ).forEachIndexed { index, (response, expectedFailure) ->
            withRuntime { container ->
                MockWebServer().use { server ->
                    val sessionId = prepare(container, server.url("/v1").toString().trimEnd('/'))
                    server.enqueue(response)

                    val result = container.createRealChatRuntime().sendText(sessionId, "failure-$index")
                    val messages = container.chatRepository.getMessages(sessionId)
                    val user = messages.single { it.role == MessageRole.USER }
                    val generated = messages.filter { it.role == MessageRole.ASSISTANT }.drop(1)

                    assertEquals("failure-$index", user.content)
                    assertEquals(1, generated.size)
                    assertTrue(generated.single().content.startsWith("错误: "))
                    assertTrue(requireNotNull(result.failureMessage).contains(expectedFailure))
                    assertEquals(generated.single(), result.assistant)
                }
            }
        }
    }

    @Test
    fun `transport failure after finish reason remains completed transport-failed success`() = runBlocking {
        TruncatedDesktopSseServer(
            """{"choices":[{"delta":{"content":"accepted-partial"},"finish_reason":"stop"}]}""",
        ).use { server ->
            withRuntime { container ->
                val sessionId = prepare(container, server.baseUrl)

                val result = withTimeout(5_000) {
                    container.createRealChatRuntime().sendText(sessionId, "finish-before-drop")
                }
                val generated = container.chatRepository.getMessages(sessionId)
                    .filter { it.role == MessageRole.ASSISTANT }
                    .drop(1)

                assertEquals(1, generated.size)
                assertEquals("accepted-partial", generated.single().content)
                assertNull(result.failureMessage)
                assertTrue(requireNotNull(result.completion).transportFailed)
            }
        }
    }

    @Test
    fun `blank and reasoning-only full completions obey assistant body policy`() = runBlocking {
        listOf(
            arrayOf("[DONE]"),
            arrayOf(
                """{"choices":[{"delta":{"reasoning_content":"full-reasoning-only"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        ).forEachIndexed { index, payloads ->
            withRuntime { container ->
                MockWebServer().use { server ->
                    val sessionId = prepare(container, server.url("/v1").toString().trimEnd('/'))
                    server.enqueue(sse(*payloads))

                    val result = container.createRealChatRuntime().sendText(sessionId, "invalid-body-$index")
                    val generated = container.chatRepository.getMessages(sessionId)
                        .filter { it.role == MessageRole.ASSISTANT }
                        .drop(1)

                    assertEquals(1, generated.size)
                    assertTrue(generated.single().content.startsWith("错误: "))
                    assertNull(generated.single().reasoningContent)
                    assertNotNull(result.failureMessage)
                }
            }
        }
    }

    private suspend fun assertStoppedDraft(
        payload: String,
        expectedContent: String,
        expectedReasoning: String?,
    ) {
        withRuntime { container ->
            ControlledDesktopSseServer(payload).use { server ->
                val sessionId = prepare(container, server.baseUrl)
                val partialSeen = CompletableDeferred<Unit>()
                val control = DesktopChatGenerationControl()
                val operation = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).async {
                    container.createRealChatRuntime().sendText(
                        sessionId = sessionId,
                        content = "explicit-stop",
                        observer = DesktopRealChatObserver { partialSeen.complete(Unit) },
                        control = control,
                    )
                }
                withTimeout(3_000) { partialSeen.await() }

                assertTrue(control.requestUserStop())
                assertFailsWith<DesktopUserStoppedChatException> { operation.await() }

                val generated = container.chatRepository.getMessages(sessionId)
                    .filter { it.role == MessageRole.ASSISTANT }
                    .drop(1)
                assertEquals(1, generated.size)
                assertEquals(expectedContent, generated.single().content)
                assertEquals(expectedReasoning, generated.single().reasoningContent)
                assertFalse(generated.single().content == "...")
            }
        }
    }

    private suspend fun prepare(
        container: DesktopAppContainer,
        baseUrl: String,
    ): String {
        val character = CharacterCard.create("Character", greeting = "Greeting")
        container.characterRepository.save(character)
        val sessionId = container.characterSessionService.createSessionForCharacter(character.id)
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = baseUrl,
            apiKey = "fake-stop-secret",
            modelName = "fake-model",
            createdAt = 1,
        )
        container.modelRepository.saveModel(model)
        container.settingsRepository.saveAppSettings(
            AppSettings(defaultModelId = model.id, allowCleartextModelApi = true, ragInjectionMode = "OFF"),
        )
        return sessionId
    }

    private suspend fun withRuntime(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("desktop-real-chat-cancel-")
        val container = DesktopAppContainer(
            resolvedRoot = DesktopDataRootResolution.Resolved(
                appDataRoot = parent.resolve("app-data"),
                provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try {
            block(container)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun event(payload: String): String = "data: $payload\n\n"

    private fun sse(vararg payloads: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setBody(payloads.joinToString("") { event(it) })
}

private class TruncatedDesktopSseServer(payload: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    private val worker = thread(name = "desktop-truncated-sse", isDaemon = true) {
        server.accept().use { socket ->
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            while (!reader.readLine().isNullOrEmpty()) Unit
            val body = "data: $payload\n\n".toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/event-stream\r\n")
                append("Content-Length: ${body.size + 100}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            socket.getOutputStream().apply {
                write(headers)
                write(body)
                flush()
            }
        }
    }

    override fun close() {
        server.close()
        worker.join(1_000)
    }
}

internal class ControlledDesktopSseServer(payload: String?) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val activeSocket = AtomicReference<Socket?>()
    private val ready = CountDownLatch(1)
    private val release = CountDownLatch(1)
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    private val worker = thread(name = "desktop-controlled-sse", isDaemon = true) {
        runCatching {
            server.accept().use { socket ->
                activeSocket.set(socket)
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                while (!reader.readLine().isNullOrEmpty()) Unit
                socket.getOutputStream().apply {
                    write(
                        buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: text/event-stream\r\n")
                            append("Connection: close\r\n\r\n")
                        }.toByteArray(Charsets.US_ASCII),
                    )
                    payload?.let { write("data: $it\n\n".toByteArray(Charsets.UTF_8)) }
                    flush()
                }
                ready.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        ready.countDown()
    }

    suspend fun awaitReady(): Boolean = withContext(Dispatchers.IO) {
        ready.await(3, TimeUnit.SECONDS)
    }

    override fun close() {
        release.countDown()
        activeSocket.getAndSet(null)?.close()
        server.close()
        worker.join(1_000)
    }
}
