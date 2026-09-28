package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class DesktopModelDiagnosticsTest {
    private val model = ModelConfig(
        id = "model-one", displayName = "One", modelName = "one",
        baseUrl = "https://example.invalid/v1", apiKey = "fake-model-secret", createdAt = 1L,
    )

    @Test
    fun `diagnostic distinguishes explicit automatic and stale fallback without secrets`() {
        val settings = AppSettings(siliconFlowApiKey = "fake-global-secret")
        val explicit = desktopModelDiagnostic(model.id, model, settings, model, null)
        val automatic = desktopModelDiagnostic(null, model, settings, model, null)
        val stale = desktopModelDiagnostic("deleted-model", model, settings, model, null)
        assertEquals(DesktopModelSelection.EXPLICIT, explicit.selection)
        assertEquals(DesktopModelSelection.AUTOMATIC, automatic.selection)
        assertEquals(DesktopModelSelection.STALE_FALLBACK, stale.selection)
        assertEquals("deleted-model", stale.configuredId)
        assertEquals(model.id, stale.effectiveId)
        assertEquals(DesktopCredentialSource.MODEL_KEY, explicit.credentialSource)
        assertFalse(explicit.toString().contains("fake-model-secret"))
        assertFalse(explicit.toString().contains("fake-global-secret"))
    }

    @Test
    fun `credential provenance follows model key global key local no-auth and missing cases`() {
        val global = AppSettings(siliconFlowApiKey = "fake-global-secret")
        assertEquals(DesktopCredentialSource.GLOBAL_KEY,
            desktopModelDiagnostic(null, model, global, model.copy(apiKey = ""), null).credentialSource)
        val local = model.copy(baseUrl = "http://127.0.0.1:12345/v1", apiKey = "")
        assertEquals(DesktopCredentialSource.NO_AUTH,
            desktopModelDiagnostic(null, local, global.copy(allowCleartextModelApi = true), local, null).credentialSource)
        assertEquals(DesktopCredentialSource.UNCONFIGURED,
            desktopModelDiagnostic(null, model, AppSettings(), model.copy(apiKey = ""), null).credentialSource)
    }

    @Test
    fun `set default and automatic actions persist immediately and preserve unrelated settings`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-default-diagnostic-")
        val root = parent.resolve("data")
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
                parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try {
            container.modelRepository.saveModel(model)
            container.settingsRepository.saveAppSettings(AppSettings(lastSeenChatAt = 41L,
                siliconFlowApiKey = "fake-global-secret"))
            val controller = container.modelSettingsController
            controller.loadModels()
            assertEquals(DesktopModelSelection.AUTOMATIC, controller.state.value.defaultDiagnostic?.selection)
            container.settingsRepository.updateAppSettings { it.copy(defaultModelId = "removed-model") }
            controller.loadModels()
            assertEquals(DesktopModelSelection.STALE_FALLBACK, controller.state.value.defaultDiagnostic?.selection)
            assertEquals("removed-model", controller.state.value.defaultDiagnostic?.configuredId)
            assertEquals(model.id, controller.state.value.defaultDiagnostic?.effectiveId)
            controller.setDefaultModel(model.id)
            assertEquals(model.id, container.settingsRepository.getAppSettings().defaultModelId)
            assertEquals(DesktopModelSelection.EXPLICIT, controller.state.value.defaultDiagnostic?.selection)
            controller.setDefaultModel(null)
            val saved = container.settingsRepository.getAppSettings()
            assertNull(saved.defaultModelId)
            assertEquals(41L, saved.lastSeenChatAt)
            assertEquals("fake-global-secret", saved.siliconFlowApiKey)
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }
}
