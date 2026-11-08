package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.model.*
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopRetainedModelChoicesTest {
    private class Fixture {
        val root = Files.createTempDirectory("retained-model-choices-")
        val storage = JsonFileStorage(root)
        val secrets = InMemoryDesktopSecretStore()
        fun repository() = ModelRepository(storage, WindowsSafeModelStorageKeyPolicy, DesktopModelCredentialPersistencePolicy(secrets))
        val models = repository()
        val settings = SettingsRepository(storage)
        val formats = FormatCardRepository(storage)
        val assets = mapOf(
            "presets/manifest.json" to """{"entries":[{"type":"MODEL_CATALOG","version":1,"file":"inline-models.json"}]}""",
            "inline-models.json" to """{"baseUrl":"https://fixture.invalid/v1","chatModels":[]}""",
        )
        val presets = DesktopPresetModelCatalogSource({ path -> assets.getValue(path).toByteArray() }, Json { ignoreUnknownKeys = true })
        fun controller(models: ModelRepository = this.models) = DesktopModelSettingsController(models, settings, formats,
            EffectiveModelResolver(models, settings, presets), ModelDiscoveryService(), presets)
        val source = ModelConfig("first", "First", "https://fixture.invalid/v1", "synthetic-secret-not-ui", "fixture-model", createdAt = 1)
        fun close() { root.toFile().deleteRecursively() }
    }

    @Test fun `retained choices refresh without replacing dirty settings model editor or explicit stale choice`() = runBlocking {
        val f = Fixture()
        var observer: Job? = null
        try {
            withTimeout(10000) {
                f.models.saveModel(f.source)
                f.settings.updateAppSettings { it.copy(defaultModelId = "first") }
                val c = f.controller()
                c.loadSettings(); c.startEdit("first")
                c.editSettings { it.copy(defaultModelId = "explicit-unsaved", defaultContextWindowSize = "33") }
                c.editModel { it.copy(displayName = "Unsaved editor name") }
                val settingsDraft = c.state.value.settings
                val editorDraft = c.state.value.editor
                observer = launch { c.observeModelChoices() }
                c.state.first { it.availableChatModels.any { model -> model.id == "first" } }
                f.models.saveModel(f.source.copy(displayName = "Renamed persisted", modelName = "new-model"))
                c.state.first { it.availableChatModels.any { model -> model.displayName == "Renamed persisted" } }
                f.models.saveModel(f.source.copy(id = "second", displayName = "Second", apiKey = "another-synthetic-secret"))
                c.state.first { it.availableAuxiliaryTextModels.any { model -> model.id == "second" } }
                f.settings.updateAppSettings { it.copy(defaultModelId = "second", defaultImageModelId = "second") }
                c.state.first { it.effectiveModels.chat?.id == "second" && it.defaultDiagnostic?.effectiveId == "second" }
                f.models.deleteModel("first")
                c.state.first { it.availableChatModels.none { model -> model.id == "first" } }
                assertEquals(settingsDraft, c.state.value.settings)
                assertEquals(editorDraft, c.state.value.editor)
                assertTrue(c.state.value.chatDefaultsDirty); assertTrue(c.state.value.editorDirty)
                assertFalse(c.state.value.modelChoicesReadFailed)
                assertFalse(c.state.value.toString().contains("synthetic-secret"))
                assertFalse(c.state.value.busy)
                observer?.cancelAndJoin(); observer = null
            }
        } finally {
            withContext(NonCancellable) { observer?.cancelAndJoin() }
            f.close()
        }
    }

    @Test fun `protected model read failure is explicit and retry resumes the original reader without rewriting keys`() = runBlocking {
        val f = Fixture()
        var observer: Job? = null
        try {
            withTimeout(10000) {
                f.models.saveModel(f.source)
                val c = f.controller(f.repository())
                val saved = f.secrets.values.toMap()
                f.secrets.loadFailure = IOException("synthetic protected storage failure")
                c.observeModelChoices()
                assertTrue(c.state.value.modelChoicesReadFailed)
                assertEquals(saved, f.secrets.values)
                val before = c.state.value.modelChoicesObservationRevision
                f.secrets.loadFailure = null
                c.retryModelChoices()
                assertEquals(before + 1, c.state.value.modelChoicesObservationRevision)
                observer = launch { c.observeModelChoices() }
                c.state.first { !it.modelChoicesReadFailed && it.availableChatModels.any { model -> model.id == "first" } }
                assertEquals(saved, f.secrets.values)
                assertFalse(c.state.value.toString().contains("synthetic-secret-not-ui"))
                observer?.cancelAndJoin(); observer = null
            }
        } finally {
            withContext(NonCancellable) { observer?.cancelAndJoin() }
            f.close()
        }
    }
}
