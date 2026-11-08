@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.chatbar.domain.image.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class DesktopStudioImportedAnnotationTest {
    private val edt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private suspend fun ImageComposeScene.frames() { repeat(6) { render(System.nanoTime()).close(); delay(15) } }
    private fun ImageComposeScene.pixels() = render(System.nanoTime()).use { image -> image.encodeToData()!!.use { it.bytes.contentHashCode() } }

    @Test fun `authoritative import refreshes retained annotations even for same visible prompt without toggling or changing selection`() = runBlocking(edt) {
        val f = FinalProductDesignFixture(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ready = AtomicBoolean(false); val lookups = AtomicInteger()
        val source = "synthetic_import_tag"
        val lookup = object : NovelAiTagLookup {
            override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList())
            override suspend fun exactChineseTranslations(names: Collection<String>): Map<String,String> {
                lookups.incrementAndGet()
                return if (ready.get()) names.associateWith { "合成标签注释" } else emptyMap()
            }
            override suspend fun catalogMetadata() = DanbooruCatalogMetadata("synthetic", "", 1)
        }
        val dictionary = NovelAiPromptWordDictionary.fromTsv("unrelated\t无关\n".byteInputStream())
        val catalog = object : NovelAiCompletionCatalog {
            override val completionVersion = MutableStateFlow("synthetic")
            override suspend fun prepareCompletion() = Unit
            override suspend fun streamCompletion(query: String, onCandidate: suspend (NovelAiTagCandidate) -> Unit, onWarning: (String) -> Unit) = Unit
        }
        val suggestions = NovelAiTagSuggestionService(catalog, dictionary, scope)
        val translations = NovelAiPromptTranslationService(dictionary, lookup)
        try {
            f.initialize(); val c = f.container.novelAiStudioController
            val originalPrompt = c.draft.value!!.basePrompt
            assertTrue(c.replace { it.copy(basePrompt = source) })
            val scene = ImageComposeScene(500, 160) {
                val draft by c.draft.collectAsState()
                draft?.let { d -> CompositionLocalProvider(LocalDesktopStudioTextHistories provides c.textHistories,
                    LocalDesktopPromptAnnotationRevision provides d.promptContentRevision) {
                    StudioPromptField("导入注释", d.basePrompt, suggestions, translations, true) {}
                } }
            }
            try {
                withTimeout(5000) { while (lookups.get() < 1) scene.frames() }; scene.frames()
                val field = c.textHistories.field("studio:导入注释", source)
                field.edit(TextFieldValue(source, TextRange(2))) {}
                scene.frames(); val beforeInput = field.value; val before = scene.pixels()
                ready.set(true)
                val oldRevision = c.textHistories.annotationRevision
                assertTrue(c.replace { it.copy(basePrompt = source) })
                assertTrue(c.textHistories.annotationRevision > oldRevision)
                withTimeout(5000) { while (lookups.get() < 2) scene.frames() }; scene.frames()
                assertNotEquals(before, scene.pixels())
                assertEquals(beforeInput, field.value)
                assertEquals(source, c.draft.value!!.basePrompt)
                assertTrue(c.undo()); scene.frames()
                assertEquals(originalPrompt, c.draft.value!!.basePrompt)
                assertTrue(f.requests.isEmpty()); assertTrue(f.container.taskRuntime.tasks.value.isEmpty())
            } finally { scene.close() }
        } finally { scope.cancel(); f.close() }
    }
}
