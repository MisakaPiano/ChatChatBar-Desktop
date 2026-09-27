package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopRealChatRuntimeResolutionTest {
    @Test
    fun `explicit session model then missing selection use explicit and default hydrated models`() = runBlocking {
        withRuntime { container ->
            MockWebServer().use { server ->
                val sessionId = createSession(container)
                val defaultModel = model("default", server, "default-secret")
                val explicitModel = model("explicit", server, "explicit-secret")
                container.modelRepository.saveModel(defaultModel)
                container.modelRepository.saveModel(explicitModel)
                container.settingsRepository.saveAppSettings(
                    AppSettings(
                        defaultModelId = defaultModel.id,
                        allowCleartextModelApi = true,
                        ragInjectionMode = "OFF",
                    ),
                )
                val initial = requireNotNull(container.chatRepository.getSession(sessionId))
                container.chatRepository.updateSession(initial.copy(modelId = explicitModel.id))
                server.enqueue(success("explicit-answer"))
                server.enqueue(success("default-answer"))

                container.createRealChatRuntime().sendText(sessionId, "explicit-user")
                val explicitRequest = server.takeRequest()
                container.chatRepository.updateSession(
                    requireNotNull(container.chatRepository.getSession(sessionId)).copy(modelId = "missing-model"),
                )
                container.createRealChatRuntime().sendText(sessionId, "fallback-user")
                val defaultRequest = server.takeRequest()

                assertEquals("explicit-model", requestModel(explicitRequest.body.readUtf8()))
                assertEquals("Bearer explicit-secret", explicitRequest.getHeader("Authorization"))
                assertEquals("default-model", requestModel(defaultRequest.body.readUtf8()))
                assertEquals("Bearer default-secret", defaultRequest.getHeader("Authorization"))
            }
        }
    }

    @Test
    fun `https blank model credential inherits hydrated global key through shared resolver`() = runBlocking {
        withRuntime { container ->
            val httpsModel = ModelConfig(
                id = "https-model",
                displayName = "HTTPS",
                baseUrl = "https://desktop.invalid/v1",
                apiKey = "",
                modelName = "https-model",
                createdAt = 1,
            )
            container.modelRepository.saveModel(httpsModel)
            val settings = AppSettings(
                defaultModelId = httpsModel.id,
                siliconFlowApiKey = "global-fake-secret",
                allowCleartextModelApi = false,
            )
            container.settingsRepository.saveAppSettings(settings)

            val hydratedSettings = container.settingsRepository.getAppSettings()
            val resolved = requireNotNull(
                container.effectiveModelResolver.resolveChatModel(httpsModel.id, hydratedSettings),
            )

            assertEquals("global-fake-secret", hydratedSettings.siliconFlowApiKey)
            assertEquals("global-fake-secret", resolved.apiKey)
            assertTrue(container.effectiveModelResolver.status(httpsModel.id, hydratedSettings).isUsable)
        }
    }

    @Test
    fun `allowed local http blank credential sends without authorization`() = runBlocking {
        withRuntime { container ->
            MockWebServer().use { server ->
                val sessionId = createSession(container)
                val local = model("local", server, apiKey = "")
                container.modelRepository.saveModel(local)
                container.settingsRepository.saveAppSettings(
                    AppSettings(
                        defaultModelId = local.id,
                        allowCleartextModelApi = true,
                        ragInjectionMode = "OFF",
                    ),
                )
                server.enqueue(success("local-answer"))

                container.createRealChatRuntime().sendText(sessionId, "local-user")
                val request = server.takeRequest()

                assertNull(request.getHeader("Authorization"))
                assertEquals("local-model", requestModel(request.body.readUtf8()))
            }
        }
    }

    @Test
    fun `unusable authentication fails before user persistence and transport`() = runBlocking {
        withRuntime { container ->
            MockWebServer().use { server ->
                val sessionId = createSession(container)
                val unusable = model("unusable", server, apiKey = "")
                container.modelRepository.saveModel(unusable)
                container.settingsRepository.saveAppSettings(
                    AppSettings(defaultModelId = unusable.id, allowCleartextModelApi = false),
                )

                assertFailsWith<DesktopChatConfigurationException> {
                    container.createRealChatRuntime().sendText(sessionId, "must-not-persist")
                }

                val messages = container.chatRepository.getMessages(sessionId)
                assertEquals(listOf(MessageRole.ASSISTANT), messages.map { it.role })
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test
    fun `invalid active format tools fail before user persistence and transport`() = runBlocking {
        withRuntime { container ->
            MockWebServer().use { server ->
                val invalidFormat = FormatCard(
                    id = "invalid-format",
                    name = "Invalid",
                    content = "format",
                    userTools = listOf(
                        FormatCardUserToolConfig.randomNumber().copy(minimum = "10", maximum = "2"),
                    ),
                    createdAt = 1,
                )
                container.formatCardRepository.save(invalidFormat)
                val character = CharacterCard.create("Character", greeting = "Greeting").copy(
                    defaultFormatCardId = invalidFormat.id,
                )
                container.characterRepository.save(character)
                val sessionId = container.characterSessionService.createSessionForCharacter(character.id)
                val usable = model("usable", server, "fake-secret")
                container.modelRepository.saveModel(usable)
                container.settingsRepository.saveAppSettings(
                    AppSettings(defaultModelId = usable.id, allowCleartextModelApi = true),
                )

                assertFailsWith<DesktopChatConfigurationException> {
                    container.createRealChatRuntime().sendText(sessionId, "must-not-persist")
                }

                val messages = container.chatRepository.getMessages(sessionId)
                assertEquals(listOf(MessageRole.ASSISTANT), messages.map { it.role })
                assertEquals(0, server.requestCount)
            }
        }
    }

    private suspend fun createSession(container: DesktopAppContainer): String {
        val character = CharacterCard.create("Character", greeting = "Greeting")
        container.characterRepository.save(character)
        return container.characterSessionService.createSessionForCharacter(character.id)
    }

    private fun model(
        id: String,
        server: MockWebServer,
        apiKey: String,
    ) = ModelConfig(
        id = id,
        displayName = id,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = apiKey,
        modelName = "$id-model",
        createdAt = 1,
    )

    private fun success(content: String) = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        )

    private fun requestModel(body: String): String = Json.parseToJsonElement(body)
        .jsonObject.getValue("model").jsonPrimitive.content

    private suspend fun withRuntime(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("desktop-real-chat-resolution-")
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
}
