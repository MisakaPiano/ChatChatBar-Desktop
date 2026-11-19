@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.chatbar.domain.image.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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

    @Test fun `editor retries initial and persistent reads at most once without altering the prompt`(): Unit = runBlocking(edt) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retryStarted = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        val recovered = AtomicBoolean(false)
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val firstTag = "qzxktransient"
        val brokenTag = "qzxkpersistent"
        val firstSource = "blue eyes, $firstTag"
        val oldTag = "qzxkabandoned"
        val replacementTag = "qzxkreplacement"
        val oldRetryStarted = CompletableDeferred<Unit>()
        val oldRetryCancelled = CompletableDeferred<Unit>()
        val lookup = object : NovelAiTagLookup {
            override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList())
            override suspend fun catalogMetadata() = DanbooruCatalogMetadata("synthetic", "", 1)
            override suspend fun exactChineseTranslations(names: Collection<String>): Map<String, String> {
                val last = names.last()
                val invocation = calls.computeIfAbsent(last) { AtomicInteger() }.incrementAndGet()
                if (last == firstTag && invocation == 2) {
                    retryStarted.complete(Unit)
                    finishRetry.await()
                    recovered.set(true)
                }
                if (last == oldTag && invocation == 2) {
                    oldRetryStarted.complete(Unit)
                    try { awaitCancellation() } finally { oldRetryCancelled.complete(Unit) }
                }
                return if (last == replacementTag) mapOf(last to "新词", "blue_eyes" to "蓝眼")
                else mapOf("blue_eyes" to "蓝眼")
            }
        }
        val dictionary = NovelAiPromptWordDictionary.fromSource(object : NovelAiDictionarySource {
            override fun lookup(word: String): String? = when (word) {
                firstTag -> if (recovered.get()) "恢复的注释" else throw IOException("fixture transient")
                brokenTag, oldTag -> throw IOException("fixture unavailable")
                else -> null
            }
            override fun search(query: String) = emptyList<NovelAiTagCandidate>()
            override suspend fun prepareCompletion() = Unit
            override suspend fun streamCompletion(query: String, onCandidate: suspend (NovelAiTagCandidate) -> Unit,
                onWarning: (String) -> Unit) = Unit
        })
        val catalog = object : NovelAiCompletionCatalog {
            override val completionVersion = MutableStateFlow("synthetic")
            override suspend fun prepareCompletion() = Unit
            override suspend fun streamCompletion(query: String, onCandidate: suspend (NovelAiTagCandidate) -> Unit,
                onWarning: (String) -> Unit) = Unit
        }
        val suggestions = NovelAiTagSuggestionService(catalog, dictionary, scope)
        val translations = NovelAiPromptTranslationService(dictionary, lookup)
        val registry = DesktopStudioTextHistories()
        val initial = TextFieldValue(firstSource, TextRange(2, 5))
        val editor = registry.field("studio:重读注释", firstSource)
        editor.edit(initial) {}
        var source by mutableStateOf(firstSource)
        val scene = ImageComposeScene(520, 180, coroutineContext = edt) {
            CompositionLocalProvider(LocalDesktopStudioTextHistories provides registry) {
                StudioPromptField("重读注释", source, suggestions, translations, true) { source = it }
            }
        }
        try {
            fun hasWarning(): Boolean {
                fun contains(node: SemanticsNode): Boolean =
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("离线词典查询失败") } == true ||
                        node.children.any(::contains)
                return scene.semanticsOwners.any { contains(it.rootSemanticsNode) }
            }
            withTimeout(5_000) { while (!retryStarted.isCompleted) scene.frames() }
            scene.frames()
            assertTrue(hasWarning())
            assertEquals(initial, editor.value)
            finishRetry.complete(Unit)
            withTimeout(5_000) { while (hasWarning()) scene.frames() }
            assertEquals(2, calls[firstTag]?.get())
            assertEquals(initial, editor.value)
            assertEquals(firstSource, source)

            source = "blue eyes, $brokenTag"
            withTimeout(5_000) { while ((calls[brokenTag]?.get() ?: 0) < 2) scene.frames() }
            delay(700); scene.frames()
            assertEquals(2, calls[brokenTag]?.get())
            assertTrue(hasWarning())
            assertEquals("blue eyes, $brokenTag", editor.value.text)

            source = "blue eyes, $oldTag"
            withTimeout(5_000) { while (!oldRetryStarted.isCompleted) scene.frames() }
            assertTrue(hasWarning())
            source = "blue eyes, $replacementTag"
            withTimeout(5_000) {
                while (!oldRetryCancelled.isCompleted || calls[replacementTag]?.get() != 1 || hasWarning()) scene.frames()
            }
            assertEquals("blue eyes, $replacementTag", editor.value.text)
            assertFalse(hasWarning())
        } finally {
            finishRetry.complete(Unit)
            scene.close()
            scope.cancel()
        }
    }

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
