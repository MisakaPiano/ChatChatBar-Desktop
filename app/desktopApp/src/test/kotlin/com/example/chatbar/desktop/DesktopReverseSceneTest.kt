@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.chat.OpenAiStreamingTransport
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopReverseSceneTest {
    private fun image() = DesktopImageEditing.png(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB))
    private suspend fun setup(f: FinalProductDesignFixture): DesktopNovelAiStudioController {
        f.initialize()
        val model = assertNotNull(f.container.modelRepository.getModel("local-design"))
        f.container.modelRepository.saveModel(model.copy(isMultimodal = true))
        return f.container.novelAiStudioController
    }
    private fun response(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: " + buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("content", text) }); put("finish_reason", "stop")
        }) }) } + "\n\ndata: [DONE]\n\n")

    @Test fun `real HTTP reverse pauses for scene then sends only confirmed text and applies exact V5 target with undo`() = runBlocking {
        MockWebServer().use { server ->
            val wire = OpenAiStreamingTransport { true }
            val text = DesktopNovelAiTextTransport(wire)
            val designer = NovelAiPromptDesigner(text, NovelAiTagResearchService(LlmNovelAiTagSearchPlanner(text),
                object : NovelAiTagSearchClient { override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList()) }))
            val f = FinalProductDesignFixture(overrideDesigner = designer)
            try {
                val c = setup(f)
                val model = assertNotNull(f.container.modelRepository.getModel("local-design"))
                f.container.modelRepository.saveModel(model.copy(baseUrl = server.url("/v1").toString(), maxOutputTokens = 317, enableThinking = true))
                c.edit { it.copy(selectedModel = NovelAiImageModel.V4_5_FULL, followDefaultNovelAiImageModel = true,
                    stylePrompt = "STYLE_KEEP", negativePrompt = "NEGATIVE_KEEP", extraRequirement = "REQUIREMENT_KEEP",
                    characters = listOf(NovelAiCharacterPromptDraft(prompt = "OLD_EDITABLE_ROLE", negativePrompt = "ROLE_NEGATIVE_KEEP")),
                    importedCharacterPromptSources = listOf(NovelAiCharacterPromptSource("Synthetic actor", "ACTOR_REFERENCE"))) }
                val before = c.repository.loadDraft()
                server.enqueue(response("""{"sceneDescription":"ORIGINAL_VISION_SCENE","queries":[]}"""))
                assertTrue(c.reverseImage(image())); f.idle()
                assertEquals("ORIGINAL_VISION_SCENE", c.state.value.reverseSceneDescription)
                assertNull(c.state.value.reverseCandidate); assertEquals(1, server.requestCount)
                assertEquals(before, c.repository.loadDraft())
                val first = Json.parseToJsonElement(assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)).body.readUtf8()).jsonObject
                assertTrue(first["messages"].toString().contains("image_url"))
                assertTrue(first["messages"].toString().contains("ACTOR_REFERENCE"))
                c.updateReverseSceneDescription("EDITED_CONFIRMED_SCENE")
                server.enqueue(response("""{"queries":[]}"""))
                server.enqueue(response("""{"baseCaption":"用户确认后的房间场景","characters":[{"caption":"合成人物"}]}"""))
                assertTrue(c.confirmReverseSceneDescription()); f.idle()
                assertNotNull(c.state.value.reverseCandidate); assertEquals(NovelAiImageModel.V5_FULL, c.state.value.reverseTargetModel)
                assertEquals(3, server.requestCount)
                repeat(2) {
                    val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals("/v1/chat/completions", request.path)
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    assertFalse(body["messages"].toString().contains("image_url"))
                    assertTrue(body["messages"].toString().contains("EDITED_CONFIRMED_SCENE"))
                    assertTrue(body["messages"].toString().contains("ACTOR_REFERENCE"))
                    assertTrue(body["messages"].toString().contains("REQUIREMENT_KEEP"))
                    assertFalse(body["messages"].toString().contains("OLD_EDITABLE_ROLE"))
                    assertEquals(317, body.getValue("max_tokens").jsonPrimitive.int)
                    assertTrue(body.getValue("enable_thinking").jsonPrimitive.boolean)
                }
                assertEquals(before, c.repository.loadDraft())
                assertTrue(c.applyReverseCandidate())
                val applied = c.repository.loadDraft()
                assertEquals("用户确认后的房间场景", applied.basePrompt)
                assertEquals(NovelAiImageModel.V5_FULL, applied.selectedModel); assertFalse(applied.followDefaultNovelAiImageModel)
                assertEquals(before.stylePrompt, applied.stylePrompt); assertEquals(before.negativePrompt, applied.negativePrompt)
                assertEquals(before.v45Settings, applied.v45Settings); assertEquals(before.v5Settings, applied.v5Settings)
                assertEquals(before.characters.single().id, applied.characters.single().id)
                assertEquals(before.characters.single().negativePrompt, applied.characters.single().negativePrompt)
                assertTrue(c.undo()); val undone = c.repository.loadDraft()
                assertEquals(before.basePrompt, undone.basePrompt); assertEquals(before.selectedModel, undone.selectedModel)
                assertEquals(before.followDefaultNovelAiImageModel, undone.followDefaultNovelAiImageModel)
                assertEquals(before.stylePrompt, undone.stylePrompt); assertEquals(before.negativePrompt, undone.negativePrompt)
            } finally { f.close(); wire.closeAndDrain() }
        }
    }

    @Test fun `empty edited scene and changed draft do not start final design`() = runBlocking {
        val f = FinalProductDesignFixture()
        try {
            val c = setup(f); assertTrue(c.reverseImage(image())); f.idle()
            c.updateReverseSceneDescription(" "); assertFalse(c.confirmReverseSceneDescription())
            assertEquals(1, f.requests.size); assertNull(c.state.value.reverseCandidate)
            c.updateReverseSceneDescription("Manual corrected scene")
            c.edit { it.copy(basePrompt = "NEW_MANUAL_DRAFT") }
            assertFalse(c.confirmReverseSceneDescription()); assertEquals(1, f.requests.size)
            assertEquals("NEW_MANUAL_DRAFT", c.repository.loadDraft().basePrompt)
        } finally { f.close() }
    }

    @Test fun `stale final candidate cannot overwrite later manual draft`() = runBlocking {
        val f = FinalProductDesignFixture()
        try {
            val c = setup(f); assertTrue(c.reverseImage(image())); f.idle()
            assertTrue(c.confirmReverseSceneDescription()); f.idle(); assertNotNull(c.state.value.reverseCandidate)
            c.edit { it.copy(basePrompt = "MANUAL_AFTER_CANDIDATE") }
            val before = c.repository.loadDraft()
            assertFalse(c.applyReverseCandidate()); assertEquals(before, c.repository.loadDraft())
        } finally { f.close() }
    }

    @Test fun `source replacement stops exact pending task and late result cannot resurrect previous scene`() = runBlocking {
        val f = FinalProductDesignFixture()
        try {
            val c = setup(f); val entered = CompletableDeferred<Unit>()
            f.beforeReply = { entered.complete(Unit); awaitCancellation() }
            val before = c.repository.loadDraft()
            assertTrue(c.reverseImage(image())); withTimeout(5_000) { entered.await() }
            c.clearReverseCandidate(); f.idle()
            assertNull(c.state.value.reverseSceneDescription); assertNull(c.state.value.reverseCandidate)
            assertEquals(before, c.repository.loadDraft())
            assertEquals(DesktopTaskStatus.CANCELLED, c.taskEntries.value.first().status)
            f.beforeReply = null; assertTrue(c.reverseImage(image())); f.idle()
            assertNotNull(c.state.value.reverseSceneDescription); assertNull(c.state.value.reverseCandidate)
        } finally { f.close() }
    }

    @Test fun `failure or cancellation preserves corrected scene and original draft for explicit retry`() = runBlocking {
        val f = FinalProductDesignFixture()
        try {
            val c = setup(f); assertTrue(c.reverseImage(image())); f.idle()
            c.updateReverseSceneDescription("CORRECTED_SCENE")
            val before = c.repository.loadDraft()
            f.failure = DesktopDesignFailure.NETWORK
            assertTrue(c.confirmReverseSceneDescription()); f.idle()
            assertNull(c.state.value.reverseCandidate); assertEquals("CORRECTED_SCENE", c.state.value.reverseSceneDescription)
            assertEquals(before, c.repository.loadDraft()); assertFalse(c.state.value.reverseBusy)
            f.failure = null
            val entered = CompletableDeferred<Unit>()
            f.beforeReply = { entered.complete(Unit); awaitCancellation() }
            assertTrue(c.confirmReverseSceneDescription()); withTimeout(5_000) { entered.await() }
            c.stopReverse(); f.idle()
            assertEquals("CORRECTED_SCENE", c.state.value.reverseSceneDescription); assertEquals(before, c.repository.loadDraft())
            f.beforeReply = null; assertTrue(c.confirmReverseSceneDescription()); f.idle()
            assertNotNull(c.state.value.reverseCandidate); assertEquals(before, c.repository.loadDraft())
        } finally { f.close() }
    }

    @Test fun `source or scene replacement while undo save is suspended refuses old candidate at actual draft commit`() = runBlocking {
        for (editScene in listOf(false, true)) {
            val f = FinalProductDesignFixture()
            try {
                val c = setup(f); assertTrue(c.reverseImage(image())); f.idle()
                assertTrue(c.confirmReverseSceneDescription()); f.idle()
                val before = c.repository.loadDraft()
                // First lock is action's original loadUndoDraft asset inspection; second is
                // apply's saveUndoDraft, after selection/candidate capture. Pause only the second.
                val field = c.repository.javaClass.getDeclaredField("undoMutex").also { it.isAccessible = true }
                val original = field.get(c.repository) as kotlinx.coroutines.sync.Mutex
                val locks = java.util.concurrent.atomic.AtomicInteger()
                val enteredSave = CompletableDeferred<Unit>(); val resumeSave = CompletableDeferred<Unit>()
                val tracking = object : kotlinx.coroutines.sync.Mutex by original {
                    override suspend fun lock(owner: Any?) {
                        if (locks.incrementAndGet() == 2) { enteredSave.complete(Unit); resumeSave.await() }
                        original.lock(owner)
                    }
                }
                field.set(c.repository, tracking)
                val applying = async(Dispatchers.Default) { c.applyReverseCandidate() }
                try {
                    withTimeout(5_000) { enteredSave.await() }
                    assertEquals(2, locks.get()); assertNotNull(c.state.value.reverseCandidate)
                    if (editScene) c.updateReverseSceneDescription("NEWER_CORRECTION") else c.clearReverseCandidate()
                } finally { resumeSave.complete(Unit) }
                assertFalse(withTimeout(5_000) { applying.await() })
                field.set(c.repository, original)
                assertEquals(before, c.repository.loadDraft()); assertNull(c.state.value.reverseCandidate)
                if (editScene) assertEquals("NEWER_CORRECTION", c.state.value.reverseSceneDescription)
            } finally { f.close() }
        }
    }

    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `actual reverse pane edits confirms and explicitly applies at narrow and wide sizes`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val f = FinalProductDesignFixture()
            try {
                val c = setup(f); assertTrue(c.reverseImage(image())); f.idle()
                val before = c.repository.loadDraft()
                val scene = ImageComposeScene(width, 760) {
                    val state by c.state.collectAsState()
                    val scope = rememberCoroutineScope()
                    Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp)) {
                        DesktopReversePromptPane(state, state.reverseBusy, c::updateReverseSceneDescription,
                            { scope.launch { c.confirmReverseSceneDescription() } }, c::stopReverse,
                            { scope.launch { c.reverseImage(image()) } }, true,
                            { scope.launch { c.applyReverseCandidate() } }, c::clearReverseCandidate)
                    }
                }
                try {
                    scene.frames()
                    val field = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("反推场景描述") == true }
                    assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("UI_EDITED_SCENE")))
                    scene.frames(); assertEquals("UI_EDITED_SCENE", c.state.value.reverseSceneDescription)
                    scene.visible("确认场景并设计 Prompt", width); scene.shot("reverse-scene-$width.png")
                    scene.click("确认场景并设计 Prompt")
                    withTimeout(5_000) { while (c.state.value.reverseCandidate == null) scene.frames() }
                    f.idle(); scene.frames()
                    assertNotNull(c.state.value.reverseCandidate); assertEquals(before, c.repository.loadDraft())
                    scene.visible("应用反推", width); scene.shot("reverse-candidate-$width.png")
                    scene.click("应用反推")
                    withTimeout(5_000) { while (c.state.value.reverseCandidate != null) scene.frames() }
                    scene.frames()
                    assertNotEquals(before.basePrompt, c.repository.loadDraft().basePrompt)
                    assertNull(c.state.value.reverseCandidate)
                } finally { scene.close() }
            } finally { f.close() }
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun ImageComposeScene.action(label: String) = nodes().first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.getOrNull(SemanticsActions.OnClick) != null
    }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render().close(); delay(20) } }
    private suspend fun ImageComposeScene.click(label: String) { assertTrue(action(label).config[SemanticsActions.OnClick].action!!.invoke()); frames() }
    private fun ImageComposeScene.visible(label: String, width: Int) {
        val bounds = action(label).boundsInRoot
        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 760 && bounds.width > 0, "$label: $bounds")
    }
    private fun ImageComposeScene.shot(name: String) {
        val output = System.getenv("CCB_FORMAT_AI_REVIEW_DIR") ?: return
        val path = Path.of(output).resolve(name); Files.createDirectories(path.parent)
        render().use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
    }
}
