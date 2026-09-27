package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
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

class DesktopAlphaChatControllerTest {
    @Test
    fun `default Alpha refresh on fresh root creates no business data`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-alpha-fresh-refresh-")
        val root = parent.resolve("app-data")
        val container = DesktopAppContainer(
            resolvedRoot = DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try {
            val controller = container.alphaChatController
            assertFalse(Files.exists(root))
            controller.refresh()
            val writtenFiles = Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) }.toList()
            }
            assertTrue(writtenFiles.isEmpty(), "Empty chat refresh wrote business files: $writtenFiles")
            assertTrue(controller.state.value.characters.isEmpty())
            assertTrue(controller.state.value.sessions.isEmpty())
            assertNull(controller.state.value.selectedSessionId)
            assertFalse(controller.state.value.modelUsable)
            assertEquals("Select or create a session", controller.state.value.configurationMessage)
            assertNull(controller.state.value.error)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `refresh with persisted session uses configured settings and model resolver`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("Configured character", greeting = "Greeting")
            container.characterRepository.save(character)
            val model = ModelConfig(
                id = "configured-model", displayName = "Configured model", modelName = "fake-model",
                baseUrl = "http://127.0.0.1:12345/v1", apiKey = "fake-controller-key", createdAt = 1,
            )
            container.modelRepository.saveModel(model)
            container.settingsRepository.saveAppSettings(
                AppSettings(defaultModelId = model.id, allowCleartextModelApi = true, ragInjectionMode = "OFF"),
            )
            val sessionId = container.characterSessionService.createSessionForCharacter(character.id)

            val controller = container.alphaChatController
            controller.refresh()

            assertEquals(sessionId, controller.state.value.selectedSessionId)
            assertTrue(controller.state.value.modelUsable)
            assertNull(controller.state.value.configurationMessage)
            assertNull(controller.state.value.error)
        }
    }

    @Test
    fun `container task and Alpha controller construction write no app data`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-alpha-zero-write-")
        val root = parent.resolve("app-data")
        val container = DesktopAppContainer(
            resolvedRoot = DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try {
            container.taskRuntime
            container.alphaChatController
            assertFalse(Files.exists(root))
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
        Unit
    }

    @Test
    fun `reopened controller reconnects to application task and persisted session`() = runBlocking {
        withContainer { container ->
            MockWebServer().use { server ->
                val character = CharacterCard.create("Alpha character", greeting = "Greeting")
                container.characterRepository.save(character)
                val model = ModelConfig(
                    id = "model", displayName = "Model", modelName = "fake-model",
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "fake-controller-key", createdAt = 1,
                )
                container.modelRepository.saveModel(model)
                container.settingsRepository.saveAppSettings(
                    AppSettings(defaultModelId = model.id, allowCleartextModelApi = true, ragInjectionMode = "OFF"),
                )
                server.enqueue(
                    MockResponse().addHeader("Content-Type", "text/event-stream")
                        .setBody("data: [DONE]\n\n")
                        .setBodyDelay(5, TimeUnit.SECONDS),
                )
                val firstPanelController = controller(container)
                firstPanelController.refresh()
                firstPanelController.createSession(character.id)
                val sessionId = requireNotNull(firstPanelController.state.value.selectedSessionId)
                assertEquals("Greeting", firstPanelController.state.value.messages.single().content)
                val taskId = requireNotNull(firstPanelController.send("persisted-user"))
                withTimeout(3_000) { while (server.requestCount == 0) delay(10) }

                // Dropping the first panel/controller has no ownership effect on the container task.
                val reopened = controller(container)
                reopened.refresh()
                assertEquals(sessionId, reopened.state.value.selectedSessionId)
                assertTrue(reopened.state.value.modelUsable)
                assertEquals(DesktopTaskStatus.RUNNING, container.taskRuntime.tasks.value.single().status)
                assertEquals(1, container.chatRepository.getMessages(sessionId).count { it.role == MessageRole.USER })
                assertNotNull(reopened.diagnostic(taskId))

                assertTrue(reopened.stop(taskId))
                withTimeout(5_000) {
                    container.taskRuntime.tasks.first { it.single().status == DesktopTaskStatus.USER_STOPPED }
                }
                reopened.refresh()
                assertEquals(1, reopened.state.value.messages.count { it.role == MessageRole.USER })
            }
        }
    }

    @Test
    fun `missing usable model disables send before user persistence`() = runBlocking {
        withContainer { container ->
            val character = CharacterCard.create("No model", greeting = "Greeting")
            container.characterRepository.save(character)
            val controller = controller(container)
            controller.refresh()
            controller.createSession(character.id)
            val sessionId = requireNotNull(controller.state.value.selectedSessionId)

            assertFalse(controller.state.value.modelUsable)
            assertNotNull(controller.state.value.configurationMessage)
            assertNull(controller.send("must-not-persist"))
            assertEquals(0, container.chatRepository.getMessages(sessionId).count { it.role == MessageRole.USER })
            assertTrue(container.taskRuntime.tasks.value.isEmpty())
        }
    }

    private fun controller(container: DesktopAppContainer) = DesktopAlphaChatController(
        characterRepository = container.characterRepository,
        chatRepository = container.chatRepository,
        settingsRepository = container.settingsRepository,
        modelResolver = container.effectiveModelResolver,
        realChat = container.createRealChatRuntime(),
        taskRuntime = container.taskRuntime,
    )

    private suspend fun withContainer(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("desktop-alpha-controller-")
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
