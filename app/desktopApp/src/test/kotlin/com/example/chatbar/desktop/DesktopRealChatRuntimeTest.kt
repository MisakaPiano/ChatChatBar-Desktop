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
import java.nio.file.Files
import java.nio.file.Path
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
                assertNull(result.failureMessage)
                assertEquals("Answer {{user}}", updates.last().content)
                val modelPath = root.resolve(
                    "entities/model_configs/${WindowsSafeModelStorageKeyPolicy.storageKey("model")}.json",
                )
                assertFalse(Files.readString(modelPath).contains("fake-runtime-key"))
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

    private fun sse(vararg payloads: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setBody(payloads.joinToString("") { "data: $it\n\n" })

}
