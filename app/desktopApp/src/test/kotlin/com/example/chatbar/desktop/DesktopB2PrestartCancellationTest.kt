package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.card.CharacterCardAiOperation
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DesktopB2PrestartCancellationTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runQueued() { while (true) (queue.poll() ?: return).run() }
        suspend fun until(condition: () -> Boolean) = withTimeout(5_000) {
            while (!condition()) { runQueued(); yield(); delay(1) }
            runQueued()
        }
    }
    private val card = CharacterCard("card", "合成卡", greeting = "合成开场", characters = listOf(CharacterInfo("person", "合成人物", imagePrompt = "synthetic")),
        defaultImagePrompt = "synthetic", defaultImageNegativePrompt = "synthetic-negative", createdAt = 1, updatedAt = 1)
    private val model = ModelConfig("model", "合成模型", "http://127.0.0.1:1/v1", "fixture-key", "synthetic", isMultimodal = true, createdAt = 1)
    private val bytes get() = DesktopImageEditing.png(java.awt.image.BufferedImage(12, 12, java.awt.image.BufferedImage.TYPE_INT_RGB))

    @Test fun `card generation cancelled before dispatched body clears busy without requests and can retry`() = runBlocking {
        fixture { _, app, editor, _ ->
            val dispatcher = QueuedDispatcher(); val scope = CoroutineScope(SupervisorJob() + dispatcher)
            var calls = 0
            val ai = DesktopCharacterCardAiController(editor, app.settingsRepository, app.effectiveModelResolver,
                app.taskRuntime, app.dataOperationCoordinator, stream = { _, _, _, _, _ ->
                    calls++; flowOf(StreamEvent.Delta("""{"characters":[{"targetIndex":0,"appearance":"合成外貌"}]}"""), StreamEvent.Done)
                }, scope = scope).also { editor.cardAi = it }
            try {
            ai.open(CharacterCardAiOperation.FILL); dispatcher.until { !ai.state.value.loading }
            ai.setRequest("合成要求"); ai.generate(); assertTrue(ai.state.value.busy)
            ai.cancel(); dispatcher.runQueued()
            assertFalse(ai.state.value.busy); assertEquals(0, calls); assertNull(ai.state.value.candidate)
            assertEquals(card, editor.state.value.card); ai.close(); assertFalse(ai.state.value.open)
            ai.open(CharacterCardAiOperation.FILL); dispatcher.until { !ai.state.value.loading }
            ai.generate(); dispatcher.until { !ai.state.value.busy }
            assertEquals(1, calls); assertNotNull(ai.state.value.candidate)
            } finally { scope.cancel(); dispatcher.until { scope.coroutineContext[Job]!!.isCompleted } }
        }
    }

    @Test fun `appearance cancelled before body neither reads image nor requests and can retry`() = runBlocking {
        fixture { _, app, editor, picker ->
            val dispatcher = QueuedDispatcher(); val scope = CoroutineScope(SupervisorJob() + dispatcher)
            var calls = 0
            val ai = DesktopCharacterAppearanceController(editor, app.settingsRepository, app.effectiveModelResolver,
                app.taskRuntime, app.dataOperationCoordinator, picker, stream = { _, _, _ ->
                    calls++; flowOf(StreamEvent.Delta("""{"appearance":"合成外貌","clothing":""}"""), StreamEvent.Done)
                }, scope = scope).also { editor.appearanceAi = it }
            try {
            ai.open("person"); assertTrue(ai.state.value.busy); ai.cancel(); dispatcher.runQueued()
            assertFalse(ai.state.value.busy); assertEquals(0, calls); assertNull(ai.state.value.preview)
            assertEquals(card, editor.state.value.card)
            ai.generate(); dispatcher.until { !ai.state.value.busy }
            assertEquals(1, calls); assertNotNull(ai.state.value.candidate)
            ai.close(); assertFalse(ai.state.value.open)
            } finally { scope.cancel(); dispatcher.until { scope.coroutineContext[Job]!!.isCompleted } }
        }
    }

    @Test fun `image generation and apply prestart cancellation recover preserving completed candidate for apply retry`() = runBlocking {
        fixture { root, app, editor, _ ->
            val dispatcher = QueuedDispatcher(); val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val secrets = InMemoryDesktopSecretStore(); var calls = 0
            val ai = DesktopCharacterImageController(editor, app.settingsRepository, app.effectiveModelResolver,
                app.taskRuntime, app.dataOperationCoordinator,
                captureCredential = { DesktopCredentialCall(secrets, DesktopCredentialKey.NovelAiToken, "synthetic", "fixture-key") },
                loadRequirement = { "" }, design = { _, _, _, _, _ -> error("No avatar design") },
                generateImage = { _, _, _, _, _ -> calls++; bytes }, scope = scope).also { editor.cardImages = it }
            try {
            ai.openAvatar("person"); ai.generate(); ai.cancel(); dispatcher.runQueued()
            assertFalse(ai.state.value.busy); assertEquals(0, calls); assertEquals(card, editor.state.value.card)
            ai.generate(); dispatcher.until { !ai.state.value.busy }
            val candidate = assertNotNull(ai.state.value.candidate); assertEquals(1, calls)
            ai.apply(); assertTrue(ai.state.value.busy); ai.cancel(); dispatcher.runQueued()
            assertFalse(ai.state.value.busy); assertSame(candidate, ai.state.value.candidate)
            assertEquals(card, editor.state.value.card); assertFalse(Files.exists(root.resolve("draft_assets")))
            ai.apply(); dispatcher.until { !ai.state.value.busy }
            assertFalse(ai.state.value.open); assertNotNull(editor.state.value.card!!.characters.single().appearanceImage)
            assertEquals(1, calls)
            } finally { scope.cancel(); dispatcher.until { scope.coroutineContext[Job]!!.isCompleted } }
        }
    }

    private suspend fun fixture(block: suspend (Path, DesktopAppContainer, DesktopCharacterEditorController, DesktopFilePicker) -> Unit) {
        val parent = Files.createTempDirectory("b2-prestart-").toAbsolutePath().normalize()
        val root = parent.resolve("data")
        val source = parent.resolve("synthetic.png"); Files.write(source, bytes)
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType) = source
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("No export")
        }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            app.characterRepository.save(card); app.modelRepository.saveModel(model)
            app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true, defaultModelId = model.id) }
            val editor = app.characterEditorController; editor.openExisting(card.id)
            block(root, app, editor, picker)
        } finally {
            app.close()
            check(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            parent.toFile().deleteRecursively()
        }
    }
}
