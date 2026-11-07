@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopReverseSceneEditorTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun image() = DesktopImageEditing.png(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB))
    private suspend fun setup(f: FinalProductDesignFixture): DesktopNovelAiStudioController {
        f.initialize(false)
        val model = assertNotNull(f.container.modelRepository.getModel("local-design"))
        f.container.modelRepository.saveModel(model.copy(isMultimodal = true))
        return f.container.novelAiStudioController.also { assertTrue(it.reverseImage(image())); f.idle() }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private suspend fun ImageComposeScene.frames() { repeat(12) { render(System.nanoTime()).close(); delay(18) } }
    private fun ImageComposeScene.click(label: String) {
        val n = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
            it.config.contains(SemanticsActions.OnClick) }
        assertFalse(n.config.contains(SemanticsProperties.Disabled))
        assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun ImageComposeScene.shot(name: String) {
        System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let {
            val p = Path.of(it).resolve(name); Files.createDirectories(p.parent)
            render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(p, it.bytes) } }
        }
    }

    @Test fun `actual pane shows both reverse modes and opens scene editing without confirming generation or applying a candidate`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) for (natural in listOf(false, true)) {
            var expanded = 0; var confirmed = 0; var applied = 0
            val scene = ImageComposeScene(width, 760, coroutineContext = edt) {
                Box(Modifier.fillMaxSize().padding(12.dp)) {
                    DesktopReversePromptPane(DesktopNovelAiStudioState(reverseSceneDescription = "Synthetic editable scene"), false,
                        {}, { confirmed++ }, {}, {}, true, { applied++ }, {}, naturalLanguage = natural, onExpandScene = { expanded++ })
                }
            }
            try {
                scene.frames()
                val mode = if (natural) "反推模式：自然语言 · V5" else "反推模式：结构化 Tag"
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == mode } == true })
                scene.click("展开反推场景描述"); scene.frames()
                assertEquals(1, expanded); assertEquals(0, confirmed); assertEquals(0, applied)
                scene.shot("reverse-mode-$natural-$width.png")
            } finally { scene.close() }
        }
    }

    @Test fun `expanded scene content cancels locally then explicitly applies changed text without a model request or draft write`() = runBlocking(edt) {
        val f = FinalProductDesignFixture()
        try {
            val c = setup(f); val ticket = assertNotNull(c.beginReverseSceneEdit())
            val draft = c.repository.loadDraft(); val count = f.requests.size
            val presentation = DesktopReverseSceneEditorPresentation()
            var captures = 0
            presentation.open { captures++; ticket }
            var observedActivation = 0
            val edited = (1..45).joinToString("\n") { "Synthetic scene line $it" }
            val scene = ImageComposeScene(760, 620, coroutineContext = edt) {
                presentation.editor?.let { active ->
                    Box(Modifier.fillMaxSize().padding(12.dp)) {
                        val activation = presentation.activationRevision
                        SideEffect { observedActivation = activation }
                        DesktopReverseSceneEditorBody(active, false,
                            { assertTrue(c.applyReverseSceneEdit(active, it)); true }, presentation::close)
                    }
                }
            }
            fun field() = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("展开的反推场景描述") == true &&
                it.config.contains(SemanticsActions.SetText) }
            try {
                scene.frames(); assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(edited))); scene.frames()
                presentation.open { captures++; error("An existing editor must not recapture its source") }; scene.frames()
                assertEquals(1, captures); assertEquals(2, observedActivation)
                assertEquals(edited, field().config[SemanticsProperties.EditableText].text)
                scene.shot("reverse-expanded-scene.png")
                scene.click("取消"); scene.frames()
                assertEquals(ticket.original, c.state.value.reverseSceneDescription)
                assertEquals(draft, c.repository.loadDraft()); assertEquals(count, f.requests.size)
                presentation.open { captures++; assertNotNull(c.beginReverseSceneEdit()) }; scene.frames()
                assertEquals(ticket.original, field().config[SemanticsProperties.EditableText].text)
                assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(edited))); scene.frames()
                scene.click("应用场景修改"); scene.frames()
                assertNull(presentation.editor); assertEquals(2, captures); assertEquals(edited, c.state.value.reverseSceneDescription)
                assertNull(c.state.value.reverseCandidate); assertEquals(draft, c.repository.loadDraft()); assertEquals(count, f.requests.size)
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `expanded scene ticket refuses newer inline text stopped cleared or same text replacement sources`() = runBlocking {
        for (change in listOf("inline", "stop", "clear", "replace")) {
            val f = FinalProductDesignFixture()
            try {
                val c = setup(f); val ticket = assertNotNull(c.beginReverseSceneEdit())
                when (change) {
                    "inline" -> c.updateReverseSceneDescription("Newer inline scene")
                    "stop" -> c.stopReverse()
                    "clear" -> c.clearReverseCandidate()
                    else -> { assertTrue(c.reverseImage(image())); f.idle() }
                }
                val before = c.state.value.reverseSceneDescription; val count = f.requests.size
                assertFalse(c.applyReverseSceneEdit(ticket, "Stale expanded scene"), change)
                assertEquals(before, c.state.value.reverseSceneDescription); assertEquals(count, f.requests.size)
            } finally { f.close() }
        }
    }

    @Test fun `unchanged expanded scene keeps the candidate and an active confirmation refuses stale apply`() = runBlocking {
        val f = FinalProductDesignFixture()
        try {
            val c = setup(f); val stale = assertNotNull(c.beginReverseSceneEdit())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.beforeReply = { entered.complete(Unit); release.await() }
            try {
                assertTrue(c.confirmReverseSceneDescription()); withTimeout(5_000) { entered.await() }
                assertTrue(c.state.value.reverseBusy)
                assertFalse(c.applyReverseSceneEdit(stale, "Must not replace active source"))
            } finally { release.complete(Unit) }
            f.idle(); f.beforeReply = null
            val candidate = assertNotNull(c.state.value.reverseCandidate); val target = c.state.value.reverseTargetModel
            val ticket = assertNotNull(c.beginReverseSceneEdit()); val count = f.requests.size
            assertTrue(c.applyReverseSceneEdit(ticket, ticket.original))
            assertEquals(candidate, c.state.value.reverseCandidate); assertEquals(target, c.state.value.reverseTargetModel)
            assertEquals(count, f.requests.size)
        } finally { f.close() }
    }
}
