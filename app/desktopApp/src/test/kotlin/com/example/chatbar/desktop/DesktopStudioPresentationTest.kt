@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.*
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

// Drive scenes and input on the same UI dispatcher as GlobalSnapshotManager and real windows.
class DesktopStudioPresentationTest {
    // Compose's AWT dispatcher is internal; no global Dispatchers.setMain override or new dependency.
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() { repeat(4) { render().close(); yield() } }
    private suspend fun ImageComposeScene.key(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); frames()
    }
    private suspend fun ImageComposeScene.click(x: Float, y: Float) {
        sendPointerEvent(PointerEventType.Press, Offset(x, y), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, Offset(x, y), button = PointerButton.Primary); frames()
    }
    private fun ImageComposeScene.shot(name: String) = render().use { image ->
        val path = Path.of("build", "phase7-ux-evidence", "$name.png"); Files.createDirectories(path.parent)
        image.encodeToData()?.use { Files.write(path, it.bytes) }; Unit
    }

    @Test fun `chips are direct keyboard choices and preserve unrelated serialized draft fields`() = runBlocking(awt) {
        var draft by mutableStateOf(NovelAiStudioDraft(basePrompt = "landscape", stylePrompt = "watercolor"))
        val before = draft
        val focus = FocusRequester()
        val scene = ImageComposeScene(500, 150) {
            Box(Modifier.focusRequester(focus)) {
                StudioChips("数量", (1..4).toList(), draft.activeSettings.count, { it.toString() }) { count ->
                    draft = draft.withActiveSettings(draft.activeSettings.copy(count = count))
                }
            }
        }
        try {
            scene.frames(); focus.requestFocus(); scene.key(Key.Tab); scene.key(Key.Tab); scene.key(Key.Enter)
            assertEquals(3, draft.activeSettings.count)
            assertEquals(Json.encodeToString(NovelAiStudioDraft.serializer(), before.withActiveSettings(before.activeSettings.copy(count = 3))), Json.encodeToString(NovelAiStudioDraft.serializer(), draft))
        } finally { scene.close() }
    }

    @Test fun `small enum menu supports arrows enter escape and nullable follow default`() = runBlocking(awt) {
        var selected by mutableStateOf<NovelAiImageModel?>(NovelAiImageModel.V4_5_FULL)
        var calls = 0
        val focus = FocusRequester()
        val scene = ImageComposeScene(500, 400) {
            Box(Modifier.focusRequester(focus)) {
                CompactChoice("模型", listOf<NovelAiImageModel?>(null) + NovelAiImageModel.entries, selected, { it?.displayName ?: "跟随默认" }) { selected = it; calls++ }
            }
        }
        try {
            scene.frames(); focus.requestFocus(); scene.key(Key.Enter); scene.key(Key.DirectionUp); scene.key(Key.Enter)
            assertNull(selected); assertEquals(1, calls)
            focus.requestFocus(); scene.key(Key.Enter); scene.key(Key.DirectionDown); scene.key(Key.Escape)
            assertNull(selected); assertEquals(1, calls)
            scene.shot("compact-choice-closed")
        } finally { scene.close() }
    }

    @Test fun `advanced disclosure starts collapsed and does not alter draft`() = runBlocking(awt) {
        var composed = false
        val settings = NovelAiGenerationSettings(seed = 42)
        val before = Json.encodeToString(NovelAiGenerationSettings.serializer(), settings)
        val scene = ImageComposeScene(600, 250) { StudioDisclosure("高级设置", studioAdvancedSummary(settings)) { composed = true; StatusText("Steps editor") } }
        try {
            scene.frames(); assertFalse(composed)
            assertTrue(studioAdvancedSummary(settings).contains("28 Steps")); assertTrue(studioAdvancedSummary(settings).contains("Random Seed"))
            scene.click(45f, 16f); assertTrue(composed)
            assertEquals(before, Json.encodeToString(NovelAiGenerationSettings.serializer(), settings))
        } finally { scene.close() }
    }

    @Test fun `sliders and keyboard keep exact discrete values`() = runBlocking(awt) {
        assertEquals(28f, studioSliderValue(27f / 49f, 1f, 50f, 1f))
        assertEquals(6.7f, studioSliderValue(5.7f / 9f, 1f, 10f, .1f))
        assertEquals(.65f, studioSliderValue(.65f, 0f, 1f, .05f))
        var value by mutableStateOf(6f)
        val focus = FocusRequester()
        val scene = ImageComposeScene(500, 120) {
            Box(Modifier.focusRequester(focus)) { StudioSlider("CFG", value, 1f, 10f, .1f, 1) { value = it } }
        }
        try {
            scene.frames(); focus.requestFocus(); scene.key(Key.Tab); scene.key(Key.DirectionRight)
            assertEquals(6.1f, value)
            scene.key(Key.MoveEnd); assertEquals(10f, value)
            scene.key(Key.MoveHome); assertEquals(1f, value)
            scene.shot("slider")
        } finally { scene.close() }
    }

    @Test fun `Wallpaper excludes square without modifying shared size or sampler capability`() {
        assertEquals(NovelAiAspectRatio.entries.toList(), studioAspectOptions(NovelAiGenerationSettings()))
        assertEquals(listOf(NovelAiAspectRatio.PORTRAIT, NovelAiAspectRatio.LANDSCAPE), studioAspectOptions(NovelAiGenerationSettings(sizeTier = NovelAiSizeTier.WALLPAPER)))
        val s = NovelAiGenerationSettings(customWidth = 1024, customHeight = 1024)
        assertNull(s.sizeValidationError()); assertNotNull(s.copy(customWidth = 1).sizeValidationError())
        assertFalse(s.copy(customWidth = null, customHeight = null).usesCustomSize)
    }

    @Test fun `precise readout retains imported values and only large choices use search dialog`() {
        assertEquals("0.00", studioNumericText(0f, 2))
        assertEquals("0.013", studioNumericText(.013f, 2))
        assertEquals("6.78", studioNumericText(6.78f, 1))
        assertTrue(studioAdvancedSummary(NovelAiGenerationSettings(guidance = 6.78f, cfgRescale = .013f)).contains("6.78 / 0.013"))
        assertFalse(studioUsesSearchDialog(NovelAiSampler.entries.size))
        assertFalse(studioUsesSearchDialog(NovelAiImageModel.entries.size + 1))
        assertTrue(studioUsesSearchDialog(100))
    }

    @Test fun `custom size applies valid dimensions resets preset or cancels without publication`() = runBlocking(awt) {
        for (action in listOf("apply", "reset", "cancel", "invalid")) {
            val settings = NovelAiGenerationSettings(customWidth = if (action == "invalid") 1 else 1024, customHeight = 1024)
            val values = mutableListOf<Pair<Int?, Int?>>()
            var cancelled = false
            val focus = FocusRequester()
            val scene = ImageComposeScene(420, 300) { Box(Modifier.focusRequester(focus)) {
                StudioCustomSizeEditor(settings, { cancelled = true }) { width, height -> values += width to height }
            } }
            try {
                scene.frames(); focus.requestFocus()
                repeat(when (action) { "apply" -> 2; "reset" -> 3; "cancel" -> 4; else -> 2 }) { scene.key(Key.Tab) }
                scene.key(Key.Enter)
                when (action) {
                    "apply" -> assertEquals(listOf<Pair<Int?, Int?>>(1024 to 1024), values)
                    "reset", "invalid" -> assertEquals(listOf<Pair<Int?, Int?>>(null to null), values, "Invalid Apply must be disabled; focus reaches Reset")
                    else -> { assertTrue(cancelled); assertTrue(values.isEmpty()) }
                }
                scene.shot("custom-size-$action")
            } finally { scene.close() }
        }
    }

    @Test fun `random seed switch conditionally adds fixed editor and preserves stored seed`() = runBlocking(awt) {
        var settings by mutableStateOf(NovelAiGenerationSettings(seed = 12345))
        var height = 0
        val focus = FocusRequester()
        val scene = ImageComposeScene(620, 420) {
            Column(Modifier.background(DesktopBootstrapColors.background).focusRequester(focus).onSizeChanged { height = it.height }) {
                StudioAdvancedSettings(settings) { transform -> settings = transform(settings) }
            }
        }
        try {
            scene.frames(); val randomHeight = height
            focus.requestFocus(); repeat(7) { scene.key(Key.Tab) }; scene.key(Key.Spacebar)
            assertEquals(NovelAiSeedMode.FIXED, settings.seedMode); assertEquals(12345L, settings.seed)
            assertTrue(height > randomHeight, "Fixed editor should exist only in fixed mode")
            scene.shot("advanced-fixed")
            scene.key(Key.Spacebar); assertEquals(NovelAiSeedMode.RANDOM, settings.seedMode); assertEquals(randomHeight, height)
        } finally { scene.close() }
    }

    @Test fun `inline and fullscreen text editor share annotations and leave request body unchanged`() = runBlocking(awt) {
        val prompt = NovelAiPromptPlan(baseCaption = "blue sky, forest", characterCaptions = emptyList(), negativePrompt = "blur")
        val settings = NovelAiGenerationSettings(seedMode = NovelAiSeedMode.FIXED, seed = 93)
        val service = NovelAiImageService()
        val before = service.buildRequestBody(prompt, settings.imageSize(), settings)
        for ((width, height) in listOf(400 to 150, 800 to 500)) {
            var enabled by mutableStateOf(true)
            var input by mutableStateOf(TextFieldValue(prompt.baseCaption, TextRange(4)))
            val annotation = listOf(NovelAiPromptAnnotation(0, 8, "blue sky", "蓝天"))
            val scene = ImageComposeScene(width, height) { Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp)) {
                StudioPromptTextEditor(input, { input = it }, enabled, annotation, Modifier.fillMaxSize())
            } }
            try {
                scene.frames(); scene.shot("prompt-editor-$width")
                enabled = false; scene.frames(); assertEquals(prompt.baseCaption, input.text); assertEquals(TextRange(4), input.selection)
                enabled = true; scene.frames(); assertEquals(before, service.buildRequestBody(prompt.copy(baseCaption = input.text), settings.imageSize(), settings))
            } finally { scene.close() }
        }
    }

    @Test fun `annotation follows glyph lines and rejects stale ranges without changing raw prompt`() = runBlocking(awt) {
        val source = "blue sky, green forest, mountain landscape"
        val annotations = listOf(NovelAiPromptAnnotation(0, 8, "blue sky", "蓝天"), NovelAiPromptAnnotation(10, 22, "green forest", "绿色森林"))
        for (width in listOf(160, 700)) {
            var raw by mutableStateOf(source)
            var enabled by mutableStateOf(true)
            var layout: TextLayoutResult? = null
            val scene = ImageComposeScene(width, 200) {
                var rendered by remember { mutableStateOf<TextLayoutResult?>(null) }
                BasicTextField(raw, { raw = it }, Modifier.fillMaxWidth().background(DesktopBootstrapColors.input),
                    textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp, lineHeight = 34.sp,
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None)),
                    onTextLayout = { layout = it; rendered = it }, decorationBox = { inner -> Box {
                        inner(); if (enabled) StudioPromptAnnotationOverlay(raw, rendered, annotations, Modifier.matchParentSize())
                    } })
            }
            try {
                scene.frames(); val positions = studioAnnotationPlacements(assertNotNull(layout), annotations)
                assertEquals(2, positions.size)
                assertEquals(layout!!.getBoundingBox(0).left, positions[0].slots[0].left)
                assertTrue(positions.all { p -> p.slots.all { it.right > it.left && it.baseline > 0 } })
                scene.shot("annotation-$width")
                enabled = false; scene.frames(); assertEquals(source, raw)
                enabled = true; raw = "new text"; scene.frames()
                assertTrue(studioAnnotationPlacements(assertNotNull(layout), annotations).isEmpty())
                assertEquals("new text", raw)
            } finally { scene.close() }
        }
    }

    @Test fun `suggestion keyboard acceptance protects selection IME and empty results`() {
        assertTrue(studioCanAcceptSuggestion(TextFieldValue("blue", TextRange(4)), 1))
        assertFalse(studioCanAcceptSuggestion(TextFieldValue("blue", TextRange(0, 4)), 1))
        assertFalse(studioCanAcceptSuggestion(TextFieldValue("blue", TextRange(4), TextRange(0, 4)), 1))
        assertFalse(studioCanAcceptSuggestion(TextFieldValue("blue"), 0))
    }

    @Test fun `field displays local translation and accepts anchored suggestion with Enter`() = runBlocking(awt) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val searched = CompletableDeferred<Unit>()
        val catalog = object : NovelAiCompletionCatalog {
            override val completionVersion = MutableStateFlow("fixture")
            override suspend fun prepareCompletion() = Unit
            override suspend fun streamCompletion(query: String, onCandidate: suspend (NovelAiTagCandidate) -> Unit, onWarning: (String) -> Unit) {
                if (query == "blue") onCandidate(NovelAiTagCandidate("blue_sky", "蓝天", 100, NovelAiTagCategory.GENERAL))
                searched.complete(Unit)
            }
        }
        val lookup = object : NovelAiTagLookup {
            override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList())
            override suspend fun exactChineseTranslations(names: Collection<String>) = mapOf("blue" to "蓝色", "blue_sky" to "蓝天")
            override suspend fun catalogMetadata() = DanbooruCatalogMetadata("fixture", "", 1)
        }
        val dictionary = NovelAiPromptWordDictionary.fromTsv("blue\t蓝色\nsky\t天空\n".byteInputStream())
        val suggestions = NovelAiTagSuggestionService(catalog, dictionary, scope)
        val translations = NovelAiPromptTranslationService(dictionary, lookup)
        var raw by mutableStateOf("blue")
        val scene = ImageComposeScene(520, 350) { Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp)) {
            StudioPromptField("基础 Prompt", raw, suggestions, translations, true) { raw = it }
        } }
        try {
            scene.frames(); scene.click(47f, 58f); scene.key(Key.MoveEnd)
            withTimeout(3000) { searched.await() }
            repeat(8) { delay(20); scene.frames() }
            scene.shot("field-suggestion-and-annotation")
            scene.key(Key.Enter)
            assertEquals(NovelAiTagCompletion.insert("blue", 4, "blue_sky").text, raw)
            assertFalse(raw.contains("蓝"))
        } finally { scene.close(); scope.cancel() }
    }

    @Test fun `anchored popup flips above near bottom and stays in viewport`() {
        assertEquals(IntOffset(50, 70), StudioPopupPosition.calculatePosition(IntRect(50, 40, 150, 70), IntSize(500, 400), LayoutDirection.Ltr, IntSize(200, 100)))
        assertEquals(IntOffset(300, 240), StudioPopupPosition.calculatePosition(IntRect(430, 340, 480, 370), IntSize(500, 400), LayoutDirection.Ltr, IntSize(200, 100)))
    }

    @Test fun `empty attachments occupy no row and compact chat actions retain ownership`() = runBlocking(awt) {
        var pick = 0; var images = 0; var background = 0
        val focus = FocusRequester()
        val scene = ImageComposeScene(500, 140) { Column(Modifier.focusRequester(focus)) {
            DesktopPendingImageStrip(emptyList(), true, {}, { pick++ }, showPicker = false)
            DesktopChatImageToolbar(true, false, { pick++ }, { images++ }, { background++ })
            StatusText("聊天输入区域")
        } }
        try {
            scene.frames(); focus.requestFocus(); scene.key(Key.Enter); scene.key(Key.Tab); scene.key(Key.Enter); scene.key(Key.Tab); scene.key(Key.Enter)
            assertEquals(1, pick); assertEquals(1, images); assertEquals(1, background)
            scene.shot("chat-image-toolbar")
        } finally { scene.close() }
    }
}
