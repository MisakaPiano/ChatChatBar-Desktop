package com.example.chatbar.desktop

import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopStudioEditResponsivenessTest {
    private class Fixture {
        val root = Files.createTempDirectory("studio-edit-response-")
        val secrets = InMemoryDesktopSecretStore()
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(Files.createDirectory(root.resolve("data")),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { secrets }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        fun controller(cleanup: suspend (List<String>) -> Unit) = DesktopNovelAiStudioController(
            app.jsonFileStorage, app.characterResourceStore, UnconfiguredDesktopFilePicker,
            app.dataOperationCoordinator, { if (it.isNotEmpty()) cleanup(it) }, app.novelAiGenerationRuntime,
            DesktopNovelAiGuidance(app.appDataRoot, app.characterResourceStore, secrets), app.taskRuntime,
            app.novelAiInfrastructure, app.settingsRepository, app.effectiveModelResolver,
            app.characterRepository, account = { error("No account request permitted") }, credentialConfigured = { false })
        suspend fun close() { app.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `ordinary parameter edits finish without waiting for unrelated image cleanup`() = runBlocking {
        val f = Fixture()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleanupCalls = 0
        val controller = f.controller { cleanupCalls++; releaseCleanup.await() }
        try {
            controller.load()
            val guidance = NovelAiImageGuidanceDraft(baseImage = NovelAiStudioAssetRef(path = "images/kept.png"))
            controller.repository.updateDraft { it.copy(imageGuidance = guidance) }
            controller.repository.saveUndoDraft(controller.draft.value!!)
            controller.repository.saveGuidanceCheckpoint(guidance)
            val edits = launch {
                for (steps in 29..50) assertTrue(controller.edit {
                    it.withActiveSettings(it.activeSettings.copy(steps = steps, guidance = 6.5f, cfgRescale = .05f))
                })
            }
            try {
                assertNotNull(withTimeoutOrNull(5_000) { edits.join(); true },
                    "Pure parameter edits were queued behind image cleanup")
                assertEquals(0, cleanupCalls)
                assertEquals(50, controller.draft.value!!.activeSettings.steps)
                val reloaded = NovelAiStudioStateRepository(com.example.chatbar.data.local.JsonFileStorage(f.app.appDataRoot)).loadDraft()
                assertEquals(controller.draft.value, reloaded)
                assertEquals(guidance, reloaded.imageGuidance)
                assertEquals(guidance, controller.repository.loadUndoDraft()!!.imageGuidance)
                assertEquals(guidance, controller.repository.loadGuidanceCheckpoint())
            } finally { releaseCleanup.complete(Unit); edits.join() }
        } finally { releaseCleanup.complete(Unit); f.close() }
    }

    @Test fun `removing guidance still checks old image candidates after the draft is durable`() = runBlocking {
        val f = Fixture()
        val calls = mutableListOf<List<String>>()
        val controller = f.controller { candidates ->
            val saved = NovelAiStudioStateRepository(com.example.chatbar.data.local.JsonFileStorage(f.app.appDataRoot)).loadDraft()
            assertNull(saved.imageGuidance.baseImage)
            calls += candidates
        }
        try {
            controller.load()
            controller.repository.updateDraft { it.copy(imageGuidance = NovelAiImageGuidanceDraft(
                baseImage = NovelAiStudioAssetRef(path = "images/old.png"))) }
            assertTrue(controller.edit { it.copy(imageGuidance = it.imageGuidance.copy(baseImage = null)) })
            assertEquals(listOf(listOf("images/old.png")), calls)
        } finally { f.close() }
    }

    @Test fun `rejected parameter persistence restores the prior draft after a committed root switch`() = runBlocking {
        val f = Fixture()
        val controller = f.controller { error("No cleanup permitted") }
        try {
            controller.load()
            val before = controller.draft.value
            f.app.dataOperationCoordinator.withExclusiveMaintenance { requireRestart() }
            assertFalse(controller.edit { it.withActiveSettings(it.activeSettings.copy(steps = 50)) })
            assertEquals(before, controller.draft.value)
            assertEquals(DesktopDataOperationCoordinatorState.RESTART_REQUIRED, f.app.dataOperationCoordinator.state)
        } finally { f.close() }
    }
}
