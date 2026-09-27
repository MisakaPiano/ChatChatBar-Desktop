package com.example.chatbar.desktop.security

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.PresetModelCatalog
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class DesktopSecureModelRepositoryTest {
    private lateinit var parent: Path
    private lateinit var appDataRoot: Path
    private lateinit var secretRoot: Path
    private lateinit var secretStore: WindowsSecretStore

    @BeforeTest
    fun setUp() {
        parent = Files.createTempDirectory("desktop-secure-model-")
        appDataRoot = parent.resolve("app-data")
        secretRoot = parent.resolve("secrets")
        secretStore = WindowsSecretStore(secretRoot, ReversibleTestSecretProtector())
    }

    @AfterTest
    fun tearDown() {
        parent.toFile().deleteRecursively()
    }

    @Test
    fun `model lifecycle keeps runtime cache and restart hydrated while both filename namespaces stay separate`() = runTest {
        val repository = secureRepository(appDataRoot, secretStore)
        val source = model("preset:vision", "Vision", "fake-test-key")
        repository.saveModel(source)

        assertEquals("fake-test-key", repository.getModel(source.id)?.apiKey)
        assertEquals("fake-test-key", repository.getAllModels().single().apiKey)
        assertEquals("fake-test-key", repository.models.first().single().apiKey)
        assertSanitizedModelJson(appDataRoot, source.id, listOf("fake-test-key"))

        val entityPath = modelPath(appDataRoot, source.id)
        val credentialPath = secretStore.secretPath(DesktopCredentialKey.ModelApiKey(source.id))
        assertEquals(WindowsSafeModelStorageKeyPolicy.storageKey(source.id) + ".json", entityPath.fileName.toString())
        assertTrue(credentialPath.fileName.toString().matches(Regex("[0-9a-f]{64}\\.ccbsecret")))
        assertNotEquals(entityPath.fileName.toString(), credentialPath.fileName.toString())
        assertFalse(credentialPath.fileName.toString().contains(source.id))
        assertFalse(secretRoot.startsWith(appDataRoot))
        assertFalse(appDataRoot.startsWith(secretRoot))

        val restarted = secureRepository(appDataRoot, secretStore)
        assertEquals("fake-test-key", restarted.getModel(source.id)?.apiKey)

        val otherRoot = parent.resolve("other-app-data")
        val otherPath = modelPath(otherRoot, source.id)
        Files.createDirectories(otherPath.parent)
        Files.copy(entityPath, otherPath, StandardCopyOption.REPLACE_EXISTING)
        assertEquals("fake-test-key", secureRepository(otherRoot, secretStore).getModel(source.id)?.apiKey)
        assertSanitizedModelJson(otherRoot, source.id, listOf("fake-test-key"))

        restarted.saveModel(source.copy(apiKey = "fake-updated-key"))
        assertEquals(
            "fake-updated-key",
            secretStore.load(DesktopCredentialKey.ModelApiKey(source.id)),
        )

        val duplicate = restarted.duplicateModel(source.id)
        assertNotEquals(source.id, duplicate.id)
        assertEquals("fake-updated-key", duplicate.apiKey)
        assertEquals(
            "fake-updated-key",
            secretStore.load(DesktopCredentialKey.ModelApiKey(duplicate.id)),
        )
        assertSanitizedModelJson(appDataRoot, duplicate.id, listOf("fake-updated-key"))

        restarted.saveModel(source.copy(apiKey = ""))
        assertEquals("", restarted.getModel(source.id)?.apiKey)
        assertNull(secretStore.load(DesktopCredentialKey.ModelApiKey(source.id)))
        assertEquals("", secureRepository(appDataRoot, secretStore).getModel(source.id)?.apiKey)

        restarted.deleteModel(duplicate.id)
        assertNull(restarted.getModel(duplicate.id))
        assertNull(secretStore.load(DesktopCredentialKey.ModelApiKey(duplicate.id)))
    }

    @Test
    fun `preset ensure and restore preserve secure credential for authoritative logical model ID`() = runTest {
        val repository = secureRepository(appDataRoot, secretStore)
        val existing = model("custom-preset-id", "Edited", "fake-preset-key").copy(
            sourcePresetKey = "chat",
            sourcePresetVersion = 1,
            createdAt = 7L,
        )
        repository.saveModel(existing)
        val catalog = PresetModelCatalog(
            baseUrl = "https://preset.example/v1",
            chatModels = listOf(
                PresetChatModel(
                    modelKey = "chat",
                    displayName = "Preset Chat",
                    modelName = "provider/chat",
                    templateType = ModelTemplate.CUSTOM,
                ),
            ),
        )

        assertEquals("fake-preset-key", repository.ensurePresetChatModels(catalog, 2).single().apiKey)
        val restored = repository.restorePresetChatModels(catalog, 3).single()

        assertEquals(existing.id, restored.id)
        assertEquals("fake-preset-key", restored.apiKey)
        assertEquals(3, restored.sourcePresetVersion)
        assertEquals(
            "fake-preset-key",
            secretStore.load(DesktopCredentialKey.ModelApiKey(existing.id)),
        )
        assertSanitizedModelJson(appDataRoot, existing.id, listOf("fake-preset-key"))
    }

    @Test
    fun `unexpected plaintext model credential fails closed without rewriting entity`() = runTest {
        val plaintext = model("preset:vision", "Vision", "fake-plaintext-key")
        ModelRepository(
            JsonFileStorage(appDataRoot),
            WindowsSafeModelStorageKeyPolicy,
        ).saveModel(plaintext)
        val path = modelPath(appDataRoot, plaintext.id)
        val before = Files.readAllBytes(path)

        assertFailsWith<UnsafeDesktopPlaintextCredentialException> {
            secureRepository(appDataRoot, secretStore).getAllModels()
        }

        assertTrue(before.contentEquals(Files.readAllBytes(path)))
        assertNull(secretStore.load(DesktopCredentialKey.ModelApiKey(plaintext.id)))
    }

    @Test
    fun `model SecretStore failure remains explicit instead of becoming blank`() = runTest {
        val stored = model("model-a", "Model A", "")
        ModelRepository(
            JsonFileStorage(appDataRoot),
            WindowsSafeModelStorageKeyPolicy,
        ).saveModel(stored)
        val failingSecrets = InMemoryDesktopSecretStore().apply { failNextLoad() }

        val error = assertFailsWith<DesktopSecretStoreException> {
            secureRepository(appDataRoot, failingSecrets).getModel(stored.id)
        }

        assertEquals(DesktopSecretStoreFailureKind.STORAGE_FAILURE, error.kind)
    }

    @Test
    fun `failed model entity save and delete restore prior secure credential`() = runTest {
        val secrets = InMemoryDesktopSecretStore()
        val key = DesktopCredentialKey.ModelApiKey("model-a")
        secrets.values[key] = "fake-existing-key"
        val policy = DesktopModelCredentialPersistencePolicy(secrets)

        assertFailsWith<IOException> {
            policy.persist(model("model-a", "Model A", "fake-replacement-key")) {
                throw IOException("Injected entity write failure")
            }
        }
        assertEquals("fake-existing-key", secrets.values[key])

        assertFailsWith<IOException> {
            policy.delete("model-a") {
                throw IOException("Injected entity delete failure")
            }
        }
        assertEquals("fake-existing-key", secrets.values[key])
    }

    private fun secureRepository(dataRoot: Path, secrets: DesktopSecretStore) = ModelRepository(
        storage = JsonFileStorage(dataRoot),
        modelStorageKeyPolicy = WindowsSafeModelStorageKeyPolicy,
        credentialPersistencePolicy = DesktopModelCredentialPersistencePolicy(secrets),
    )

    private fun model(id: String, displayName: String, apiKey: String) = ModelConfig(
        id = id,
        displayName = displayName,
        baseUrl = "https://example.test/v1",
        apiKey = apiKey,
        modelName = "provider/model",
        createdAt = 1L,
    )

    private fun assertSanitizedModelJson(dataRoot: Path, id: String, forbidden: List<String>) {
        val raw = modelPath(dataRoot, id).readText()
        forbidden.forEach { secret -> assertFalse(raw.contains(secret)) }
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(ModelConfig.serializer(), raw)
        assertEquals("", decoded.apiKey)
    }

    private fun modelPath(dataRoot: Path, id: String): Path = dataRoot.resolve(
        "entities/model_configs/${WindowsSafeModelStorageKeyPolicy.storageKey(id)}.json",
    )
}
