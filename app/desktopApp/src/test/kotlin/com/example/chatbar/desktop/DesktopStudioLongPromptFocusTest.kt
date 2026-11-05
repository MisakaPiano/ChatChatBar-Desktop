@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.test.*

class DesktopStudioLongPromptFocusTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames(count: Int = 12) {
        repeat(count) { render(System.nanoTime()).close(); delay(18) }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }

    @Test fun `clicking visible long prompt preserves outer viewport while caret selection and inner scrolling remain usable`(): Unit = runBlocking(edt) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val catalog = object : NovelAiCompletionCatalog {
            override val completionVersion = MutableStateFlow("synthetic")
            override suspend fun prepareCompletion() = Unit
            override suspend fun streamCompletion(query: String, onCandidate: suspend (NovelAiTagCandidate) -> Unit, onWarning: (String) -> Unit) = Unit
        }
        val lookup = object : NovelAiTagLookup {
            override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList())
            override suspend fun exactChineseTranslations(names: Collection<String>) = mapOf("blue sky" to "蓝天")
            override suspend fun catalogMetadata() = DanbooruCatalogMetadata("synthetic", "", 1)
        }
        val dictionary = NovelAiPromptWordDictionary.fromTsv("blue sky\t蓝天\n".byteInputStream())
        val suggestions = NovelAiTagSuggestionService(catalog, dictionary, scope)
        val translations = NovelAiPromptTranslationService(dictionary, lookup)
        val observations = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val destination = System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { Path.of(it).resolve("long-prompt-focus.txt") }
            ?: Path.of("build", "long-prompt-focus.txt")
        fun observe(text: String) {
            observations += text
            Files.createDirectories(destination.parent)
            Files.writeString(destination, observations.joinToString("\n"))
        }
        try {
            for (translated in listOf(false, true)) for (label in listOf("基础 Prompt", "角色正向")) {
                val original = (1..120).joinToString("\n") { "blue sky, synthetic_line_$it," }
                var raw by mutableStateOf(original)
                var inputRequest: PlatformTextInputMethodRequest? = null
                val capture = PlatformTextInputInterceptor { current, _ -> inputRequest = current; awaitCancellation() }
                val scene = ImageComposeScene(1100, 650, coroutineContext = edt) {
                    InterceptPlatformTextInput(capture) {
                    DesktopStudioWorkspace(compact = {}, editor = {
                        Spacer(Modifier.height(800.dp))
                        StudioPromptField(label, raw, suggestions, translations, translated, compact = label == "角色正向") { raw = it }
                        Spacer(Modifier.height(1200.dp))
                    }, footer = { StudioAction("Synthetic other focus") {} }, preview = {})
                    }
                }
                try {
                    scene.frames()
                    fun outer() = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Studio Prompt 编辑区") == true }
                        .let { n -> generateSequence(n) { it.parent }.first { it.config.contains(SemanticsActions.ScrollBy) } }
                    fun field() = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.SetText) }
                    fun assertCaretVisible() {
                        val caret = assertNotNull(assertNotNull(inputRequest).focusedRectInRoot())
                        val viewport = field().boundsInRoot
                        assertTrue(caret.top >= viewport.top - 1f && caret.bottom <= viewport.bottom + 1f,
                            "Actual source/IME caret=$caret outside editor viewport=$viewport")
                    }
                    outer().config[SemanticsActions.ScrollBy].action!!.invoke(0f, 700f)
                    scene.frames(30)
                    val before = outer().config[SemanticsProperties.VerticalScrollAxisRange].value()
                    val rect = field().boundsInRoot
                    assertTrue(rect.top > 0 && rect.bottom < 650, "Fixture must expose the complete field viewport")
                    scene.sendPointerEvent(PointerEventType.Press, Offset(rect.left + 45f, rect.top + 55f), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, Offset(rect.left + 45f, rect.top + 55f), button = PointerButton.Primary)
                    scene.frames(35)
                    val after = outer().config[SemanticsProperties.VerticalScrollAxisRange].value()
                    val selection = field().config[SemanticsProperties.TextSelectionRange]
                    observe("$label translated=$translated outerBefore=$before outerAfter=$after viewport=$rect selection=$selection")
                    if (abs(after - before) > 2f) failures += observations.last()
                    assertEquals(original, raw)
                    assertTrue(selection.collapsed && selection.end in 1 until original.length)
                    assertCaretVisible()
                    val ancestry = generateSequence(field()) { it.parent }.toList()
                    observe(ancestry.joinToString("\n") { n ->
                        val axis = n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                        "ancestor=${n.id} scrollBy=${n.config.contains(SemanticsActions.ScrollBy)} vertical=${axis?.value?.invoke()}/${axis?.maxValue?.invoke()} bounds=${n.boundsInRoot}"
                    })
                    val inner = generateSequence(field().parent) { it.parent }.first {
                        it.id != outer().id && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke()?.let { max -> max > 0 } == true
                    }
                    assertNotEquals(outer().id, inner.id, "Inner editor scroll remains independent")
                    val innerBefore = inner.config[SemanticsProperties.VerticalScrollAxisRange].value()
                    assertTrue(inner.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() - innerBefore > 600f,
                        "Click near start must not relocate long content to its end")
                    scene.sendPointerEvent(PointerEventType.Scroll, rect.center, scrollDelta = Offset(0f, 5f))
                    scene.frames(25)
                    val innerAfter = inner.config[SemanticsProperties.VerticalScrollAxisRange].value()
                    observe("wheel innerBefore=$innerBefore innerAfter=$innerAfter outer=${outer().config[SemanticsProperties.VerticalScrollAxisRange].value()}")
                    assertTrue(innerAfter > innerBefore, "Long text must remain scrollable")
                    assertEquals(after, outer().config[SemanticsProperties.VerticalScrollAxisRange].value(), 2f)
                    assertEquals(original, raw)
                    scene.sendKeyEvent(KeyEvent(Key.MoveEnd, KeyEventType.KeyDown, isCtrlPressed = true))
                    scene.sendKeyEvent(KeyEvent(Key.MoveEnd, KeyEventType.KeyUp, isCtrlPressed = true))
                    scene.frames(35)
                    assertEquals(original.length, field().config[SemanticsProperties.TextSelectionRange].end)
                    assertTrue(inner.config[SemanticsProperties.VerticalScrollAxisRange].value() > innerAfter,
                        "Keyboard navigation to the end must reveal the caret inside the field")
                    assertEquals(after, outer().config[SemanticsProperties.VerticalScrollAxisRange].value(), 2f)
                    assertCaretVisible()
                    val other = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Synthetic other focus" } == true }
                        .let { n -> generateSequence(n) { it.parent }.first { it.config.contains(SemanticsActions.RequestFocus) } }
                    other.config[SemanticsActions.RequestFocus].action!!.invoke()
                    inner.config[SemanticsActions.ScrollBy].action!!.invoke(0f, -inner.config[SemanticsProperties.VerticalScrollAxisRange].value())
                    scene.frames(25)
                    val reclick = field().boundsInRoot
                    scene.sendPointerEvent(PointerEventType.Press, Offset(reclick.left + 45f, reclick.top + 55f), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, Offset(reclick.left + 45f, reclick.top + 55f), button = PointerButton.Primary)
                    scene.frames(35)
                    assertTrue(field().config[SemanticsProperties.TextSelectionRange].end in 1..200,
                        "Click must select visible source text instead of the previous end caret")
                    assertEquals(after, outer().config[SemanticsProperties.VerticalScrollAxisRange].value(), 2f)
                    assertEquals(original, raw)
                    assertCaretVisible()
                    other.config[SemanticsActions.RequestFocus].action!!.invoke()
                    outer().config[SemanticsActions.ScrollBy].action!!.invoke(0f, -1000f)
                    scene.frames(25)
                    val outsideBefore = outer().config[SemanticsProperties.VerticalScrollAxisRange].value()
                    field().config[SemanticsActions.RequestFocus].action!!.invoke()
                    scene.frames(35)
                    assertTrue(outer().config[SemanticsProperties.VerticalScrollAxisRange].value() > outsideBefore,
                        "An off-screen editor must still be brought into view")
                    assertCaretVisible()
                    assertEquals(original, raw)
                } finally { scene.close() }
            }
            assertTrue(failures.isEmpty(), "Visible-field focus moved outer viewport:\n${failures.joinToString("\n")}")
        } finally { scope.cancel() }
    }
}
