package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.OutputTokenParameter
import com.example.chatbar.data.local.entity.PRESET_MODEL_ID_PREFIX
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.PresetEmbeddingModel
import com.example.chatbar.data.local.entity.PresetModelCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRepositoryFoundationTest {
    private lateinit var root: Path
    private lateinit var repository: ModelRepository

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("model-repository-foundation-")
        repository = ModelRepository(JsonFileStorage(root), WindowsSafeModelStorageKeyPolicy)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `model CRUD sorting duplication and observable cache keep baseline behavior`() = runTest {
        val zulu = model("zulu", "Zulu")
        val alpha = model("alpha", "Alpha").copy(
            sourcePresetKey = "source",
            sourcePresetVersion = 2
        )
        repository.saveModel(zulu)
        repository.saveModel(alpha)

        assertEquals(listOf("alpha", "zulu"), repository.getAllModels().map(ModelConfig::id))
        assertEquals(alpha, repository.getModel(alpha.id))
        assertEquals(listOf("alpha", "zulu"), repository.models.first().map(ModelConfig::id))

        val duplicate = repository.duplicateModel(alpha.id)
        assertNotEquals(alpha.id, duplicate.id)
        assertEquals("Alpha (2)", duplicate.displayName)
        assertNull(duplicate.sourcePresetKey)
        assertNull(duplicate.sourcePresetVersion)
        assertTrue(duplicate.createdAt >= alpha.createdAt)

        repository.deleteModel(zulu.id)
        assertNull(repository.getModel(zulu.id))
        assertEquals(listOf("Alpha", "Alpha (2)"), repository.getAllModels().map(ModelConfig::displayName))
    }

    @Test
    fun `preset ensure and restore retain identity credentials and user placement semantics`() = runTest {
        val existing = model("custom-id", "Edited").copy(
            apiKey = "fake-existing-key",
            formatPromptPosition = FormatPromptPosition.END,
            sourcePresetKey = "chat",
            sourcePresetVersion = 1,
            createdAt = 7L
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
                    customParams = mapOf("temperature" to ParamValue.NumberValue(0.5)),
                    reasoningEffort = "low",
                    enableThinking = true,
                    maxOutputTokens = 2048
                ),
                PresetChatModel(
                    modelKey = "vision",
                    displayName = "Vision",
                    modelName = "provider/vision",
                    isMultimodal = true,
                    visionModelKey = "chat"
                )
            )
        )

        assertEquals(existing, repository.ensurePresetChatModels(catalog, 4).first())

        val restored = repository.restorePresetChatModels(catalog, 4)
        val chat = restored.first()
        assertEquals(existing.id, chat.id)
        assertEquals("fake-existing-key", chat.apiKey)
        assertEquals(FormatPromptPosition.END, chat.formatPromptPosition)
        assertEquals(existing.createdAt, chat.createdAt)
        assertEquals("chat", chat.sourcePresetKey)
        assertEquals(4, chat.sourcePresetVersion)
        assertEquals("https://preset.example/v1", chat.baseUrl)
        assertEquals(OutputTokenParameter.MAX_TOKENS, chat.outputTokenParameter)

        val vision = restored.last()
        assertEquals(PRESET_MODEL_ID_PREFIX + "vision", vision.id)
        assertEquals(PRESET_MODEL_ID_PREFIX + "chat", vision.visionModelId)
    }

    @Test
    fun `embedding list migration selects preferred singleton and removes legacy entries`() = runTest {
        val first = embedding("first", "Zulu")
        val preferred = embedding("preferred", "Alpha").copy(dimensions = 3072)
        repository.saveEmbedding(first)
        repository.saveEmbedding(preferred)

        assertEquals(listOf("preferred", "first"), repository.getAllEmbeddings().map(EmbeddingConfig::id))
        repository.migrateEmbeddingsToSingleton(preferred.id)

        assertTrue(repository.getAllEmbeddings().isEmpty())
        assertNull(repository.getEmbedding(first.id))
        assertNull(repository.getEmbedding(preferred.id))
        assertEquals(preferred.copy(id = "default"), repository.getEmbeddingModel())
        assertEquals(preferred.copy(id = "default"), repository.embeddingModel.first())

        repository.deleteEmbeddingModel()
        assertNull(repository.getEmbeddingModel())
    }

    @Test
    fun `preset embedding ensure preserves existing while restore preserves credential and identity`() = runTest {
        val existing = embedding("existing", "Existing").copy(apiKey = "fake-embedding-key")
        repository.saveEmbeddingModel(existing)
        val catalog = PresetModelCatalog(
            baseUrl = "https://preset.example/v1",
            embeddingModel = PresetEmbeddingModel(
                modelKey = "embedding",
                displayName = "Preset Embedding",
                modelName = "provider/embedding",
                dimensions = 1024
            )
        )

        repository.ensurePresetEmbeddingModel(catalog)
        assertEquals(existing.copy(id = "default"), repository.getEmbeddingModel())

        repository.restorePresetEmbeddingModel(catalog)
        val restored = requireNotNull(repository.getEmbeddingModel())
        assertEquals("default", restored.id)
        assertEquals("fake-embedding-key", restored.apiKey)
        assertEquals("Preset Embedding", restored.displayName)
        assertEquals("https://preset.example/v1", restored.baseUrl)
        assertEquals(1024, restored.dimensions)
        assertFalse(restored.modelName.isBlank())
    }

    private fun model(id: String, displayName: String) = ModelConfig(
        id = id,
        displayName = displayName,
        baseUrl = "https://example.test/v1",
        apiKey = "fake-test-key",
        modelName = "provider/model",
        createdAt = 1L
    )

    private fun embedding(id: String, displayName: String) = EmbeddingConfig(
        id = id,
        displayName = displayName,
        baseUrl = "https://example.test/v1",
        apiKey = "fake-test-key",
        modelName = "provider/embedding"
    )
}
