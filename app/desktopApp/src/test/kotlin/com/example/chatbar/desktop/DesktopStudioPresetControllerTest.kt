package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopStudioPresetControllerTest {
    private class Fixture {
        val parent = Files.createTempDirectory("studio-preset-controller-")
        val root = Files.createDirectory(parent.resolve("data"))
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        val controller = app.novelAiStudioController
        val file = parent.resolve("synthetic.ccbpreset")
        val draftFile = root.resolve("entities/novelai_studio_draft.json")
        suspend fun prepare() {
            controller.load()
            app.settingsRepository.updateAppSettings { it.copy(novelAiImageModel = NovelAiImageModel.V5_FULL) }
            controller.edit { it.copy(basePrompt = "source", negativePrompt = "source-negative", followDefaultNovelAiImageModel = true,
                selectedModel = NovelAiImageModel.V4_5_FULL, continuousModeEnabled = false, continuousTargetCount = 12,
                characters = listOf(NovelAiCharacterPromptDraft(prompt = "person", negativePrompt = "person-negative", enabled = false))) }
            assertTrue(controller.exportGenerationPreset(file))
            assertEquals(NovelAiImageModel.V5_FULL, controller.draft.value!!.selectedModel)
        }
        suspend fun close() { app.close(); check(parent.toFile().deleteRecursively()) }
    }

    @Test fun `actual production controller export resolves defaults and preview explicit apply and original undo preserve surrounding configuration`() = runBlocking {
        val f = Fixture()
        try {
            f.prepare()
            f.controller.edit { it.copy(basePrompt = "target", negativePrompt = "target-negative", aiDesignModelId = "target-ai-model",
                imageDescription = "target-input", continuousModeEnabled = true, continuousTargetCount = 100) }
            val before = Files.readAllBytes(f.draftFile)
            assertTrue(f.controller.inspectGenerationPreset(f.file))
            assertContentEquals(before, Files.readAllBytes(f.draftFile))
            val preview = assertNotNull(f.controller.state.value.presetPreview)
            assertEquals(NovelAiImageModel.V5_FULL, preview.preset.selectedModel)
            assertFalse(preview.preset.continuousModeEnabled)
            assertTrue(f.controller.applyGenerationPreset())
            val result = f.controller.draft.value!!
            assertEquals("source", result.basePrompt); assertEquals("source-negative", result.negativePrompt)
            assertFalse(result.continuousModeEnabled); assertEquals(12, result.continuousTargetCount)
            assertEquals("target-ai-model", result.aiDesignModelId); assertEquals("target-input", result.imageDescription)
            assertFalse(result.characters.single().enabled); assertEquals("person-negative", result.characters.single().negativePrompt)
            assertNull(f.controller.state.value.presetPreview); assertTrue(f.app.taskRuntime.tasks.value.isEmpty())
            assertTrue(f.controller.undo())
            assertEquals("target", f.controller.draft.value!!.basePrompt); assertTrue(f.controller.draft.value!!.continuousModeEnabled)
            assertEquals(100, f.controller.draft.value!!.continuousTargetCount)
        } finally { f.close() }
    }

    @Test fun `cancel stale preview malformed archive and committed root switch never replace the current draft`() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); f.controller.edit { it.copy(basePrompt = "target") }
            val original = Files.readAllBytes(f.draftFile)
            assertTrue(f.controller.inspectGenerationPreset(f.file)); f.controller.discardGenerationPreset()
            assertNull(f.controller.state.value.presetPreview); assertContentEquals(original, Files.readAllBytes(f.draftFile))
            val bad = Files.write(f.parent.resolve("bad.ccbpreset"), byteArrayOf(1, 2, 3))
            assertFalse(f.controller.inspectGenerationPreset(bad)); assertContentEquals(original, Files.readAllBytes(f.draftFile))
            assertTrue(f.controller.inspectGenerationPreset(f.file))
            assertTrue(f.controller.edit { it.copy(basePrompt = "newer draft") })
            val newer = Files.readAllBytes(f.draftFile)
            assertFalse(f.controller.applyGenerationPreset()); assertContentEquals(newer, Files.readAllBytes(f.draftFile))
            f.controller.discardGenerationPreset()
            assertTrue(f.controller.inspectGenerationPreset(f.file))
            f.app.dataOperationCoordinator.withExclusiveMaintenance { requireRestart() }
            assertFalse(f.controller.applyGenerationPreset()); assertContentEquals(newer, Files.readAllBytes(f.draftFile))
            assertEquals("newer draft", f.controller.draft.value!!.basePrompt)
            assertTrue(f.app.taskRuntime.tasks.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun `normal application close drains an uncommitted preview without applying it`() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); f.controller.edit { it.copy(basePrompt = "retained target") }
            assertTrue(f.controller.inspectGenerationPreset(f.file))
            val before = Files.readAllBytes(f.draftFile)
            f.app.close()
            assertContentEquals(before, Files.readAllBytes(f.draftFile))
            assertEquals(0L, Files.list(f.root.resolve("backups/.studio-preset-import")).use { it.count() })
            assertNull(f.controller.state.value.presetPreview)
        } finally { f.close() }
    }

    @Test fun `committed draft with unknown staging child is applied and undoable while cleanup remains explicit without repeating import`() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); f.controller.edit { it.copy(basePrompt = "retained target") }
            val originalArchive = Files.readAllBytes(f.file)
            assertTrue(f.controller.inspectGenerationPreset(f.file))
            val home = Files.list(f.root.resolve("backups/.studio-preset-import")).use { it.findFirst().orElseThrow() }
            val unknown = Files.writeString(home.resolve("unknown-retained"), "original")
            assertTrue(f.controller.applyGenerationPreset())
            assertEquals("source", f.controller.draft.value!!.basePrompt)
            assertEquals(DesktopPresetApplyOutcome.APPLIED_CLEANUP_PENDING, f.controller.state.value.presetOutcome)
            assertNull(f.controller.state.value.presetPreview)
            assertTrue(f.controller.state.value.status.contains("已应用")); assertTrue(f.controller.state.value.status.contains("请勿重复导入"))
            assertEquals("original", Files.readString(unknown)); assertContentEquals(originalArchive, Files.readAllBytes(f.file))
            assertEquals("APPLIED_CLEANUP_PENDING", DesktopPresetCustody(f.root).records().single { it.kind == "STAGING" }.phase)
            assertTrue(f.controller.undo()); assertEquals("retained target", f.controller.draft.value!!.basePrompt)
            val before = Files.readAllBytes(f.draftFile)
            assertFalse(f.controller.applyGenerationPreset()); assertContentEquals(before, Files.readAllBytes(f.draftFile))
            f.app.close(); assertEquals("original", Files.readString(unknown))
        } finally { f.close() }
    }
}
