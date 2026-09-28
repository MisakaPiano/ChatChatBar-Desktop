package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.ConnectionProbeStatus
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopConnectionTestControllerTest {
    @Test fun `fresh root operation is unavailable without business writes`() = runBlocking {
        fixture { container, server ->
            val controller = container.connectionTestController
            assertFalse(Files.exists(container.appDataRoot))
            assertEquals(0, server.requestCount)
            assertTrue(controller.start())
            val state = withTimeout(10_000) { controller.state.first { !it.running } }
            assertEquals(ConnectionProbeStatus.NOT_CONFIGURED, state.chat?.status)
            Files.walk(container.appDataRoot).use { paths -> assertFalse(paths.anyMatch(Files::isRegularFile)) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `test consumes saved credentials rather than unsaved editor key and persists no chat`() = runBlocking {
        fixture { container, server ->
            configure(container, server)
            val controller = container.connectionTestController
            container.modelSettingsController.openCredentialEditor()
            container.modelSettingsController.editCredentialDraft("fake-unsaved-key")
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"works"}}]}"""))
            assertTrue(controller.start())
            val state = withTimeout(10_000) { controller.state.first { !it.running } }
            assertEquals(ConnectionProbeStatus.SUCCESS, state.chat?.status)
            assertEquals("Bearer fake-saved-key", server.takeRequest().getHeader("Authorization"))
            assertFalse(state.toString().contains("fake-saved-key"))
            assertFalse(state.toString().contains("fake-unsaved-key"))
            assertTrue(container.chatRepository.getAllSessions().isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `only one application owned operation runs and explicit stop cancels`() = runBlocking {
        fixture { container, server ->
            configure(container, server)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val controller = container.connectionTestController
            assertTrue(controller.start())
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
            assertFalse(controller.start())
            val navigation = DesktopPrimaryNavigationController()
            val root = DesktopDataRootSwitchState.Idle(container.appDataRoot, DesktopDataRootProvenance.CLI_OVERRIDE, true)
            assertTrue(navigation.navigate(DesktopPrimaryRoute.MANAGE, root))
            assertTrue(navigation.navigate(DesktopPrimaryRoute.CHAT, root))
            assertTrue(controller.state.value.running)
            controller.stop()
            val state = withTimeout(10_000) { controller.state.first { !it.running } }
            assertTrue(state.cancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `container close cancels and drains the probe`() = runBlocking {
        fixture { container, server ->
            configure(container, server)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val controller = container.connectionTestController
            controller.start()
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
            withTimeout(15_000) { container.close() }
            assertFalse(controller.state.value.running)
            assertFalse(controller.start())
            assertEquals(1, server.requestCount)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `stop before dispatch retires operation without network or writes`() = kotlinx.coroutines.test.runTest {
        fixture { container, server ->
            val controller = DesktopConnectionTestController(container.settingsRepository, container.effectiveModelResolver,
                kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            assertTrue(controller.start())
            controller.stop()
            testScheduler.advanceUntilIdle()
            assertFalse(controller.state.value.running)
            assertTrue(controller.state.value.cancelled)
            assertFalse(Files.exists(container.appDataRoot))
            assertEquals(0, server.requestCount)
            controller.closeAndDrain()
        }
    }

    private suspend fun configure(container: DesktopAppContainer, server: MockWebServer) {
        container.modelRepository.saveModel(ModelConfig(id = "probe", displayName = "Fake",
            baseUrl = server.url("/v1").toString().trimEnd('/'), modelName = "fake", apiKey = "fake-saved-key", createdAt = 1))
        container.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "probe", allowCleartextModelApi = true))
    }
    private suspend fun fixture(block: suspend (DesktopAppContainer, MockWebServer) -> Unit) {
        val parent = Files.createTempDirectory("desktop-probe-")
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try { MockWebServer().use { block(container, it) } }
        finally { container.close(); parent.toFile().deleteRecursively() }
    }
}
