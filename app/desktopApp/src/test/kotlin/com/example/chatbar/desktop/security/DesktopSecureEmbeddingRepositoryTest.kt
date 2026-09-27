package com.example.chatbar.desktop.security

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.repository.EmbeddingCredentialScope
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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

class DesktopSecureEmbeddingRepositoryTest {
    private lateinit var parent: Path
    private lateinit var appDataRoot: Path
    private lateinit var secretRoot: Path
    private lateinit var secretStore: WindowsSecretStore

    @BeforeTest
    fun setUp() {
        parent = Files.createTempDirectory("desktop-secure-embedding-")
        appDataRoot = parent.resolve("app-data")
        secretRoot = parent.resolve("secrets")
        secretStore = WindowsSecretStore(secretRoot, ReversibleTestSecretProtector())
    }

    @AfterTest
    fun tearDown() {
        parent.toFile().deleteRecursively()
    }

    @Test
    fun `legacy embedding save restart clear and delete keep JSON sanitized and runtime hydrated`() = runTest {
        val repository = secureRepository(appDataRoot, secretStore)
        val embedding = embedding("legacy-a", "Legacy", "fake-legacy-key")
        val key = DesktopCredentialKey.LegacyEmbeddingApiKey(embedding.id)

        repository.saveEmbedding(embedding)

        assertEquals("fake-legacy-key", repository.getEmbedding(embedding.id)?.apiKey)
        assertEquals("fake-legacy-key", repository.getAllEmbeddings().single().apiKey)
        assertEquals("fake-legacy-key", repository.embeddings.first().single().apiKey)
        assertEquals("fake-legacy-key", secretStore.load(key))
        assertSanitizedEmbeddingJson(legacyPath(appDataRoot, embedding.id), listOf("fake-legacy-key"))
        assertEquals("fake-legacy-key", secureRepository(appDataRoot, secretStore).getEmbedding(embedding.id)?.apiKey)

        repository.saveEmbedding(embedding.copy(apiKey = ""))
        assertNull(secretStore.load(key))
        assertEquals("", secureRepository(appDataRoot, secretStore).getEmbedding(embedding.id)?.apiKey)

        repository.saveEmbedding(embedding.copy(apiKey = "fake-delete-key"))
        repository.deleteEmbedding(embedding.id)
        assertNull(repository.getEmbedding(embedding.id))
        assertNull(secretStore.load(key))
        assertFalse(Files.exists(legacyPath(appDataRoot, embedding.id)))
    }

    @Test
    fun `singleton embedding save restart clear and delete keep JSON sanitized and runtime hydrated`() = runTest {
        val repository = secureRepository(appDataRoot, secretStore)
        val singleton = embedding("source-id", "Singleton", "fake-singleton-key")
        val key = DesktopCredentialKey.SingletonEmbeddingApiKey

        repository.saveEmbeddingModel(singleton)

        val runtime = requireNotNull(repository.getEmbeddingModel())
        assertEquals("default", runtime.id)
        assertEquals("fake-singleton-key", runtime.apiKey)
        assertEquals("fake-singleton-key", repository.embeddingModel.first()?.apiKey)
        assertEquals("fake-singleton-key", secretStore.load(key))
        assertSanitizedEmbeddingJson(singletonPath(appDataRoot), listOf("fake-singleton-key"))
        assertEquals(
            "fake-singleton-key",
            secureRepository(appDataRoot, secretStore).getEmbeddingModel()?.apiKey,
        )

        repository.saveEmbeddingModel(singleton.copy(apiKey = ""))
        assertNull(secretStore.load(key))
        assertEquals("", secureRepository(appDataRoot, secretStore).getEmbeddingModel()?.apiKey)

        repository.saveEmbeddingModel(singleton.copy(apiKey = "fake-delete-key"))
        repository.deleteEmbeddingModel()
        assertNull(repository.getEmbeddingModel())
        assertNull(secretStore.load(key))
        assertFalse(Files.exists(singletonPath(appDataRoot)))
    }

