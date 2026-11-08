@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.model.*
import com.example.chatbar.domain.worldbook.*
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DesktopRetainedAiModelChoicesTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private class Fixture {
        val parent = Files.createTempDirectory("retained-ai-models-")
        val secrets = InMemoryDesktopSecretStore()
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { secrets }, bundledAssets = { path ->
            check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
        })
        val first = ModelConfig("first", "First", "https://fixture.invalid/v1", "synthetic-first-key", "first", createdAt = 1)
        val second = first.copy(id = "second", displayName = "Second", modelName = "second")
        val vision = first.copy(id = "vision", displayName = "Vision", isMultimodal = true, selectableForChat = false)
        val card = CharacterCard("card", "Fixture", characters = listOf(CharacterInfo(name = "Person", profile = "Profile")), createdAt = 1, updatedAt = 1)
        val calls = CopyOnWriteArrayList<String>()
        suspend fun initialize() {
            app.modelRepository.saveModel(first); app.modelRepository.saveModel(second); app.modelRepository.saveModel(vision)
            app.settingsRepository.updateAppSettings { it.copy(defaultModelId = "first") }
            app.characterRepository.save(card)
        }
        suspend fun close() { app.close(); parent.toFile().deleteRecursively() }
    }
    private suspend fun ImageComposeScene.until(predicate: () -> Boolean) = withTimeout(15000) {
        while (!predicate()) { repeat(3) { render(System.nanoTime()).close(); delay(10) } }
        render(System.nanoTime()).close()
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(value: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription) == listOf(value)
    private fun ImageComposeScene.captureReview(name: String) {
        System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
            val path = java.nio.file.Path.of(directory).resolve("retained-ai-$name.png"); Files.createDirectories(path.parent)
            render(System.nanoTime()).use { it.encodeToData()!!.use { data -> Files.write(path, data.bytes) } }
        }
    }

    @Test fun `character overlay updates same-id capabilities defaults and deleted choices while keeping candidate and source inputs`() = runBlocking(edt) {
        val f = Fixture()
        try {
            f.initialize(); val editor = f.app.characterEditorController; editor.openExisting("card")
            val ai = DesktopCharacterCardAiController(editor, f.app.settingsRepository, f.app.effectiveModelResolver,
                f.app.taskRuntime, f.app.dataOperationCoordinator, stream = { _, _, _, model, _ ->
                    f.calls += model.id
                    flowOf(StreamEvent.Delta("""{"characters":[{"targetIndex":0,"appearance":"New appearance"}]}"""), StreamEvent.Done)
                }).also { editor.cardAi = it }
            ai.open(CharacterCardAiOperation.FILL); withTimeout(10000) { ai.state.first { !it.loading } }
            ai.setRequest("Retained character request"); ai.selectModel("first")
            val scene = ImageComposeScene(1100, 900) { DesktopCharacterCardAiOverlay(ai) }
            try {
                scene.until { ai.state.value.referenceImageRoute == DesktopReferenceImageRoute.UNAVAILABLE }
                ai.generate(); scene.until { !ai.state.value.busy && ai.state.value.candidate != null }
                val candidate = assertNotNull(ai.state.value.candidate)
                val before = editor.state.value.card
                f.app.modelRepository.saveModel(f.first.copy(displayName = "Renamed first", visionModelId = "vision"))
                scene.until { ai.state.value.referenceImageRoute == DesktopReferenceImageRoute.LINKED &&
                    ai.state.value.models.any { it.name.contains("Renamed first") } }
                assertTrue(ai.state.value.referenceImageModelName.contains("Vision"))
                f.app.modelRepository.saveModel(f.first.copy(displayName = "Direct first", isMultimodal = true))
                scene.until { ai.state.value.referenceImageRoute == DesktopReferenceImageRoute.DIRECT }
                assertTrue(scene.nodes().any { it.label(DesktopUiStrings(DesktopUiLanguage.ZH_CN)(DesktopUiText.REFERENCE_IMAGE_DIRECT)) })
                f.app.settingsRepository.updateAppSettings { it.copy(defaultModelId = "second", characterAutoFillResearchSourceMode = CharacterResearchSourceMode.MANUAL_URLS) }
                scene.until { ai.state.value.defaultModel.contains("Second") }
                scene.captureReview("character")
                f.app.modelRepository.deleteModel("first")
                scene.until { ai.state.value.referenceImageRoute == DesktopReferenceImageRoute.NO_MODEL }
                assertEquals("first", ai.state.value.modelId)
                assertEquals("Retained character request", ai.state.value.request)
                assertEquals(CharacterResearchSourceMode.NONE, ai.state.value.sourceMode)
                assertEquals(candidate, ai.state.value.candidate); assertEquals(before, editor.state.value.card)
                assertEquals(listOf("first"), f.calls)
                assertFalse(ai.state.value.toString().contains("synthetic-first-key"))
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `worldbook overlay refreshes retained models without resetting candidates source mode or selected targets`() = runBlocking(edt) {
        val f = Fixture()
        try {
            f.initialize()
            val book = WorldBook("book", "Fixture", entries = listOf(WorldBookEntry("target", "Blank", listOf("key"), "")))
            f.app.worldBookRepository.save(book)
            val editor = f.app.worldBookEditorController; editor.openExisting("book")
            val ai = DesktopWorldBookAiController(editor, f.app.settingsRepository, f.app.effectiveModelResolver,
                f.app.taskRuntime, f.app.dataOperationCoordinator, stream = { _, _, _, model, _ ->
                    f.calls += model.id
                    flowOf(StreamEvent.Delta("""{"entries":[{"name":"Mountain","keys":["peak"]}],"hasMore":false}"""), StreamEvent.Done)
                }, research = { worldBookOfflineResearch }).also { editor.ai = it }
            ai.open(WorldBookAiOperation.CREATE); withTimeout(10000) { ai.state.first { !it.loading } }
            ai.setRequest("Retained world request"); ai.selectModel("first")
            val scene = ImageComposeScene(1100, 900) { DesktopWorldBookAiPanel(ai) }
            try {
                ai.generate(); scene.until { !ai.state.value.busy && ai.state.value.plans.isNotEmpty() }
                val before = ai.state.value
                f.app.modelRepository.saveModel(f.first.copy(displayName = "Renamed world model"))
                scene.until { ai.state.value.models.any { it.name.contains("Renamed world model") } }
                f.app.settingsRepository.updateAppSettings { it.copy(defaultModelId = "second", worldBookAiResearchSourceMode = CharacterResearchSourceMode.MANUAL_URLS) }
                scene.until { ai.state.value.defaultModel.contains("Second") }
                scene.captureReview("worldbook")
                f.app.modelRepository.deleteModel("first")
                scene.until { ai.state.value.models.none { it.id == "first" } }
                val after = ai.state.value
                assertEquals("first", after.modelId); assertEquals(before.request, after.request)
                assertEquals(before.plans, after.plans); assertEquals(before.selectedIds, after.selectedIds)
                assertEquals(before.targetIds, after.targetIds); assertEquals(before.sourceMode, after.sourceMode)
                assertEquals(before.outputs, after.outputs); assertEquals(book, editor.state.value.book)
                assertEquals(listOf("first"), f.calls)
                assertFalse(after.toString().contains("synthetic-first-key"))
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `format overlay retains requested character and candidate through model rename default switch and deletion`() = runBlocking(edt) {
        val f = Fixture()
        try {
            f.initialize(); val editor = f.app.formatCardEditorController; editor.openNew()
            val ai = DesktopFormatCardAutoFillController(editor, f.app.characterRepository, f.app.settingsRepository,
                f.app.effectiveModelResolver, f.app.taskRuntime, f.app.dataOperationCoordinator, stream = { _, _, model, _ ->
                    f.calls += model.id
                    flowOf(StreamEvent.Delta("""{"name":"Fixture guide","content":"A complete fixture guide","userTools":[]}"""), StreamEvent.Done)
                }).also { editor.autoFill = it }
            ai.open(); withTimeout(10000) { ai.state.first { !it.loadingOptions } }
            ai.selectCharacter("card"); ai.selectModel("first"); ai.setRequest("Retained format request")
            val scene = ImageComposeScene(1100, 900) { DesktopFormatCardAutoFillOverlay(ai) }
            try {
                ai.generate(); scene.until { !ai.state.value.busy && ai.state.value.candidate != null }
                val before = ai.state.value; val editorBefore = editor.state.value.card
                f.app.modelRepository.saveModel(f.first.copy(displayName = "Renamed format model"))
                scene.until { ai.state.value.models.any { it.name.contains("Renamed format model") } }
                f.app.settingsRepository.updateAppSettings { it.copy(defaultModelId = "second") }
                scene.until { ai.state.value.defaultModelName.contains("Second") }
                scene.captureReview("format")
                f.app.modelRepository.deleteModel("first")
                scene.until { ai.state.value.models.none { it.id == "first" } }
                val after = ai.state.value
                assertEquals("first", after.modelId); assertEquals("card", after.characterId)
                assertEquals(before.request, after.request); assertEquals(before.candidate, after.candidate)
                assertEquals(before.rawText, after.rawText); assertEquals(editorBefore, editor.state.value.card)
                assertEquals(listOf("first"), f.calls)
                assertFalse(after.toString().contains("synthetic-first-key"))
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `protected read failure is visible and explicit overlay retry keeps input and protected keys`() = runBlocking(edt) {
        val f = Fixture()
        try {
            f.initialize(); val editor = f.app.formatCardEditorController; editor.openNew()
            val cold = ModelRepository(JsonFileStorage(f.parent.resolve("data"), f.app.dataOperationCoordinator),
                WindowsSafeModelStorageKeyPolicy, DesktopModelCredentialPersistencePolicy(f.secrets, f.app.dataOperationCoordinator))
            val resolver = EffectiveModelResolver(cold, f.app.settingsRepository, object : PresetModelCatalogSource {
                override val catalog = PresetModelCatalog(chatModels = emptyList())
                override val modelCatalogVersion: Int? = null
            })
            val ai = DesktopFormatCardAutoFillController(editor, f.app.characterRepository, f.app.settingsRepository,
                resolver, f.app.taskRuntime, f.app.dataOperationCoordinator, stream = { _, _, _, _ -> error("No request during metadata recovery") })
                .also { editor.autoFill = it }
            val keys = f.secrets.values.toMap()
            f.secrets.loadFailure = IOException("synthetic protected read failure")
            ai.open(); withTimeout(10000) { ai.state.first { !it.loadingOptions } }
            ai.setRequest("Keep this unsaved request")
            val scene = ImageComposeScene(1100, 900) { DesktopFormatCardAutoFillOverlay(ai) }
            try {
                scene.until { ai.state.value.modelChoicesReadFailed }
                assertEquals(keys, f.secrets.values)
                f.secrets.loadFailure = null
                val retry = scene.nodes().single { it.label("重试读取") && it.config.contains(SemanticsActions.OnClick) }
                assertTrue(retry.config[SemanticsActions.OnClick].action!!.invoke())
                scene.until { !ai.state.value.modelChoicesReadFailed && ai.state.value.models.any { it.id == "first" } }
                assertEquals("Keep this unsaved request", ai.state.value.request)
                assertEquals(keys, f.secrets.values); assertTrue(f.calls.isEmpty())
            } finally { scene.close() }
        } finally { f.secrets.loadFailure = null; f.close() }
    }

    @Test fun `reference image route preserves explicit missing choice and never borrows an unrelated multimodal model`() {
        val model = ModelConfig("text", "Text", "https://fixture.invalid", "fake-text", "text", createdAt = 1)
        val vision = model.copy(id = "vision", displayName = "Vision", isMultimodal = true, selectableForChat = false)
        fun configuration(text: ModelConfig, linked: ModelConfig = vision) = EffectiveModelConfiguration(
            AppSettings(defaultModelId = "text"), listOf(text), listOf(text, linked), listOf(linked), null)
        assertEquals(DesktopReferenceImageRoute.UNAVAILABLE, desktopAiModelChoices(configuration(model), null).referenceRoute)
        assertEquals(DesktopReferenceImageRoute.NO_MODEL, desktopAiModelChoices(configuration(model), "deleted").referenceRoute)
        assertEquals(DesktopReferenceImageRoute.DIRECT, desktopAiModelChoices(configuration(model.copy(isMultimodal = true)), null).referenceRoute)
        assertEquals(DesktopReferenceImageRoute.LINKED, desktopAiModelChoices(configuration(model.copy(visionModelId = "vision")), null).referenceRoute)
        assertEquals(DesktopReferenceImageRoute.UNAVAILABLE, desktopAiModelChoices(configuration(model.copy(visionModelId = "vision"), vision.copy(apiKey = "")), null).referenceRoute)
        assertFalse(desktopAiModelChoices(configuration(model), null).toString().contains("fake-text"))
    }
}
