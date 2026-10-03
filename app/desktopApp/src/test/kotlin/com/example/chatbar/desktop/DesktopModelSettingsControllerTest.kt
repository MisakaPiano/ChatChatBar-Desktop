package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.domain.model.ModelDiscoveryService
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
import kotlinx.serialization.json.Json

class DesktopModelSettingsControllerTest {
    @Test
    fun `management avatar decodes through existing controller resource authority`() = runBlocking {
        withFixture { fixture ->
            Files.createDirectories(fixture.root)
            val image = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val bytes = java.io.ByteArrayOutputStream().use {
                javax.imageio.ImageIO.write(image, "png", it)
                it.toByteArray()
            }
            val store = fixture.container.characterResourceStore
            val reference = store.materializeImage(com.example.chatbar.domain.card.PackagedImage(
                "avatar.png", java.util.Base64.getEncoder().encodeToString(bytes)), 1L, "avatar")
            val card = CharacterCard(id = "avatar-card", name = "Avatar", avatar = reference, createdAt = 1, updatedAt = 1)
            val controller = fixture.container.characterEditorController
            assertNotNull(desktopCharacterManagementPresentation(card, controller::imageBytes).avatar)
            store.deleteOwned(reference)
            val missing = desktopCharacterManagementPresentation(card, controller::imageBytes)
            assertNull(missing.avatar)
            assertEquals("A", missing.fallbackInitial)
            assertEquals(reference, card.avatar)
        }
    }

    @Test
    fun `format management uses settings authority and synchronizes both default repositories`() = runBlocking {
        withFixture { fixture ->
            val formats = fixture.container.formatCardRepository
            formats.save(FormatCard("old", "Old flag", "inline", isDefault = true, createdAt = 1))
            formats.save(FormatCard("selected", "Settings authority", "inline", createdAt = 2))
            fixture.container.settingsRepository.saveAppSettings(AppSettings(defaultFormatCardId = "selected"))
            val controller = fixture.controller
            controller.loadSettings()
            controller.loadFormatManagement()
            assertEquals("selected", controller.state.value.globalDefaultFormatCardId)
            assertTrue(formats.getById("old")!!.isDefault)
            assertFalse(formats.getById("selected")!!.isDefault)
            assertTrue(controller.setDefaultFormatCard("selected"))
            assertFalse(formats.getById("old")!!.isDefault)
            assertTrue(formats.getById("selected")!!.isDefault)
            assertEquals("selected", fixture.container.settingsRepository.getAppSettings().defaultFormatCardId)
            assertEquals("selected", controller.state.value.settings?.defaultFormatCardId)
            assertTrue(controller.setDefaultFormatCard("old"))
            assertEquals("old", controller.state.value.globalDefaultFormatCardId)
            assertEquals("old", controller.state.value.settings?.defaultFormatCardId)
            assertFalse(formats.getById("selected")!!.isDefault)
            // Raw editor flag is independent of runtime fallback selection.
            formats.save(formats.getById("old")!!.copy(isDefault = false))
            controller.loadFormatManagement()
            assertEquals("old", controller.state.value.globalDefaultFormatCardId)
            assertEquals("old", fixture.container.settingsRepository.getAppSettings().defaultFormatCardId)
        }
    }