    @Test
    fun `legacy default and singleton credential namespaces do not collide`() = runTest {
        val repository = secureRepository(appDataRoot, secretStore)
        val legacy = embedding("default", "Legacy Default", "fake-legacy-key")
        val singleton = embedding("another-id", "Singleton", "fake-singleton-key")
        val legacyKey = DesktopCredentialKey.LegacyEmbeddingApiKey("default")
        val singletonKey = DesktopCredentialKey.SingletonEmbeddingApiKey

        repository.saveEmbedding(legacy)
        repository.saveEmbeddingModel(singleton)

        assertEquals("fake-legacy-key", repository.getEmbedding("default")?.apiKey)
        assertEquals("fake-singleton-key", repository.getEmbeddingModel()?.apiKey)
        assertEquals("fake-legacy-key", secretStore.load(legacyKey))
        assertEquals("fake-singleton-key", secretStore.load(singletonKey))
        val legacySecretPath = secretStore.secretPath(legacyKey)
        val singletonSecretPath = secretStore.secretPath(singletonKey)
        assertNotEquals(legacySecretPath, singletonSecretPath)
        listOf(legacySecretPath, singletonSecretPath).forEach { path ->
            assertTrue(path.fileName.toString().matches(Regex("[0-9a-f]{64}\\.ccbsecret")))
            assertFalse(path.fileName.toString().contains("default"))
        }
    }

    @Test
    fun `migration transfers selected legacy credential to singleton and removes legacy state`() = runTest {
        val repository = secureRepository(appDataRoot, secretStore)
        val first = embedding("first", "First", "fake-first-key")
        val preferred = embedding("preferred", "Preferred", "fake-preferred-key")
        repository.saveEmbedding(first)
        repository.saveEmbedding(preferred)

        repository.migrateEmbeddingsToSingleton(preferred.id)

        val singleton = requireNotNull(repository.getEmbeddingModel())
        assertEquals("default", singleton.id)
        assertEquals("fake-preferred-key", singleton.apiKey)
        assertTrue(repository.getAllEmbeddings().isEmpty())
        assertEquals("fake-preferred-key", secretStore.load(DesktopCredentialKey.SingletonEmbeddingApiKey))
        assertNull(secretStore.load(DesktopCredentialKey.LegacyEmbeddingApiKey(first.id)))
        assertNull(secretStore.load(DesktopCredentialKey.LegacyEmbeddingApiKey(preferred.id)))
        assertFalse(Files.exists(legacyPath(appDataRoot, first.id)))
        assertFalse(Files.exists(legacyPath(appDataRoot, preferred.id)))
        assertSanitizedEmbeddingJson(
            singletonPath(appDataRoot),
            listOf("fake-first-key", "fake-preferred-key"),
        )
    }

    @Test
    fun `unexpected plaintext legacy and singleton embedding JSON fail closed without rewrite`() = runTest {
        val legacyRoot = parent.resolve("plaintext-legacy")
        val singletonRoot = parent.resolve("plaintext-singleton")
        val plaintextLegacy = embedding("legacy", "Legacy", "fake-legacy-plaintext")
        val plaintextSingleton = embedding("singleton", "Singleton", "fake-singleton-plaintext")
        identityRepository(legacyRoot).saveEmbedding(plaintextLegacy)
        identityRepository(singletonRoot).saveEmbeddingModel(plaintextSingleton)
        val legacyPath = legacyPath(legacyRoot, plaintextLegacy.id)
        val singletonPath = singletonPath(singletonRoot)
        val legacyBefore = Files.readAllBytes(legacyPath)
        val singletonBefore = Files.readAllBytes(singletonPath)

        assertFailsWith<UnsafeDesktopPlaintextCredentialException> {
            secureRepository(legacyRoot, secretStore).getEmbedding(plaintextLegacy.id)
        }
        assertFailsWith<UnsafeDesktopPlaintextCredentialException> {
            secureRepository(singletonRoot, secretStore).getEmbeddingModel()
        }

        assertTrue(legacyBefore.contentEquals(Files.readAllBytes(legacyPath)))
        assertTrue(singletonBefore.contentEquals(Files.readAllBytes(singletonPath)))
        assertNull(secretStore.load(DesktopCredentialKey.LegacyEmbeddingApiKey(plaintextLegacy.id)))
        assertNull(secretStore.load(DesktopCredentialKey.SingletonEmbeddingApiKey))
    }

