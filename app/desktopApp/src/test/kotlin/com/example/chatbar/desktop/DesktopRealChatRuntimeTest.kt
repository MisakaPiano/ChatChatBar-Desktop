package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopRealChatRuntimeTest {
    @Test
    fun `persisted history exclusion reaches shared request policy while latest assistant stays complete`() = runBlocking {
        for (exclude in listOf(true, false)) withRuntimeContainer { container, _, _ ->
            MockWebServer().use { server ->
                val card = CharacterCard.create("Inline")
                container.characterRepository.save(card)
                val id = container.characterSessionService.createSessionForCharacter(card.id)
                configureModel(container, server, "fake-exclusion-key")
                container.settingsRepository.updateAppSettings { it.copy(excludeAssistantStatusFromHistory = exclude) }
                val earlier = "earlier-body\n```status\nearlier-status-token\n```\n------\n[earlier-option-token]()\n------"
                val previous = "previous-body\n```status\nprevious-status-token\n```"
                listOf(MessageRole.USER to "first", MessageRole.ASSISTANT to earlier,
                    MessageRole.USER to "second", MessageRole.ASSISTANT to previous).forEach { (role, text) ->
                    container.chatRepository.addMessage(ChatMessage.create(id, role, text))
                }
                server.enqueue(sse("""{"choices":[{"delta":{"content":"reply"},"finish_reason":"stop"}]}""", "[DONE]"))
                container.createRealChatRuntime().sendText(id, "third")
                val contents = serializedContents(server.takeRequest().body.readUtf8()).joinToString("\n")
                assertTrue(contents.contains("earlier-body"))
                assertEquals(!exclude, contents.contains("earlier-status-token"))
                assertEquals(!exclude, contents.contains("earlier-option-token"))
                assertTrue(contents.contains("previous-status-token"))
                assertTrue(container.chatRepository.getMessages(id).any { it.content == earlier })
            }
        }
    }

    @Test
    fun `normal turn persists durable inputs before real transport and stores raw streamed assistant`() = runBlocking {
        withRuntimeContainer { container, root, _ ->
            MockWebServer().use { server ->
                val book = WorldBook(
                    id = "book",
                    name = "Book",
                    entries = listOf(
                        WorldBookEntry(
                            id = "entry",
                            keys = listOf("trigger"),
                            content = "world-lore",
                            sticky = 2,
                            probability = 100,
                        ),
                    ),
                    createdAt = 1,
                    updatedAt = 1,
                )
                container.worldBookRepository.save(book)
                val character = CharacterCard.create("Character", greeting = "Greeting").copy(
                    botName = "Bot",
                    worldBookIds = listOf(book.id),
                )
                container.characterRepository.save(character)
                val sessionId = container.characterSessionService.createSessionForCharacter(character.id)
                configureModel(container, server, apiKey = "fake-runtime-key")
                container.settingsRepository.savePlayerSetting(
                    PlayerSetting(playerName = "Alice", globalPersona = "player-setting"),
                )

                var messagesAtRequest: List<ChatMessage>? = null
                var timedAtRequest: Map<String, *>? = null
                var requestBody: String? = null
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requestBody = request.body.readUtf8()
                        runBlocking {
                            messagesAtRequest = container.chatRepository.getMessages(sessionId)
                            timedAtRequest = requireNotNull(container.chatRepository.getSession(sessionId)).timedWorldInfo
                        }
                        return sse(
                            """{"choices":[{"delta":{"reasoning_content":"Think {{char}}"}}]}""",
                            """{"choices":[{"delta":{"content":"Answer {{user}}"},"finish_reason":"stop"}]}""",
                            "[DONE]",
                        )
                    }
                }

                val updates = mutableListOf<DesktopRealChatStreamUpdate>()
                val result = withTimeout(5_000) {
                    container.createRealChatRuntime().sendText(
                        sessionId = sessionId,
                        content = "trigger {{user}}",
                        observer = DesktopRealChatObserver(updates::add),
                    )
                }
                val persisted = container.chatRepository.getMessages(sessionId)
                val timedAfterCompletion = requireNotNull(container.chatRepository.getSession(sessionId)).timedWorldInfo
                val user = persisted.single { it.role == MessageRole.USER }
                val assistant = persisted.last()
                val body = Json.parseToJsonElement(requireNotNull(requestBody)).jsonObject
                val serializedMessages = body.getValue("messages").jsonArray.map { element ->
                    element.jsonObject.getValue("content").jsonPrimitive.content
                }
                val recordedRequest = server.takeRequest()

                assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), messagesAtRequest?.map(ChatMessage::role))
                assertTrue(requireNotNull(timedAtRequest).isNotEmpty())
                assertEquals("/v1/chat/completions", recordedRequest.path)
                assertEquals("Bearer fake-runtime-key", recordedRequest.getHeader("Authorization"))
                assertEquals(1, serializedMessages.count { it.contains("trigger Alice") })
                assertTrue(serializedMessages.any { it.contains("world-lore") })
                assertEquals("trigger {{user}}", user.content)
                assertEquals("Answer {{user}}", assistant.content)
                assertEquals("Think {{char}}", assistant.reasoningContent)
                assertEquals(user.sourceTurnId, assistant.sourceTurnId)
                assertEquals(user.sourceTurnOrder, assistant.sourceTurnOrder)
                assertEquals(assistant, result.assistant)
                assertTrue(result.currentUserPersisted)
                assertNull(result.failureMessage)
                assertEquals("Answer {{user}}", updates.last().content)
                assertEquals(timedAtRequest, timedAfterCompletion)
                val modelPath = root.resolve(
                    "entities/model_configs/${WindowsSafeModelStorageKeyPolicy.storageKey("model")}.json",
                )
                assertFalse(Files.readString(modelPath).contains("fake-runtime-key"))
            }
        }
    }

    @Test
    fun `restart blank continue uses one transient user scans worldbook and persists second assistant`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-real-chat-restart-")
        val root = parent.resolve("app-data")
        val secrets = InMemoryDesktopSecretStore()
        val continuationPrompt = MainChatPromptAuthority.continueGenerationUserPrompt()
        MockWebServer().use { server ->
            val bodies = mutableListOf<String>()
            var timedAtSecondRequest: Map<String, *>? = null
            var sessionId = ""
            val requests = AtomicInteger(0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val number = requests.incrementAndGet()
                    synchronized(bodies) { bodies += request.body.readUtf8() }
                    if (number == 2) {
                        runBlocking {
                            val inspection = newContainer(root, parent, secrets)
                            try {
                                timedAtSecondRequest = requireNotNull(
                                    inspection.chatRepository.getSession(sessionId),
                                ).timedWorldInfo
                            } finally {
                                inspection.close()
                            }
                        }
                    }
                    return if (number == 1) {
                        sse("""{"choices":[{"delta":{"content":"first-answer"},"finish_reason":"stop"}]}""", "[DONE]")
                    } else {
                        sse("""{"choices":[{"delta":{"content":"continued-answer"},"finish_reason":"stop"}]}""", "[DONE]")
                    }
                }
            }

            val first = newContainer(root, parent, secrets)
            try {
                val book = WorldBook(
                    id = "continue-book",
                    name = "Continue Book",
                    entries = listOf(
                        WorldBookEntry(
                            id = "continue-entry",
                            keys = listOf(continuationPrompt),
                            content = "transient-continue-lore",
                            sticky = 2,
                            probability = 100,
                        ),
                    ),
                    createdAt = 1,
                    updatedAt = 1,
                )
                first.worldBookRepository.save(book)
                val character = CharacterCard.create("Character", greeting = "Greeting").copy(
                    worldBookIds = listOf(book.id),
                )
                first.characterRepository.save(character)
                sessionId = first.characterSessionService.createSessionForCharacter(character.id)
                configureModel(first, server, apiKey = "restart-secret")
                first.createRealChatRuntime().sendText(sessionId, "first-user")
            } finally {
                first.close()
            }

            val second = newContainer(root, parent, secrets)
            try {
                val runtime = second.createRealChatRuntime()
                val reopened = runtime.openSession(sessionId)
                val userCountBefore = reopened.messages.count { it.role == MessageRole.USER }
                val firstReply = reopened.messages.last { it.role == MessageRole.ASSISTANT }

                val result = runtime.sendText(sessionId, "")
                val persisted = second.chatRepository.getMessages(sessionId)
                val secondBody = synchronized(bodies) { bodies[1] }
                val serialized = serializedContents(secondBody)
                val timedAfter = requireNotNull(second.chatRepository.getSession(sessionId)).timedWorldInfo

                assertFalse(result.currentUserPersisted)
                assertEquals(continuationPrompt, result.currentUser.content)
                assertEquals(userCountBefore, persisted.count { it.role == MessageRole.USER })
                assertEquals(1, persisted.count { it.role == MessageRole.USER })
                assertFalse(persisted.any { it.id == result.currentUser.id })
                assertEquals(3, persisted.count { it.role == MessageRole.ASSISTANT })
                assertEquals("continued-answer", persisted.last().content)
                assertEquals(1, serialized.count { it == continuationPrompt })
                assertTrue(serialized.any { it == "first-answer" })
                assertTrue(serialized.any { it.contains("transient-continue-lore") })
                assertTrue(requireNotNull(timedAtSecondRequest).isNotEmpty())
                assertEquals(timedAtSecondRequest, timedAfter)
                assertNotNull(result.assistant.sourceTurnId)
                assertEquals(firstReply.sourceTurnId, result.assistant.sourceTurnId)
                assertEquals(firstReply.sourceTurnOrder, result.assistant.sourceTurnOrder)
                assertEquals(2, requests.get())
            } finally {
                second.close()
            }
        }
        parent.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun `blank continue resumes latest unanswered user without duplicating it`() = runBlocking {
        withRuntimeContainer { container, _, _ ->
            MockWebServer().use { server ->
                val character = CharacterCard.create("Character", greeting = "Greeting")
                container.characterRepository.save(character)
                val sessionId = container.characterSessionService.createSessionForCharacter(character.id)
                configureModel(container, server, apiKey = "resume-secret")
                val pending = container.chatRepository.addMessage(
                    ChatMessage.create(sessionId, MessageRole.USER, "pending-user"),
                )
                server.enqueue(sse(
                    """{"choices":[{"delta":{"content":"resumed-answer"},"finish_reason":"stop"}]}""",
                    "[DONE]",
                ))

                val result = container.createRealChatRuntime().sendText(sessionId, "")
                val body = server.takeRequest().body.readUtf8()
                val contents = serializedContents(body)
                val persisted = container.chatRepository.getMessages(sessionId)

                assertTrue(result.currentUserPersisted)
                assertEquals(pending.id, result.currentUser.id)
                assertEquals(1, persisted.count { it.role == MessageRole.USER })
                assertEquals(1, contents.count { it == "pending-user" })
                assertFalse(contents.contains(MainChatPromptAuthority.continueGenerationUserPrompt()))
                assertEquals(pending.sourceTurnId, result.assistant.sourceTurnId)
                assertEquals(pending.sourceTurnOrder, result.assistant.sourceTurnOrder)
            }
        }
    }

    private suspend fun configureModel(
        container: DesktopAppContainer,
        server: MockWebServer,
        apiKey: String,
    ) {
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = apiKey,
            modelName = "fake-model",
            createdAt = 1,
        )
        container.modelRepository.saveModel(model)
        container.settingsRepository.saveAppSettings(
            AppSettings(
                defaultModelId = model.id,
                allowCleartextModelApi = true,
                defaultContextWindowSize = 20,
                ragInjectionMode = "OFF",
            ),
        )
    }

    private suspend fun withRuntimeContainer(
        block: suspend (DesktopAppContainer, Path, InMemoryDesktopSecretStore) -> Unit,
    ) {
        val parent = Files.createTempDirectory("desktop-real-chat-")
        val root = parent.resolve("app-data")
        val secrets = InMemoryDesktopSecretStore()
        val container = DesktopAppContainer(
            resolvedRoot = DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
            secretStoreFactory = { secrets },
        )
        try {
            block(container, root, secrets)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun newContainer(
        root: Path,
        parent: Path,
        secrets: InMemoryDesktopSecretStore,
    ) = DesktopAppContainer(
        resolvedRoot = DesktopDataRootResolution.Resolved(
            appDataRoot = root,
            provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
            bootstrapPath = parent.resolve("bootstrap.json"),
        ),
        secretStoreFactory = { secrets },
    )

    private fun serializedContents(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray.map { element ->
            element.jsonObject.getValue("content").jsonPrimitive.content
        }

    private fun sse(vararg payloads: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setBody(payloads.joinToString("") { "data: $it\n\n" })

}
