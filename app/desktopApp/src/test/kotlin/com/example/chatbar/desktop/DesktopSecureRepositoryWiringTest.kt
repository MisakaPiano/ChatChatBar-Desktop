package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails
import kotlin.test.assertNull
import com.example.chatbar.desktop.security.DesktopAccountService
import kotlinx.coroutines.test.runTest

class DesktopSecureRepositoryWiringTest {
    @Test fun `named default selection refreshes cached settings and never revives the original key`() = runTest {
        val parent = Files.createTempDirectory("desktop-default-account-routing-")
        val secrets = InMemoryDesktopSecretStore().also { it.save(DesktopCredentialKey.SiliconFlowApiKey, "SYNTHETIC_ORIGINAL") }
        val container = DesktopAppContainer(resolution(parent, parent.resolve("data")), secretStoreFactory = { secrets })
        try {
            container.settingsRepository.initialize()
            container.apiAccountController.adoptExisting(DesktopAccountService.LLM)
            val first = container.apiAccounts.selected(DesktopAccountService.LLM)
            assertEquals("SYNTHETIC_ORIGINAL", container.settingsRepository.currentAppSettings.siliconFlowApiKey)
            container.apiAccountController.save(DesktopAccountService.LLM, null, "Other", "Provider",
                "https://api.siliconflow.cn/v1", "SYNTHETIC_OTHER")
            val other = container.apiAccounts.accounts(DesktopAccountService.LLM).single { it.id != first }.id
            container.apiAccountController.select(DesktopAccountService.LLM, other, null)
            assertEquals("SYNTHETIC_OTHER", container.settingsRepository.currentAppSettings.siliconFlowApiKey)
            container.apiAccountController.select(DesktopAccountService.LLM, null, null)
            assertTrue(container.settingsRepository.currentAppSettings.siliconFlowApiKey.isNullOrEmpty())
            assertEquals("SYNTHETIC_ORIGINAL", secrets.load(DesktopCredentialKey.SiliconFlowApiKey))
        } finally { container.close(); parent.toFile().deleteRecursively() }
    }
    @Test
    fun `deleting a bound model retains its reusable account and reimport does not inherit the old binding`() = runTest {
        val parent = Files.createTempDirectory("desktop-account-model-delete-")
        val secrets = InMemoryDesktopSecretStore()
        val container = DesktopAppContainer(resolution(parent, parent.resolve("data")), secretStoreFactory = { secrets })
        try {
            val model = ModelConfig("synthetic", "Model", "https://synthetic.invalid/v1", "SYNTHETIC_LEGACY", "model", createdAt = 1)
            container.modelRepository.saveModel(model)
            val accounts = container.apiAccounts
            val id = accounts.saveAccount(DesktopAccountService.LLM, null, "Reusable", "Provider", model.baseUrl, "SYNTHETIC_NAMED")
            container.apiAccountController.select(DesktopAccountService.LLM, id, model)
            assertEquals("SYNTHETIC_NAMED", container.modelRepository.getModel(model.id)?.apiKey)
            container.modelRepository.deleteModel(model.id)
            assertNull(container.modelRepository.getModel(model.id))
            assertEquals(listOf(id), accounts.accounts(DesktopAccountService.LLM).map { it.id })
            container.modelRepository.saveModel(model.copy(apiKey = "SYNTHETIC_REIMPORTED"))
            assertEquals("SYNTHETIC_REIMPORTED", container.modelRepository.getModel(model.id)?.apiKey)
            assertNull(accounts.selected(DesktopAccountService.LLM, model.id))
            container.apiAccountController.delete(DesktopAccountService.LLM, id)
            assertTrue(accounts.accounts(DesktopAccountService.LLM).isEmpty())
        } finally { container.close(); parent.toFile().deleteRecursively() }
    }