    @Test
    fun `embedding SecretStore failures remain explicit for legacy and singleton`() = runTest {
        val legacyRoot = parent.resolve("failure-legacy")
        val singletonRoot = parent.resolve("failure-singleton")
        val blankLegacy = embedding("legacy", "Legacy", "")
        val blankSingleton = embedding("singleton", "Singleton", "")
        identityRepository(legacyRoot).saveEmbedding(blankLegacy)
        identityRepository(singletonRoot).saveEmbeddingModel(blankSingleton)

        val legacyFailure = InMemoryDesktopSecretStore().apply { failNextLoad() }
        val singletonFailure = InMemoryDesktopSecretStore().apply { failNextLoad() }
        val legacyError = assertFailsWith<DesktopSecretStoreException> {
            secureRepository(legacyRoot, legacyFailure).getEmbedding(blankLegacy.id)
        }
        val singletonError = assertFailsWith<DesktopSecretStoreException> {
            secureRepository(singletonRoot, singletonFailure).getEmbeddingModel()
        }

        assertEquals(DesktopSecretStoreFailureKind.STORAGE_FAILURE, legacyError.kind)
        assertEquals(DesktopSecretStoreFailureKind.STORAGE_FAILURE, singletonError.kind)
    }

    @Test
    fun `embedding entity save and delete failures restore prior secure credentials`() = runTest {
        val secrets = InMemoryDesktopSecretStore()
        val legacyKey = DesktopCredentialKey.LegacyEmbeddingApiKey("legacy")
        val singletonKey = DesktopCredentialKey.SingletonEmbeddingApiKey
        secrets.values[legacyKey] = "fake-existing-legacy-key"
        secrets.values[singletonKey] = "fake-existing-singleton-key"
        val policy = DesktopEmbeddingCredentialPersistencePolicy(secrets)

        assertFailsWith<IOException> {
            policy.persist(
                EmbeddingCredentialScope.Legacy("legacy"),
                embedding("legacy", "Legacy", "fake-replacement-key"),
            ) {
                throw IOException("Injected entity write failure")
            }
        }
        assertEquals("fake-existing-legacy-key", secrets.values[legacyKey])

        assertFailsWith<IOException> {
            policy.delete(EmbeddingCredentialScope.Singleton) {
                throw IOException("Injected entity delete failure")
            }
        }
        assertEquals("fake-existing-singleton-key", secrets.values[singletonKey])
    }

    @Test
    fun `embedding rollback failure surfaces compound consistency error`() = runTest {
        val secrets = InMemoryDesktopSecretStore()
        val key = DesktopCredentialKey.LegacyEmbeddingApiKey("legacy")
        secrets.values[key] = "fake-existing-key"
        secrets.failSave(call = 2)
        val policy = DesktopEmbeddingCredentialPersistencePolicy(secrets)

        val failure = assertFailsWith<DesktopCredentialConsistencyException> {
            policy.persist(
                EmbeddingCredentialScope.Legacy("legacy"),
                embedding("legacy", "Legacy", "fake-replacement-key"),
            ) {
                throw IOException("Injected entity write failure")
            }
        }

        assertTrue(failure.cause is IOException)
        assertTrue(failure.suppressed.single() is DesktopSecretStoreException)
    }

    private fun secureRepository(dataRoot: Path, secrets: DesktopSecretStore) = ModelRepository(
        storage = JsonFileStorage(dataRoot),
        modelStorageKeyPolicy = WindowsSafeModelStorageKeyPolicy,
        embeddingCredentialPersistencePolicy = DesktopEmbeddingCredentialPersistencePolicy(secrets),
    )

    private fun identityRepository(dataRoot: Path) = ModelRepository(
        JsonFileStorage(dataRoot),
        WindowsSafeModelStorageKeyPolicy,
    )

    private fun embedding(id: String, displayName: String, apiKey: String) = EmbeddingConfig(
        id = id,
        displayName = displayName,
        baseUrl = "https://example.test/v1",
        apiKey = apiKey,
        modelName = "provider/embedding",
        dimensions = 1024,
    )

    private fun assertSanitizedEmbeddingJson(path: Path, forbidden: List<String>) {
        val raw = path.readText()
        forbidden.forEach { secret -> assertFalse(raw.contains(secret)) }
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(EmbeddingConfig.serializer(), raw)
        assertEquals("", decoded.apiKey)
    }

    private fun legacyPath(dataRoot: Path, id: String): Path =
        dataRoot.resolve("entities/embedding_configs/$id.json")

    private fun singletonPath(dataRoot: Path): Path =
        dataRoot.resolve("entities/embedding_model_config/default.json")
}
