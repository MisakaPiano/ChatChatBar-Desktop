package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DesktopSecureRepositoryWiringTest {
    @Test
    fun `container construction and secure repository access remain zero-write`() = runTest {
        val parent = Files.createTempDirectory("desktop-secure-wiring-")
        val appDataRoot = parent.resolve("app-data")
        val secrets = InMemoryDesktopSecretStore()
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
            assertSame(secrets, container.desktopSecretStore)
            assertEquals(appDataRoot, requestedRoot)
            container.settingsRepository
            container.modelRepository
            assertFalse(Files.exists(appDataRoot))
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

            val settingsJson = Files.readString(appDataRoot.resolve("entities/app_settings.json"))
            val modelPath = appDataRoot.resolve(
                "entities/model_configs/${WindowsSafeModelStorageKeyPolicy.storageKey(model.id)}.json",
            )
            val modelJson = Files.readString(modelPath)
            assertFalse(settingsJson.contains("fake-test-key"))
            assertFalse(modelJson.contains("fake-model-key"))
            assertTrue(Files.isRegularFile(modelPath))
            assertEquals(
                "fake-test-key",
                secrets.values[DesktopCredentialKey.SiliconFlowApiKey],
            )
            assertEquals(
                "fake-model-key",
                secrets.values[DesktopCredentialKey.ModelApiKey(model.id)],
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
