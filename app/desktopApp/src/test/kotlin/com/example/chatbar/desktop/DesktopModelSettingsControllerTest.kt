package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopModelSettingsControllerTest {
    @Test
    fun `model create edit replace clear and delete use secure repository credential boundary`() = runBlocking {
        withFixture { fixture ->
            val controller = fixture.controller
            controller.loadModels()
            controller.startCreate()
            controller.applyTemplate(com.example.chatbar.data.local.entity.ModelTemplate.CUSTOM)
            controller.editModel { it.copy(displayName = "Local", baseUrl = "https://example.invalid/v1", modelName = "local-chat") }
            controller.replaceModelCredential("fake-first-key")
            controller.saveModel()
            val id = controller.state.value.models.single().id
            assertEquals("fake-first-key", fixture.container.modelRepository.getModel(id)?.apiKey)
            assertEquals("fake-first-key", fixture.secrets.values[DesktopCredentialKey.ModelApiKey(id)])
            assertSanitizedModel(fixture.root, id, listOf("fake-first-key"))
            assertIs<DesktopCredentialEdit.Unchanged>(controller.state.value.editor?.credentialEdit)
            assertTrue(controller.state.value.editor?.hasSavedCredential == true)
            assertFalse(controller.state.value.models.toString().contains("fake-first-key"))

            controller.startEdit(id)
            controller.editModel { it.copy(displayName = "Renamed") }
            controller.saveModel()
            assertEquals("fake-first-key", fixture.secrets.values[DesktopCredentialKey.ModelApiKey(id)])
            assertEquals("Renamed", fixture.container.modelRepository.getModel(id)?.displayName)

            controller.replaceModelCredential("")
            controller.saveModel()
            assertEquals("Enter a key or choose Clear", controller.state.value.error)
            assertEquals("fake-first-key", fixture.secrets.values[DesktopCredentialKey.ModelApiKey(id)])

            controller.replaceModelCredential("fake-second-key")
            controller.saveModel()
            assertEquals("fake-second-key", fixture.secrets.values[DesktopCredentialKey.ModelApiKey(id)])
            assertSanitizedModel(fixture.root, id, listOf("fake-first-key", "fake-second-key"))

            controller.clearModelCredential()
            controller.saveModel()
            assertNull(fixture.secrets.values[DesktopCredentialKey.ModelApiKey(id)])
            assertEquals("", fixture.container.modelRepository.getModel(id)?.apiKey)
            assertSanitizedModel(fixture.root, id, listOf("fake-first-key", "fake-second-key"))

            controller.replaceModelCredential("fake-delete-key")
            controller.saveModel()
            controller.deleteModel(id)
            assertNull(fixture.container.modelRepository.getModel(id))
            assertNull(fixture.secrets.values[DesktopCredentialKey.ModelApiKey(id)])
        }
    }

    @Test
    fun `editor preserves current model metadata and stores typed custom parameters`() = runBlocking {
        withFixture { fixture ->
            val source = ModelConfig(
                id = "preset:logical", displayName = "Preset", baseUrl = "https://example.invalid/v1",
                apiKey = "fake-preset-key", modelName = "prior", sourcePresetKey = "logical",
                sourcePresetVersion = 8, createdAt = 71L,
            )
            fixture.container.modelRepository.saveModel(source)
            val controller = fixture.controller
            controller.startEdit(source.id)
            controller.editModel { draft ->
                draft.copy(
                    displayName = "Edited",
                    customParams = listOf(
                        DesktopParameterDraft("temperature", DesktopParameterKind.NUMBER, "0.75"),
                        DesktopParameterDraft("enabled", DesktopParameterKind.BOOLEAN, "true"),
                        DesktopParameterDraft("label", DesktopParameterKind.STRING, "value"),
                    ),
                )
            }
            controller.saveModel()
            val saved = assertNotNull(fixture.container.modelRepository.getModel(source.id))
            assertEquals(71L, saved.createdAt)
            assertEquals("logical", saved.sourcePresetKey)
            assertEquals(8, saved.sourcePresetVersion)
            assertEquals("fake-preset-key", saved.apiKey)
            assertEquals(ParamValue.NumberValue(0.75), saved.customParams["temperature"])
            assertEquals(ParamValue.BooleanValue(true), saved.customParams["enabled"])
            assertEquals(ParamValue.StringValue("value"), saved.customParams["label"])

            controller.editModel { it.copy(customParams = it.customParams + DesktopParameterDraft(" ", DesktopParameterKind.STRING, "x")) }
            controller.saveModel()
            assertEquals("Custom parameter name is required", controller.state.value.error)
            assertEquals(saved, fixture.container.modelRepository.getModel(source.id))
        }
    }

    @Test
    fun `settings draft retains untouched concurrent fields and secure fallback key`() = runBlocking {
        withFixture { fixture ->
            val controller = fixture.controller
            controller.loadSettings()
            controller.replaceFallbackCredential("fake-global-first")
            controller.editSettings { it.copy(defaultContextWindowSize = "32") }
            controller.saveAppSettings()
            assertEquals("fake-global-first", fixture.secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
            assertSanitizedSettings(fixture.root, listOf("fake-global-first"))

            fixture.container.settingsRepository.updateAppSettings {
                it.copy(lastSeenChatAt = 99L, siliconFlowApiKey = "fake-global-concurrent")
            }
            controller.editSettings { it.copy(defaultContextWindowSize = "48", playerName = "Player", playerPersona = "Persona") }
            controller.saveAppSettings()
            val merged = fixture.container.settingsRepository.getAppSettings()
            assertEquals(48, merged.defaultContextWindowSize)
            assertEquals(99L, merged.lastSeenChatAt)
            assertEquals("fake-global-concurrent", merged.siliconFlowApiKey)
            assertEquals("fake-global-concurrent", fixture.secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
            assertSanitizedSettings(fixture.root, listOf("fake-global-first", "fake-global-concurrent"))

            controller.savePlayerSetting()
            assertEquals("Player", fixture.container.settingsRepository.getPlayerSetting().playerName)
            assertEquals("Persona", fixture.container.settingsRepository.getPlayerSetting().globalPersona)

            controller.replaceFallbackCredential("fake-global-new")
            controller.saveAppSettings()
            assertEquals("fake-global-new", fixture.secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
            controller.replaceFallbackCredential("")
            controller.saveAppSettings()
            assertEquals("Enter a key or choose Clear", controller.state.value.error)
            assertEquals("fake-global-new", fixture.secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
            controller.clearFallbackCredential()
            controller.saveAppSettings()
            assertNull(fixture.secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
            assertEquals("", fixture.container.settingsRepository.getAppSettings().siliconFlowApiKey)
            assertSanitizedSettings(fixture.root, listOf("fake-global-first", "fake-global-concurrent", "fake-global-new"))
        }
    }

    @Test
    fun `controller action configures local blank-key chat model for existing Alpha resolver`() = runBlocking {
        withFixture { fixture ->
            val controller = fixture.controller
            controller.startCreate()
            controller.applyTemplate(com.example.chatbar.data.local.entity.ModelTemplate.CUSTOM)
            controller.editModel { it.copy(displayName = "Local", baseUrl = "http://127.0.0.1:12345/v1", modelName = "local-chat") }
            controller.saveModel()
            val id = controller.state.value.models.single().id
            assertEquals("", fixture.container.modelRepository.getModel(id)?.apiKey)

            controller.loadSettings()
            controller.editSettings { it.copy(defaultModelId = id, allowCleartextModelApi = true) }
            controller.saveAppSettings()
            val character = CharacterCard.create("Local character", greeting = "Greeting")
            fixture.container.characterRepository.save(character)
            fixture.container.characterSessionService.createSessionForCharacter(character.id)

            fixture.container.alphaChatController.refresh()
            assertTrue(fixture.container.alphaChatController.state.value.modelUsable)
            assertEquals(id, fixture.container.settingsRepository.getAppSettings().defaultModelId)
            assertEquals("", fixture.container.effectiveModelResolver.defaultChatModel()?.apiKey)
            assertTrue(fixture.container.taskRuntime.tasks.value.isEmpty())
        }
    }

    @Test
    fun `deleting selected model leaves stale default visible without hidden settings mutation`() = runBlocking {
        withFixture { fixture ->
            val model = ModelConfig(
                id = "selected", displayName = "Selected", baseUrl = "https://example.invalid/v1",
                apiKey = "fake-selected-key", modelName = "chat", createdAt = 1L,
            )
            fixture.container.modelRepository.saveModel(model)
            fixture.container.settingsRepository.saveAppSettings(AppSettings(defaultModelId = model.id))
            fixture.controller.loadModels()
            fixture.controller.deleteModel(model.id)

            assertEquals(model.id, fixture.container.settingsRepository.getAppSettings().defaultModelId)
            assertNull(fixture.secrets.values[DesktopCredentialKey.ModelApiKey(model.id)])
            fixture.controller.loadSettings()
            assertEquals(model.id, fixture.controller.state.value.settings?.defaultModelId)
            assertTrue(fixture.controller.state.value.availableChatModels.none { it.id == model.id })
        }
    }

    @Test
    fun `new controller and default Alpha refresh create no business files`() = runBlocking {
        withFixture { fixture ->
            fixture.controller
            assertFalse(Files.exists(fixture.root))
            fixture.container.alphaChatController.refresh()
            val files = Files.walk(fixture.root).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }
            assertTrue(files.isEmpty(), "Unexpected startup writes: $files")
        }
    }

    @Test
    fun `discovery uses effective key and cleartext blank key sends no authorization`() = runBlocking {
        withFixture { fixture ->
            MockWebServer().use { server ->
                fixture.container.settingsRepository.saveAppSettings(
                    AppSettings(allowCleartextModelApi = true, siliconFlowApiKey = "fake-global-fallback"),
                )
                val controller = fixture.controller
                controller.startCreate()
                controller.editModel { it.copy(baseUrl = server.url("/v1").toString().trimEnd('/')) }
                controller.replaceModelCredential("fake-local-key")
                server.enqueue(MockResponse().setBody("""{"data":[{"id":"second"},{"id":"first"}]}"""))
                controller.discoverModels()
                assertEquals("Bearer fake-local-key", server.takeRequest().getHeader("Authorization"))
                assertEquals(listOf("first", "second"), controller.state.value.discoveredModelIds)
                controller.selectDiscoveredModel("second")
                assertEquals("second", controller.state.value.editor?.modelName)
                assertEquals("", controller.state.value.editor?.displayName)

                controller.clearModelCredential()
                server.enqueue(MockResponse().setBody("""{"data":[{"id":"blank-key"}]}"""))
                controller.discoverModels()
                assertNull(server.takeRequest().getHeader("Authorization"))
                assertEquals(listOf("blank-key"), controller.state.value.discoveredModelIds)
            }
        }
    }

    @Test
    fun `discovery failure leaves manual ID editable and URL change cancels stale result`() = runBlocking {
        withFixture { fixture ->
            MockWebServer().use { server ->
                fixture.container.settingsRepository.saveAppSettings(AppSettings(allowCleartextModelApi = true))
                val controller = fixture.controller
                controller.startCreate()
                controller.editModel { it.copy(baseUrl = server.url("/v1").toString().trimEnd('/')) }
                server.enqueue(MockResponse().setResponseCode(404))
                controller.discoverModels()
                assertNotNull(controller.state.value.discoveryError)
                controller.editModel { it.copy(modelName = "manually-entered") }
                assertEquals("manually-entered", controller.state.value.editor?.modelName)

                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val job = launch(Dispatchers.IO) { controller.discoverModels() }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                controller.editModel { it.copy(baseUrl = "https://changed.invalid/v1") }
                job.cancelAndJoin()
                assertTrue(controller.state.value.discoveredModelIds.isEmpty())
                assertFalse(controller.state.value.discovering)
                assertEquals("manually-entered", controller.state.value.editor?.modelName)
            }
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val parent = Files.createTempDirectory("desktop-model-settings-ui-")
        val root = parent.resolve("app-data")
        val secrets = InMemoryDesktopSecretStore()
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
            secretStoreFactory = { secrets },
        )
        try {
            block(Fixture(root, secrets, container, container.modelSettingsController))
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private data class Fixture(
        val root: Path,
        val secrets: InMemoryDesktopSecretStore,
        val container: DesktopAppContainer,
        val controller: DesktopModelSettingsController,
    )

    private fun assertSanitizedModel(root: Path, id: String, forbidden: List<String>) {
        val model = requireNotNull(root.resolve("entities/model_configs").toFile().listFiles())
            .single { it.extension == "json" && it.readText().contains("\"id\": \"$id\"") }
            .readText()
        forbidden.forEach { assertFalse(model.contains(it)) }
        assertTrue(model.contains("\"apiKey\": \"\""))
    }

    private fun assertSanitizedSettings(root: Path, forbidden: List<String>) {
        val raw = root.resolve("entities/app_settings.json").readText()
        forbidden.forEach { assertFalse(raw.contains(it)) }
        assertTrue(raw.contains("\"siliconFlowApiKey\": \"\""))
    }
}
