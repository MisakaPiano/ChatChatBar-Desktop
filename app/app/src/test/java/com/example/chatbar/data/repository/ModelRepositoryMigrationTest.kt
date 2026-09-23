package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PresetModelCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelRepositoryMigrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `old catalog planner field is ignored and restoring it cannot recreate a dedicated model`() = runTest {
        val catalog = Json { ignoreUnknownKeys = true }.decodeFromString(
            PresetModelCatalog.serializer(),
            """{"schemaVersion":1,"chatModels":[],"retrievalModel":{"modelKey":"legacy","displayName":"Legacy","modelName":"old-model"}}"""
        )
        val repository = ModelRepository(storage())
        repository.restorePresetChatModels(catalog, 1)
        repository.restorePresetEmbeddingModel(catalog)
        assertTrue(repository.getAllModels().isEmpty())
    }

    @Test
    fun `legacy planner keeps explicit binding and credentials as ordinary model across restarts`() = runTest {
        val storage = storage()
        val legacy = legacy()
        storage.saveEntity("retrieval_model_config", "default", legacy, ModelConfig.serializer())
        val repository = ModelRepository(storage)
        listOf(async { repository.initialize() }, async { repository.getAllModels() }).awaitAll()
        val migrated = repository.getModel(legacy.id)!!
        assertEquals(legacy.apiKey, migrated.apiKey)
        assertEquals(legacy.baseUrl, migrated.baseUrl)
        assertEquals(legacy.modelName, migrated.modelName)
        assertEquals(legacy.createdAt, migrated.createdAt)
        assertTrue(migrated.selectableForChat)
        assertNull(migrated.sourcePresetKey)
        assertNull(storage.loadEntity("retrieval_model_config", "default", ModelConfig.serializer()))
        assertEquals(listOf(migrated), ModelRepository(storage).getAllModels())
        repository.deleteModel(migrated.id)
        val restarted = ModelRepository(storage)
        restarted.restorePresetEmbeddingModel(PresetModelCatalog())
        assertTrue(restarted.getAllModels().isEmpty())
    }

    @Test
    fun `colliding ordinary model survives migration and legacy config is preserved separately`() = runTest {
        val storage = storage()
        val legacy = legacy()
        val ordinary = legacy.copy(displayName = "My model", modelName = "other-model", apiKey = "other-key")
        storage.saveEntity("model_configs", ordinary.id, ordinary, ModelConfig.serializer())
        storage.saveEntity("retrieval_model_config", "default", legacy, ModelConfig.serializer())
        val repository = ModelRepository(storage)
        val models = repository.getAllModels()
        assertEquals(ordinary, repository.getModel(ordinary.id))
        assertEquals(2, models.size)
        assertEquals(legacy.apiKey, models.single { it.id != ordinary.id }.apiKey)
        assertEquals(models, ModelRepository(storage).getAllModels())
    }

    @Test
    fun `interruption after ordinary model save does not duplicate migration`() = runTest {
        val storage = storage()
        val legacy = legacy()
        val saved = legacy.copy(
            selectableForChat = true, sourcePresetKey = null, sourcePresetVersion = null
        )
        storage.saveEntity("retrieval_model_config", "default", legacy, ModelConfig.serializer())
        storage.saveEntity("model_configs", saved.id, saved, ModelConfig.serializer())
        assertEquals(listOf(saved), ModelRepository(storage).getAllModels())
        assertNull(storage.loadEntity("retrieval_model_config", "default", ModelConfig.serializer()))
    }

    private fun legacy() = ModelConfig(
        id = "default", displayName = "Custom planner", baseUrl = "https://example.test/v1",
        apiKey = "kept-key", modelName = "test-model", selectableForChat = false,
        sourcePresetKey = "old-planner", sourcePresetVersion = 1, createdAt = 123L
    )

    private fun storage(): JsonFileStorage = JsonFileStorage(temp.newFolder().toPath())
}