    @Test
    fun `format management refuses unsaved chat defaults and missing cards without mutation`() = runBlocking {
        withFixture { fixture ->
            val formats = fixture.container.formatCardRepository
            formats.save(FormatCard("old", "Old", "inline", isDefault = true, createdAt = 1))
            formats.save(FormatCard("new", "New", "inline", createdAt = 2))
            fixture.container.settingsRepository.saveAppSettings(AppSettings(defaultFormatCardId = "old"))
            val controller = fixture.controller
            controller.loadSettings()
            controller.editSettings { it.copy(defaultContextWindowSize = "37", defaultFormatCardId = "new") }
            val draft = controller.state.value.settings
            controller.loadFormatManagement()
            assertEquals(draft, controller.state.value.settings)
            assertEquals("old", controller.state.value.globalDefaultFormatCardId)
            assertFalse(controller.setDefaultFormatCard("new"))
            assertNotNull(controller.state.value.error)
            assertEquals(draft, controller.state.value.settings)
            assertEquals("old", fixture.container.settingsRepository.getAppSettings().defaultFormatCardId)
            assertTrue(formats.getById("old")!!.isDefault)
            assertFalse(formats.getById("new")!!.isDefault)
            controller.discardChatDefaults()
            assertFalse(controller.setDefaultFormatCard("missing"))
            assertTrue(formats.getById("old")!!.isDefault)
            controller.editSettings { it.copy(playerName = "Unsaved player") }
            assertTrue(controller.setDefaultFormatCard("new"))
            assertTrue(controller.state.value.playerDirty)
            assertEquals("Unsaved player", controller.state.value.settings?.playerName)
            assertEquals("new", controller.state.value.settings?.defaultFormatCardId)
        }
    }

