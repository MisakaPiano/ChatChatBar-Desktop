package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PRESET_MODEL_ID_PREFIX
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.PresetModelCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelStorageKeyPolicyTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        roots.asReversed().forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        roots.clear()
    }

    @Test
    fun `Windows policy is reversible safe and collision free for relevant logical IDs`() {
        val preset = "preset:vision"
        val otherForbidden = "preset?vision"
        val ordinary = "ordinary-model"

        val presetKey = WindowsSafeModelStorageKeyPolicy.storageKey(preset)
        val otherKey = WindowsSafeModelStorageKeyPolicy.storageKey(otherForbidden)
        val ordinaryKey = WindowsSafeModelStorageKeyPolicy.storageKey(ordinary)

        assertNotEquals(presetKey, otherKey)
        assertNotEquals(presetKey, ordinaryKey)
        assertTrue(listOf(presetKey, otherKey, ordinaryKey).all(::isWindowsSafeFileName))
        assertTrue(listOf(presetKey, otherKey, ordinaryKey).all { it == it.lowercase() })
        assertNotEquals(
            WindowsSafeModelStorageKeyPolicy.storageKey("A").lowercase(),
            WindowsSafeModelStorageKeyPolicy.storageKey("a").lowercase()
        )
        assertEquals(preset, WindowsSafeModelStorageKeyPolicy.logicalModelId(presetKey))
        assertEquals(otherForbidden, WindowsSafeModelStorageKeyPolicy.logicalModelId(otherKey))
        assertEquals(ordinary, WindowsSafeModelStorageKeyPolicy.logicalModelId(ordinaryKey))
    }

    @Test
    fun `Windows repository persists reopens looks up and deletes preset logical IDs`() = runTest {
        val root = newRoot()
        val catalog = PresetModelCatalog(
            chatModels = listOf(
                PresetChatModel(
                    modelKey = "chat",
                    displayName = "Chat",
                    modelName = "provider/chat"
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
        val repository = windowsRepository(root)

        val firstRestore = repository.restorePresetChatModels(catalog, 3)
        val secondRestore = repository.restorePresetChatModels(catalog, 3)

        assertEquals(firstRestore, secondRestore)
        val logicalVisionId = PRESET_MODEL_ID_PREFIX + "vision"
        val logicalChatId = PRESET_MODEL_ID_PREFIX + "chat"
        val vision = requireNotNull(repository.getModel(logicalVisionId))
        assertEquals(logicalVisionId, vision.id)
        assertEquals(logicalChatId, vision.visionModelId)

        val filenames = modelFileNames(root)
        assertEquals(2, filenames.size)
        assertTrue(filenames.all(::isWindowsSafeFileName))
        assertTrue(filenames.none { it.contains(logicalVisionId) || it.contains(logicalChatId) })

        val reopened = windowsRepository(root)
        assertEquals(vision, reopened.getModel(logicalVisionId))
        reopened.deleteModel(logicalVisionId)
        assertNull(reopened.getModel(logicalVisionId))
        assertEquals(logicalChatId, requireNotNull(reopened.getModel(logicalChatId)).id)
        assertEquals(1, modelFileNames(root).size)
    }

    @Test
    fun `identity policy keeps upstream physical storage keys`() = runTest {
        assertEquals("preset:vision", IdentityModelStorageKeyPolicy.storageKey("preset:vision"))

        val root = newRoot()
        val repository = ModelRepository(JsonFileStorage(root), IdentityModelStorageKeyPolicy)
        repository.saveModel(model("ordinary-model"))

        assertEquals(setOf("ordinary-model.json"), modelFileNames(root))
        assertEquals("ordinary-model", requireNotNull(repository.getModel("ordinary-model")).id)
    }

    @Test
    fun `legacy planning migration uses Windows policy without changing logical ID`() = runTest {
        val root = newRoot()
        val storage = JsonFileStorage(root)
        val logicalId = "preset:legacy-planner"
        val legacy = model(logicalId).copy(
            displayName = "Custom planner",
            apiKey = "fake-legacy-key",
            selectableForChat = false,
            sourcePresetKey = "old-planner",
            sourcePresetVersion = 1
        )
        storage.saveEntity("retrieval_model_config", "default", legacy, ModelConfig.serializer())

        val repository = ModelRepository(storage, WindowsSafeModelStorageKeyPolicy)
        val migrated = requireNotNull(repository.getModel(logicalId))

        assertEquals(logicalId, migrated.id)
        assertEquals("fake-legacy-key", migrated.apiKey)
        assertTrue(migrated.selectableForChat)
        assertNull(migrated.sourcePresetKey)
        assertNull(migrated.sourcePresetVersion)
        assertEquals(
            setOf(WindowsSafeModelStorageKeyPolicy.storageKey(logicalId) + ".json"),
            modelFileNames(root)
        )
        assertNull(storage.loadEntity("retrieval_model_config", "default", ModelConfig.serializer()))
    }

    private fun windowsRepository(root: Path) =
        ModelRepository(JsonFileStorage(root), WindowsSafeModelStorageKeyPolicy)

    private fun newRoot(): Path =
        Files.createTempDirectory("model-storage-key-policy-").also(roots::add)

    private fun modelFileNames(root: Path): Set<String> {
        val directory = root.resolve("entities/model_configs")
        if (!Files.isDirectory(directory)) return emptySet()
        return Files.list(directory).use { files -> files.map { it.name }.toList().toSet() }
    }

    private fun model(id: String) = ModelConfig(
        id = id,
        displayName = "Model",
        baseUrl = "https://example.test/v1",
        apiKey = "fake-test-key",
        modelName = "provider/model",
        createdAt = 1L
    )

    private fun isWindowsSafeFileName(value: String): Boolean =
        value.none { it in "<>:\"/\\|?*" } &&
            !value.endsWith('.') && !value.endsWith(' ')
}
