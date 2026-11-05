@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
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
        try {
            for (translated in listOf(false, true)) for (label in listOf("基础 Prompt", "角色正向")) {
                val original = (1..120).joinToString("\n") { "blue sky, synthetic_line_$it," }
                var raw by mutableStateOf(original)
                val scene = ImageComposeScene(1100, 650, coroutineContext = edt) {
                    DesktopStudioWorkspace(compact = {}, editor = {
                        Spacer(Modifier.height(400.dp))
                        StudioPromptField(label, raw, suggestions, translations, translated, compact = label == "角色正向") { raw = it }
                        Spacer(Modifier.height(1200.dp))
                    }, footer = { StudioAction("Synthetic other focus") {} }, preview = {})
                }
                try {
                    scene.frames()
                    fun outer() = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Studio Prompt 编辑区") == true }
                        .let { n -> generateSequence(n) { it.parent }.first { it.config.contains(SemanticsActions.ScrollBy) } }
                    fun field() = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.SetText) }
                    outer().config[SemanticsActions.ScrollBy].action!!.invoke(0f, 300f)
                    scene.frames(30)
                    val before = outer().config[SemanticsProperties.VerticalScrollAxisRange].value()
                    val rect = field().boundsInRoot
                    assertTrue(rect.top > 0 && rect.bottom < 650, "Fixture must expose the complete field viewport")
                    scene.sendPointerEvent(PointerEventType.Press, Offset(rect.left + 45f, rect.top + 55f), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, Offset(rect.left + 45f, rect.top + 55f), button = PointerButton.Primary)
                    scene.frames(35)
                    val after = outer().config[SemanticsProperties.VerticalScrollAxisRange].value()
                    val selection = field().config[SemanticsProperties.TextSelectionRange]
                    observations += "$label translated=$translated outerBefore=$before outerAfter=$after viewport=$rect selection=$selection"
                    if (abs(after - before) > 2f) failures += observations.last()
                    assertEquals(original, raw)
                    assertTrue(selection.collapsed && selection.end in 1 until original.length)
                    val inner = generateSequence(field()) { it.parent }.first { it.config.contains(SemanticsActions.ScrollBy) }
                    assertNotEquals(outer().id, inner.id, "Inner editor scroll remains independent")
                    val innerBefore = inner.config[SemanticsProperties.VerticalScrollAxisRange].value()
                    inner.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 360f)
                    scene.frames(25)
                    val innerAfter = inner.config[SemanticsProperties.VerticalScrollAxisRange].value()
                    assertTrue(innerAfter > innerBefore, "Long text must remain scrollable")
                    assertEquals(after, outer().config[SemanticsProperties.VerticalScrollAxisRange].value(), 2f)
                    assertEquals(original, raw)
                    val other = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Synthetic other focus" } == true }
                        .let { n -> generateSequence(n) { it.parent }.first { it.config.contains(SemanticsActions.RequestFocus) } }
                    other.config[SemanticsActions.RequestFocus].action!!.invoke()
                    outer().config[SemanticsActions.ScrollBy].action!!.invoke(0f, -1000f)
                    scene.frames(25)
                    val outsideBefore = outer().config[SemanticsProperties.VerticalScrollAxisRange].value()
                    field().config[SemanticsActions.RequestFocus].action!!.invoke()
                    scene.frames(35)
                    assertTrue(outer().config[SemanticsProperties.VerticalScrollAxisRange].value() > outsideBefore,
                        "An off-screen editor must still be brought into view")
                    assertEquals(rect.height, field().boundsInRoot.height, 2f)
                    assertEquals(original, raw)
                } finally { scene.close() }
            }
            val destination = System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { Path.of(it).resolve("long-prompt-focus.txt") }
                ?: Path.of("build", "long-prompt-focus.txt")
            Files.createDirectories(destination.parent)
            Files.writeString(destination, observations.joinToString("\n"))
            assertTrue(failures.isEmpty(), "Visible-field focus moved outer viewport:\n${failures.joinToString("\n")}")
        } finally { scope.cancel() }
    }
}