    @Test
    fun `immediate credential save is independent from chat defaults and player drafts`() = runBlocking {
        withFixture { fixture ->
            val controller = fixture.controller
            controller.loadSettings()
            val originalContext = fixture.container.settingsRepository.getAppSettings().defaultContextWindowSize
            controller.editSettings { it.copy(defaultContextWindowSize = "31", playerName = "Draft player") }
            assertTrue(controller.state.value.chatDefaultsDirty)
            assertTrue(controller.state.value.playerDirty)
            controller.openCredentialEditor()
            controller.editCredentialDraft("fake-independent-key")
            assertTrue(controller.state.value.credentialDirty)
            controller.saveCredentialDraft()
            assertFalse(controller.state.value.credentialEditorOpen)
            assertEquals("fake-independent-key", fixture.container.settingsRepository.getAppSettings().siliconFlowApiKey)
            assertEquals(originalContext, fixture.container.settingsRepository.getAppSettings().defaultContextWindowSize)
            assertTrue(controller.state.value.chatDefaultsDirty)
            assertTrue(controller.state.value.playerDirty)
            controller.discardChatDefaults()
            controller.discardPlayerSetting()
            assertFalse(controller.state.value.chatDefaultsDirty)
            assertFalse(controller.state.value.playerDirty)
            assertEquals("fake-independent-key", fixture.secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
            controller.clearFallbackCredentialImmediately()
            assertNull(fixture.secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
        }
    }

    @Test
    fun `bundled model page is read-only until explicit restore and restore preserves secure credentials`() = runBlocking {
        withFixture { fixture ->
            val controller = inlinePresetController(fixture)
            controller.loadModels()
            val listed = assertNotNull(controller.state.value.bundledCatalog)
            assertEquals("INLINE_PROVIDER", listed.provider)
            assertEquals("https://inline.invalid/v1", listed.baseUrl)
            assertEquals(7, listed.version)
            assertEquals(listOf("alpha", "beta"), listed.chatModels.map { it.key })
            assertTrue(controller.state.value.models.isEmpty())
            assertTrue(Files.list(fixture.root.resolve("entities/model_configs")).use { it.toList() }.isEmpty())

            fixture.container.modelRepository.saveModel(ModelConfig(
                id = "preset:alpha", displayName = "old", baseUrl = "https://inline.invalid/v1",
                modelName = "old-model", apiKey = "fake-preset-secret", createdAt = 1L,
            ))
            fixture.container.settingsRepository.saveAppSettings(AppSettings(presetDefaultModelKey = "alpha"))
            controller.restoreBundledModels()
            assertNull(controller.state.value.error)
            assertEquals(2, controller.state.value.models.size)
            val alpha = assertNotNull(fixture.container.modelRepository.getModel("preset:alpha"))
            assertEquals("Alpha bundled", alpha.displayName)
            assertEquals("alpha-model", alpha.modelName)
            assertEquals("alpha", alpha.sourcePresetKey)
            assertEquals(7, alpha.sourcePresetVersion)
            assertEquals("fake-preset-secret", alpha.apiKey)
            assertSanitizedModel(fixture.root, alpha.id, listOf("fake-preset-secret"))
            val embedding = assertNotNull(fixture.container.modelRepository.getEmbeddingModel())
            assertEquals("embed-model", embedding.modelName)
            val settings = fixture.container.settingsRepository.getAppSettings()
            assertEquals("preset:alpha", settings.defaultModelId)
            assertNull(settings.presetDefaultModelKey)
            assertTrue(controller.state.value.availableChatModels.any { it.id == "preset:alpha" })
            controller.restoreBundledModels()
            assertEquals(2, fixture.container.modelRepository.getAllModels().size)
            assertEquals("fake-preset-secret", fixture.container.modelRepository.getModel("preset:alpha")?.apiKey)
            controller.loadSettings()
            assertTrue(controller.state.value.availableChatModels.any { it.id == "preset:alpha" })
        }
    }

    @Test
    fun `vision binding persists only for non-multimodal models and does not return after clearing`() = runBlocking {
        withFixture { fixture ->
            val controller = fixture.controller
            controller.startCreate()
            controller.applyTemplate(com.example.chatbar.data.local.entity.ModelTemplate.CUSTOM)
            controller.editModel {
                it.copy(
                    displayName = "Text model",
                    baseUrl = "https://example.invalid/v1",
                    modelName = "text-chat",
                    visionModelId = "  vision-model  ",
                )
            }
            controller.saveModel()
            val id = controller.state.value.models.single().id
            assertEquals("vision-model", fixture.container.modelRepository.getModel(id)?.visionModelId)

            controller.editModel { it.copy(isMultimodal = true) }
            assertEquals("", controller.state.value.editor?.visionModelId)
            controller.saveModel()
            assertTrue(fixture.container.modelRepository.getModel(id)?.isMultimodal == true)
            assertNull(fixture.container.modelRepository.getModel(id)?.visionModelId)

            controller.editModel { it.copy(isMultimodal = false) }
            controller.saveModel()
            assertFalse(fixture.container.modelRepository.getModel(id)?.isMultimodal == true)
            assertNull(fixture.container.modelRepository.getModel(id)?.visionModelId)

            controller.editModel { it.copy(visionModelId = "new-vision-model") }
            controller.saveModel()
            assertEquals("new-vision-model", fixture.container.modelRepository.getModel(id)?.visionModelId)
        }
    }

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

    private fun inlinePresetController(fixture: Fixture): DesktopModelSettingsController {
        val assets = mapOf(
            "presets/manifest.json" to """{"entries":[{"type":"MODEL_CATALOG","version":7,"file":"presets/models/default-models.json"}]}""",
            "presets/models/default-models.json" to """{"schemaVersion":2,"provider":"INLINE_PROVIDER","baseUrl":"https://inline.invalid/v1","chatModels":[{"modelKey":"alpha","displayName":"Alpha bundled","modelName":"alpha-model"},{"modelKey":"beta","displayName":"Beta bundled","modelName":"beta-model"}],"embeddingModel":{"modelKey":"embed","displayName":"Embed bundled","modelName":"embed-model","dimensions":16}}""",
        )
        return DesktopModelSettingsController(
            models = fixture.container.modelRepository,
            settings = fixture.container.settingsRepository,
            formats = fixture.container.formatCardRepository,
            resolver = fixture.container.effectiveModelResolver,
            discovery = ModelDiscoveryService(),
            presets = DesktopPresetModelCatalogSource({ path -> assets.getValue(path).toByteArray() },
                Json { ignoreUnknownKeys = true }),
        )
    }

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