    @Test
    fun `account deletion prunes only verified missing models and refuses a corrupt model reference`() = runTest {
        val parent = Files.createTempDirectory("desktop-account-reference-")
        val root = parent.resolve("data")
        val container = DesktopAppContainer(resolution(parent, root), secretStoreFactory = { InMemoryDesktopSecretStore() })
        try {
            val model = ModelConfig("synthetic", "Model", "https://synthetic.invalid/v1", "", "model", createdAt = 1)
            container.modelRepository.saveModel(model)
            val accounts = container.apiAccounts
            val id = accounts.saveAccount(DesktopAccountService.LLM, null, "Reusable", "Provider", model.baseUrl, "SYNTHETIC_NAMED")
            container.apiAccountController.select(DesktopAccountService.LLM, id, model)
            val file = root.resolve("entities/model_configs/${WindowsSafeModelStorageKeyPolicy.storageKey(model.id)}.json")
            Files.writeString(file, "synthetic malformed entity")
            assertFails { container.apiAccountController.delete(DesktopAccountService.LLM, id) }
            assertEquals(id, accounts.selected(DesktopAccountService.LLM, model.id))
            Files.delete(file)
            container.modelRepository.refreshModelsFromStorage()
            container.apiAccountController.delete(DesktopAccountService.LLM, id)
            assertTrue(accounts.accounts(DesktopAccountService.LLM).isEmpty())
            assertNull(accounts.selected(DesktopAccountService.LLM, model.id))
        } finally { container.close(); parent.toFile().deleteRecursively() }
    }
    @Test
    fun `container construction and secure repository access remain zero-write`() = runTest {
        val parent = Files.createTempDirectory("desktop-secure-wiring-")
        val appDataRoot = parent.resolve("app-data")
        val secrets = InMemoryDesktopSecretStore()
        secrets.save(DesktopCredentialKey.SiliconFlowApiKey, "synthetic-read-only")
        var requestedRoot: Path? = null
        val container = DesktopAppContainer(
            resolvedRoot = resolution(parent, appDataRoot),
            secretStoreFactory = { root ->
                requestedRoot = root
                secrets
            },
        )
        try {
            assertFalse(Files.exists(appDataRoot))
            assertEquals("synthetic-read-only", container.desktopSecretStore.load(DesktopCredentialKey.SiliconFlowApiKey))
            assertEquals(appDataRoot, requestedRoot)
            container.settingsRepository
            container.modelRepository
            assertFalse(Files.exists(appDataRoot))
            assertEquals(mapOf<DesktopCredentialKey, String>(DesktopCredentialKey.SiliconFlowApiKey to "synthetic-read-only"), secrets.values)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `container repositories use secure policies and Windows entity keys`() = runTest {
        val parent = Files.createTempDirectory("desktop-secure-wiring-save-")
        val appDataRoot = parent.resolve("app-data")
        val secrets = InMemoryDesktopSecretStore()
        val container = DesktopAppContainer(
            resolvedRoot = resolution(parent, appDataRoot),
            secretStoreFactory = { secrets },
        )
        try {
            container.settingsRepository.saveAppSettings(
                AppSettings(siliconFlowApiKey = "fake-test-key"),
            )
            val model = ModelConfig(
                id = "preset:vision",
                displayName = "Vision",
                baseUrl = "https://example.test/v1",
                apiKey = "fake-model-key",
                modelName = "provider/model",
                createdAt = 1L,
            )
            container.modelRepository.saveModel(model)
            val embedding = EmbeddingConfig(
                id = "default",
                displayName = "Embedding",
                baseUrl = "https://example.test/v1",
                apiKey = "fake-embedding-key",
                modelName = "provider/embedding",
            )
            container.modelRepository.saveEmbeddingModel(embedding)

            val settingsJson = Files.readString(appDataRoot.resolve("entities/app_settings.json"))
            val modelPath = appDataRoot.resolve(
                "entities/model_configs/${WindowsSafeModelStorageKeyPolicy.storageKey(model.id)}.json",
            )
            val modelJson = Files.readString(modelPath)
            val embeddingJson = Files.readString(
                appDataRoot.resolve("entities/embedding_model_config/default.json"),
            )
            assertFalse(settingsJson.contains("fake-test-key"))
            assertFalse(modelJson.contains("fake-model-key"))
            assertFalse(embeddingJson.contains("fake-embedding-key"))
            assertTrue(Files.isRegularFile(modelPath))
            assertEquals(
                "fake-test-key",
                secrets.values[DesktopCredentialKey.SiliconFlowApiKey],
            )
            assertEquals(
                "fake-model-key",
                secrets.values[DesktopCredentialKey.ModelApiKey(model.id)],
            )
            assertEquals(
                "fake-embedding-key",
                secrets.values[DesktopCredentialKey.SingletonEmbeddingApiKey],
            )
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun resolution(parent: Path, appDataRoot: Path) = DesktopDataRootResolution.Resolved(
        appDataRoot = appDataRoot,
        provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
        bootstrapPath = parent.resolve("bootstrap.json"),
    )
}
