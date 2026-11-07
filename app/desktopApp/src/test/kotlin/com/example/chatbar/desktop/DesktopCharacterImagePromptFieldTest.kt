@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class DesktopCharacterImagePromptFieldTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun ComposeWindow.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.has(text: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true || children.any { it.has(text) }
    private fun ComposeWindow.action(label: String) = nodes().firstOrNull { it.has(label) && it.config.contains(SemanticsActions.OnClick) }
    private fun ComposeWindow.field() = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("图片提示词") == true &&
        it.config.contains(SemanticsActions.SetText) }
    private fun ComposeWindow.setText(text: String) { assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))) }

    @Test fun `actual person prompt offers local tags and expanded draft isolation without rewriting another person or card`() = runBlocking(edt) {
        requireDesktopGraphicsForNativeTest()
        val parent = Files.createTempDirectory("character-image-prompt-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                if (path.startsWith("presets/")) """{"entries":[]}""".toByteArray() else DesktopBundledAssetReader()(path)
            })
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val translated = MutableStateFlow(false)
        val catalog = object : NovelAiCompletionCatalog {
            override val completionVersion = MutableStateFlow("synthetic")
            override suspend fun prepareCompletion() = Unit
            override suspend fun streamCompletion(query: String, onCandidate: suspend (NovelAiTagCandidate) -> Unit, onWarning: (String) -> Unit) {
                if (query.startsWith("blu")) onCandidate(NovelAiTagCandidate("blue_eyes", "蓝眼睛", 50, NovelAiTagCategory.GENERAL))
            }
        }
        val lookup = object : NovelAiTagLookup {
            override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList())
            override suspend fun exactChineseTranslations(names: Collection<String>) = names.filter { it == "blue_eyes" }.associateWith { "蓝眼睛" }
            override suspend fun catalogMetadata() = DanbooruCatalogMetadata("synthetic", "", 1)
        }
        val dictionary = NovelAiPromptWordDictionary.fromTsv("blue_eyes\t蓝眼睛\n".byteInputStream())
        val tools = DesktopCharacterPromptTools(NovelAiTagSuggestionService(catalog, dictionary, localScope),
            NovelAiPromptTranslationService(dictionary, lookup), translated, { translated.value = it })
        val controller = app.characterEditorController
        assertNotNull(controller.imagePromptTools, "Production container must wire the real character entry")
        controller.imagePromptTools = { tools }
        val a = CharacterInfo.create("Synthetic A").copy(id = "a", imagePrompt = "red_hair", profile = "Keep A profile")
        val b = CharacterInfo.create("Synthetic B").copy(id = "b", imagePrompt = "green_eyes", profile = "Keep B profile")
        val card = CharacterCard("card", "Synthetic card", greeting = "Synthetic opening.", editMode = CharacterEditMode.STRUCTURED,
            characters = listOf(a, b), createdAt = 1, updatedAt = 1)
        var hostOpen by mutableStateOf(true)
        var scene: ImageComposeScene? = null
        val hostTitle = "CCB synthetic character prompt"
        suspend fun frames() { repeat(4) { scene!!.render(System.nanoTime()).close(); delay(20) } }
        suspend fun await(description: String, condition: () -> Boolean) {
            assertTrue(withTimeoutOrNull(15_000) { while (!condition()) frames(); true } == true, description)
        }
        fun windows(title: String) = java.awt.Window.getWindows().filterIsInstance<ComposeWindow>().filter { it.title == title && it.isDisplayable }
        fun shot(window: ComposeWindow, name: String) { System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { folder ->
            val target = Path.of(folder).resolve(name); Files.createDirectories(target.parent)
            val image = java.awt.Robot().createScreenCapture(window.bounds)
            try { javax.imageio.ImageIO.write(image, "png", target.toFile()) } finally { image.flush() }
        } }
        suspend fun expand(host: ComposeWindow): ComposeWindow {
            val field = host.field()
            val section = generateSequence(field) { it.parent }.first { node ->
                node.children.any { it.has("展开") } && node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "图片提示词" } != true
            }
            fun descendants(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descendants)
            val button = descendants(section).first { it.has("展开") && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
            await("Actual person prompt expanded window must appear") { windows("图片提示词").singleOrNull()?.action("取消") != null }
            return windows("图片提示词").single()
        }
        try {
            app.characterRepository.save(card)
            controller.openPerson(card.id, a.id)
            val originalSession = controller.state.value.draftSessionId!!
            scene = ImageComposeScene(1, 1, coroutineContext = edt) {
                if (hostOpen) Window(onCloseRequest = { hostOpen = false }, title = hostTitle,
                    state = rememberWindowState(width = 1000.dp, height = 720.dp)) {
                    val state by controller.state.collectAsState()
                    DesktopCharacterEditorOverlay(controller, state)
                }
            }
            await("Actual character editor prompt field must be available") { windows(hostTitle).singleOrNull()?.nodes()?.any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("图片提示词") == true && it.config.contains(SemanticsActions.SetText)
            } == true }
            val host = windows(hostTitle).single()
            val field = host.field()
            generateSequence(field) { it.parent }.lastOrNull { it.config.contains(SemanticsActions.ScrollBy) }
                ?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, 100000f)
            host.toFront(); host.requestFocus()
            assertTrue(host.field().config[SemanticsActions.RequestFocus].action!!.invoke())
            host.setText("blu")
            await("Local catalog candidate must be offered in the actual person field") { host.action("blue_eyes") != null }
            assertTrue(host.action("blue_eyes")!!.config[SemanticsActions.OnClick].action!!.invoke())
            await("Choosing a tag must update only person A") { controller.state.value.card!!.characters.first().imagePrompt == "blue_eyes" }
            assertEquals(b, controller.state.value.card!!.characters[1])
            assertEquals(a.profile, controller.state.value.card!!.characters[0].profile)
            assertTrue(host.action("中文注释")!!.config[SemanticsActions.OnClick].action!!.invoke())
            await("Translation choice must use the supplied shared display preference") { translated.value }
            frames(); shot(host, "character-person-prompt-tags.png")

            var expanded = expand(host)
            expanded.setText("cancelled local prompt")
            assertEquals("blue_eyes", controller.state.value.card!!.characters[0].imagePrompt)
            assertTrue(expanded.action("取消")!!.config[SemanticsActions.OnClick].action!!.invoke())
            await("Cancel must close only the expanded prompt") { windows("图片提示词").isEmpty() }
            assertEquals("blue_eyes", controller.state.value.card!!.characters[0].imagePrompt)
            expanded = expand(host); expanded.setText("blue_eyes, smile")
            frames(); shot(expanded, "character-person-prompt-expanded.png")
            assertTrue(expanded.action("确认")!!.config[SemanticsActions.OnClick].action!!.invoke())
            await("Confirm must publish the local prompt through the real controller") { controller.state.value.card!!.characters[0].imagePrompt == "blue_eyes, smile" }
            await("Confirmed prompt window must dispose") { windows("图片提示词").isEmpty() }
            val oldInline = host.field().config[SemanticsActions.SetText].action!!
            expanded = expand(host); expanded.setText("must not leak on person switch")
            controller.openPerson(card.id, b.id)
            await("Switching person must discard A's unconfirmed expanded editor") { windows("图片提示词").isEmpty() && host.field().config[SemanticsProperties.EditableText].text == b.imagePrompt }
            assertTrue(oldInline.invoke(AnnotatedString("stale A input"))); frames()
            assertEquals("blue_eyes, smile", controller.state.value.card!!.characters[0].imagePrompt)
            assertEquals(b, controller.state.value.card!!.characters[1])
            assertTrue(controller.save(), "Synthetic card save refused: ${controller.state.value.problem}")
            val saved = app.characterRepository.getById(card.id)!!
            assertEquals("blue_eyes, smile", saved.characters[0].imagePrompt); assertEquals(b, saved.characters[1])
            controller.openNew()
            controller.edit { it.copy(editMode = CharacterEditMode.STRUCTURED, characters = listOf(a.copy(imagePrompt = "replacement"), b)) }
            assertFalse(controller.updateCharacterImagePrompt(originalSession, card.id, a.id, "stale old card"))
            assertEquals("replacement", controller.state.value.card!!.characters[0].imagePrompt)
            assertTrue(app.taskRuntime.tasks.value.isEmpty())
        } finally {
            hostOpen = false; scene?.close(); repeat(3) { yield(); delay(20) }
            localScope.cancel(); app.close(); check(parent.toFile().deleteRecursively())
        }
    }
}
