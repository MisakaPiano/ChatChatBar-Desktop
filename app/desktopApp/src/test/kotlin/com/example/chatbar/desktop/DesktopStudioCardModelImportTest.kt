package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopStudioCardModelImportTest {
    private fun NovelAiStudioDraft.content() = copy(updatedAt = 0, contentRevision = 0, promptContentRevision = 0)

    @Test fun `actual card import resolves explicit inherited and manual models before updating only the active settings bank and one undo`() = runBlocking {
        val parent = Files.createTempDirectory("studio-card-model-import-")
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                if (path.startsWith("presets/")) """{"entries":[]}""".toByteArray() else DesktopBundledAssetReader()(path)
            })
        try {
            val controller = app.novelAiStudioController; assertTrue(controller.load())
            app.settingsRepository.updateAppSettings { it.copy(novelAiImageModel = NovelAiImageModel.V5_FULL) }
            val overrides = NovelAiCharacterImageSettings(steps = 23, guidance = 7f, cfgRescale = .3f)
            val cases = listOf(Triple(NovelAiImageModel.V5_FULL, false, NovelAiImageModel.V5_FULL),
                Triple(NovelAiImageModel.V5_FULL, true, NovelAiImageModel.V5_FULL),
                Triple(null, true, NovelAiImageModel.V5_FULL), Triple(null, false, NovelAiImageModel.V4_5_FULL))
            for ((index, case) in cases.withIndex()) {
                val (cardModel, follow, expectedModel) = case
                val card = CharacterCard("synthetic-$index", "Synthetic card $index", greeting = "Synthetic opening",
                    defaultImagePrompt = "Card style  ", defaultImageNegativePrompt = "Card negative  ",
                    defaultNovelAiImageModel = cardModel, defaultImageGenerationSettings = overrides, createdAt = 1, updatedAt = 1)
                app.characterRepository.save(card)
                controller.edit { it.copy(selectedModel = NovelAiImageModel.V4_5_FULL, followDefaultNovelAiImageModel = follow,
                    importedCharacterCardId = null, stylePrompt = "Existing style  ", basePrompt = "Existing scene  ", extraPrompt = "Extra  ",
                    v45Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL, steps = 39, guidance = 8f, seed = 123L),
                    v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 34, guidance = 9f, seed = 456L)) }
                val before = controller.draft.value!!
                controller.importCard(card.id)
                val after = controller.draft.value!!
                assertEquals(expectedModel, after.selectedModel, "cardModel=$cardModel follow=$follow")
                assertEquals(if (cardModel != null) false else follow, after.followDefaultNovelAiImageModel)
                assertEquals(23, after.activeSettings.steps); assertEquals(7f, after.activeSettings.guidance)
                assertEquals(.3f, after.activeSettings.cfgRescale)
                if (expectedModel == NovelAiImageModel.V5_FULL) {
                    assertEquals(before.v45Settings, after.v45Settings); assertEquals(456L, after.activeSettings.seed)
                } else {
                    assertEquals(before.v5Settings, after.v5Settings); assertEquals(123L, after.activeSettings.seed)
                }
                assertEquals(before.basePrompt, after.basePrompt); assertEquals(before.extraPrompt, after.extraPrompt)
                assertEquals(card.id, after.importedCharacterCardId)
                assertEquals(after.content(), NovelAiStudioStateRepository(JsonFileStorage(root)).loadDraft().content())
                assertEquals(card, app.characterRepository.getById(card.id))
                assertTrue(controller.undo()); assertEquals(before.content(), controller.draft.value!!.content())
                assertTrue(controller.redo()); assertEquals(after.content(), controller.draft.value!!.content())
            }
            assertTrue(app.taskRuntime.tasks.value.isEmpty())
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
