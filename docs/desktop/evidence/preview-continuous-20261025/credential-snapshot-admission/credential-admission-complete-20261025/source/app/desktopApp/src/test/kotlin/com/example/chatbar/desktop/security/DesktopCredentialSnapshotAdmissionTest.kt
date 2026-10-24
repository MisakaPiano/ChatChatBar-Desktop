package com.example.chatbar.desktop.security

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import com.example.chatbar.desktop.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Actual production container wiring; no Provider request, preset content or real secret store. */
class DesktopCredentialSnapshotAdmissionTest {
    private class FakeStore : DesktopSecretStore {
        val values = linkedMapOf<DesktopCredentialKey, String>()
        override fun load(key: DesktopCredentialKey) = values[key]
        override fun save(key: DesktopCredentialKey, secret: String) { values[key] = secret }
        override fun delete(key: DesktopCredentialKey) { values.remove(key) }
    }

    @Test fun `both embedding namespaces wait before key and endpoint publication`() = runBlocking<Unit> {
        for (singleton in listOf(false, true)) {
            val root = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/credential-admission").toAbsolutePath()), "embedding-")
            val store = FakeStore(); val actor = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.parent.resolve("synthetic-bootstrap.json")),
                secretStoreFactory = { store }, bundledAssets = { error("SYNTHETIC_PRESET_ACCESS_FORBIDDEN") })
            var operation: Job? = null
            try {
                val config = EmbeddingConfig("embedding", "Synthetic", "https://old.invalid/v1", "FAKE_OLD_EMBEDDING", "remote")
                suspend fun save(value: EmbeddingConfig) = if (singleton) container.modelRepository.saveEmbeddingModel(value) else container.modelRepository.saveEmbedding(value)
                save(config)
                val key = if (singleton) DesktopCredentialKey.SingletonEmbeddingApiKey else DesktopCredentialKey.LegacyEmbeddingApiKey("embedding")
                val path = root.resolve(if (singleton) "entities/embedding_model_config/default.json" else "entities/embedding_configs/embedding.json")
                container.dataOperationCoordinator.withExclusiveMaintenance {
                    operation = actor.launch { save(config.copy(baseUrl = "https://new.invalid/v1", apiKey = "FAKE_NEW_EMBEDDING")) }
                    assertEquals("FAKE_OLD_EMBEDDING", store.load(key))
                    assertEquals("https://old.invalid/v1", Json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("baseUrl").jsonPrimitive.content)
                }
                operation!!.join()
                assertEquals("FAKE_NEW_EMBEDDING", store.load(key)); assertFalse(Files.readString(path).contains("FAKE_NEW_EMBEDDING"))
            } finally { operation?.join(); actor.cancel(); container.close() }
        }
    }

    @Test fun `global settings key and configuration obey same exclusive snapshot admission`() = runBlocking<Unit> {
        val root = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/credential-admission").toAbsolutePath()), "settings-")
        val store = FakeStore(); val actor = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.parent.resolve("synthetic-bootstrap.json")),
            secretStoreFactory = { store }, bundledAssets = { error("SYNTHETIC_PRESET_ACCESS_FORBIDDEN") })
        var operation: Job? = null
        try {
            container.settingsRepository.updateAppSettings { it.copy(defaultModelId = "old-model", siliconFlowApiKey = "FAKE_OLD_GLOBAL") }
            val path = root.resolve("entities/app_settings.json")
            container.dataOperationCoordinator.withExclusiveMaintenance {
                operation = actor.launch { container.settingsRepository.updateAppSettings { it.copy(defaultModelId = "new-model", siliconFlowApiKey = "FAKE_NEW_GLOBAL") } }
                assertEquals("FAKE_OLD_GLOBAL", store.load(DesktopCredentialKey.SiliconFlowApiKey))
                assertEquals("old-model", Json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("defaultModelId").jsonPrimitive.content)
            }
            operation!!.join()
            assertEquals("FAKE_NEW_GLOBAL", store.load(DesktopCredentialKey.SiliconFlowApiKey)); assertFalse(Files.readString(path).contains("FAKE_NEW_GLOBAL"))
        } finally { operation?.join(); actor.cancel(); container.close() }
    }

    @Test fun `standalone Fish and NovelAI editors cannot write after data admission refuses`() = runBlocking<Unit> {
        val root = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/credential-admission").toAbsolutePath()), "editors-")
        val store = FakeStore().apply { values[DesktopCredentialKey.FishAudioApiKey] = "FAKE_OLD_FISH"; values[DesktopCredentialKey.NovelAiToken] = "FAKE_OLD_NAI" }
        val denied = object : AppDataOperationGate {
            override suspend fun <T> withNormalOperation(operation: suspend () -> T): T = error("SYNTHETIC_ADMISSION_CLOSED")
        }
        val fish = DesktopFishCredentialController(DesktopFishAudioCredentials(store), denied)
        assertFalse(fish.save("FAKE_NEW_FISH")); assertEquals("FAKE_OLD_FISH", store.load(DesktopCredentialKey.FishAudioApiKey))
        val settings = com.example.chatbar.data.repository.SettingsRepository(com.example.chatbar.data.local.JsonFileStorage(root))
        val nai = DesktopNovelAiSettingsController(store, settings, denied)
        nai.saveCredential("FAKE_NEW_NAI"); assertNotNull(nai.state.value.error)
        assertEquals("FAKE_OLD_NAI", store.load(DesktopCredentialKey.NovelAiToken))
        assertFalse(Files.exists(root.resolve("entities/app_settings.json")))
        fish.closeAndDrain()
    }

    @Test fun `exclusive data snapshot cannot see new key paired with old endpoint`() = runBlocking<Unit> {
        val root = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/credential-admission").toAbsolutePath()), "root-")
        val store = FakeStore()
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.parent.resolve("synthetic-bootstrap.json")),
            secretStoreFactory = { store }, bundledAssets = { error("SYNTHETIC_PRESET_ACCESS_FORBIDDEN") })
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var operation: Job? = null
        try {
            val model = ModelConfig("model", "Synthetic", "https://old.invalid/v1", "FAKE_OLD_KEY", "remote", createdAt = 1)
            container.modelRepository.saveModel(model)
            val path = root.resolve("entities/model_configs/${WindowsSafeModelStorageKeyPolicy.storageKey("model")}.json")
            container.dataOperationCoordinator.withExclusiveMaintenance {
                // Unrelated scope does not borrow the maintenance owner's reentrant context.
                operation = actor.launch { container.modelRepository.saveModel(model.copy(baseUrl = "https://new.invalid/v1", apiKey = "FAKE_NEW_KEY")) }
                assertEquals("https://old.invalid/v1", Json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("baseUrl").jsonPrimitive.content)
                assertEquals("FAKE_OLD_KEY", store.load(DesktopCredentialKey.ModelApiKey("model")))
            }
            operation!!.join()
            assertEquals("FAKE_NEW_KEY", store.load(DesktopCredentialKey.ModelApiKey("model")))
            assertEquals("https://new.invalid/v1", Json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("baseUrl").jsonPrimitive.content)
            assertFalse(Files.readString(path).contains("FAKE_NEW_KEY"))
        } finally {
            operation?.join(); actor.cancel(); container.close()
        }
    }
}
