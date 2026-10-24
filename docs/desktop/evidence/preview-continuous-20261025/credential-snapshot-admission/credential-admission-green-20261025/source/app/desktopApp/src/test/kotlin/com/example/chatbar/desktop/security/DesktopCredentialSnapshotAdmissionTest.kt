package com.example.chatbar.desktop.security

import com.example.chatbar.data.local.entity.ModelConfig
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
