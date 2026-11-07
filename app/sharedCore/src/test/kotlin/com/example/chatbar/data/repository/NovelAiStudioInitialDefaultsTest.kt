package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NovelAiStudioInitialDefaultsTest {
    @Test fun `missing Studio draft starts both models with current explicit defaults and persists them`() = runBlocking {
        val root = Files.createTempDirectory("studio-initial-defaults-")
        try {
            val repository = NovelAiStudioStateRepository(JsonFileStorage(root))
            val fresh = repository.loadDraft()
            assertTrue(fresh.followDefaultNovelAiImageModel)
            assertEquals(NovelAiGenerationDefaults.forModel(NovelAiImageModel.V4_5_FULL), fresh.v45Settings)
            assertEquals(NovelAiGenerationDefaults.forModel(NovelAiImageModel.V5_FULL), fresh.v5Settings)
            repository.updateDraft { it.copy(basePrompt = "synthetic persisted scene") }
            assertEquals(repository.draft.value, NovelAiStudioStateRepository(JsonFileStorage(root)).loadDraft())
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun `existing legacy draft keeps decoded and explicitly saved sampling rather than being reset`() = runBlocking {
        val root = Files.createTempDirectory("studio-legacy-defaults-")
        try {
            val storage = JsonFileStorage(root)
            val legacy = NovelAiStudioDraft(basePrompt = "synthetic old scene",
                v45Settings = NovelAiGenerationSettings(steps = 28, guidance = 6f),
                v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 41, guidance = 8.5f))
            storage.saveSingleton("novelai_studio_draft", legacy, NovelAiStudioDraft.serializer())
            val bytes = Files.readAllBytes(root.resolve("entities/novelai_studio_draft.json"))
            assertEquals(legacy, NovelAiStudioStateRepository(storage).loadDraft())
            assertContentEquals(bytes, Files.readAllBytes(root.resolve("entities/novelai_studio_draft.json")))
            assertEquals(28, kotlinx.serialization.json.Json.decodeFromString(NovelAiStudioDraft.serializer(),
                """{"basePrompt":"synthetic omitted settings"}""").v45Settings.steps)
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
