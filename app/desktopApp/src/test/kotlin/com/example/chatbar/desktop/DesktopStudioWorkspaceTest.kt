@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopStudioWorkspaceTest {
    private val awt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private suspend fun ImageComposeScene.frames() { repeat(8) { render().close(); yield() }; delay(60); render().close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(s: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(s) == true ||
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == s } == true
    private fun ImageComposeScene.bounds(s: String) = nodes().first { it.matches(s) }.boundsInRoot
    private suspend fun ImageComposeScene.click(s: String) {
        val target = nodes().first { it.matches(s) && it.config.getOrNull(SemanticsActions.OnClick) != null }.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, target, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, target, button = PointerButton.Primary); frames()
    }
    private suspend fun ImageComposeScene.drag(from: Offset, to: Offset) {
        sendPointerEvent(PointerEventType.Press, from, button = PointerButton.Primary)
        for (step in 1..10) { sendPointerEvent(PointerEventType.Move, from + (to - from) * (step / 10f), button = PointerButton.Primary); frames() }
        sendPointerEvent(PointerEventType.Release, to, button = PointerButton.Primary); frames()
    }
    private fun ImageComposeScene.shot(name: String) = render().use { image ->
        val output = Path.of("build/phase7-slice-c-scheme-a-evidence/$name.png")
        Files.createDirectories(output.parent); image.encodeToData()!!.use { Files.write(output, it.bytes) }; Unit
    }

    @Test fun `actual Studio root geometry modes diagnostics and narrow drawer preserve data`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); f.seedLocalImages()
            val c = f.container.novelAiStudioController
            val inspector = f.container.createPromptInspectorController()
            val draft = c.draft.value; val history = c.history.first(); val results = c.state.value.results
            for ((w, h) in listOf(1240 to 800, 1600 to 900, 900 to 700, 720 to 650, 360 to 620)) {
                val chrome = DesktopWindowChrome {}
                val recorder = DesktopChromeLayoutRecorder(chrome)
                val scene = ImageComposeScene(w, h) {
                    Box(Modifier.fillMaxSize().onGloballyPositioned(recorder::root)) {
                        DesktopNovelAiStudioPanel(c,
                            chromeRecorder = recorder,
                            navigation = { DesktopTitleBar(DesktopShellSize.COMPACT, DesktopPrimaryRoute.TOOLS, false, chrome, recorder, captionsVisible = false) {} },
                            captions = { DesktopTitleBar(DesktopShellSize.COMPACT, DesktopPrimaryRoute.TOOLS, false, chrome, recorder, navigationVisible = false) {} },
                            diagnostics = { DesktopPromptInspectorPanel(inspector) })
                    }
                }
                try {
                    fun assertPreviewCaption() {
                        val p = scene.bounds("Studio 全高预览面板")
                        val l = chrome.layout
                        assertEquals(listOf(ChromeRect(p.left, 0f, p.right, 44f)), l.dragRegions)
                        for (scale in listOf(1f, 1.25f, 1.5f, 2f)) {
                            fun hit(x: Float, y: Float, max: Boolean = false) = DesktopChromeHitTest.hit(
                                x * scale, y * scale, w * scale, h * scale, 8 * scale, max, l)
                            val blankX = maxOf(l.title!!.right + 10, p.left + 20).coerceAtMost(w - 140f)
                            assertEquals(ChromeHit.CAPTION, hit(blankX, 20f))
                            assertEquals(ChromeHit.CAPTION, hit(blankX, 20f, true))
                            assertEquals(ChromeHit.CLIENT, hit(p.right - 160f, 60f))
                            assertEquals(ChromeHit.MINIMIZE, hit(w - 105f, 20f))
                            assertEquals(ChromeHit.MAXIMIZE, hit(w - 63f, 20f))
                            assertEquals(ChromeHit.CLOSE, hit(w - 21f, 20f))
                        }
                    }
                    repeat(6) { scene.frames() }
                    val footer = scene.bounds("Studio 固定生成栏")
                    assertEquals(h.toFloat(), footer.bottom, 1f)
                    assertEquals(w.toFloat(), chrome.layout.captions.getValue(ChromeHit.CLOSE).right, 1f)
                    if (w >= 900) {
                        val pane = scene.bounds("Studio 全高预览面板")
                        assertEquals(0f, pane.top, 1f); assertEquals(h.toFloat(), pane.bottom, 1f)
                        assertEquals(w * .49f, pane.width, 1f)
                        assertTrue(footer.right < pane.left)
                        assertPreviewCaption()
                        val prompt = scene.bounds("Studio Prompt 编辑区")
                        scene.sendPointerEvent(PointerEventType.Scroll, prompt.center, scrollDelta = Offset(0f, 12f)); scene.frames()
                        assertEquals(footer, scene.bounds("Studio 固定生成栏"))
                        scene.sendPointerEvent(PointerEventType.Scroll, prompt.center, scrollDelta = Offset(0f, -100f)); scene.frames()
                        scene.shot("wide-expanded-$w")
                        scene.click("高级 / 诊断"); scene.shot("wide-diagnostics-$w")
                        assertEquals(pane, scene.bounds("Studio 全高预览面板"))
                        scene.click("NovelAI Studio")
                        scene.click("预览 · 展开预览 ▾"); scene.click("仅缩略图")
                        assertEquals(116f, scene.bounds("Studio 全高预览面板").width, 1f)
                        // The narrow rail's caption band is covered by the real window buttons.
                        assertEquals(116f, chrome.layout.dragRegions.single().right - chrome.layout.dragRegions.single().left, 1f)
                        scene.shot("wide-rail-$w")
                        scene.click("展开预览")
                        scene.click("预览 · 展开预览 ▾"); scene.click("预览专注")
                        assertEquals(w.toFloat(), scene.bounds("Studio 全高预览面板").width, 1f)
                        assertPreviewCaption()
                        scene.shot("wide-focus-$w"); scene.click("返回编辑")
                        assertEquals(pane, scene.bounds("Studio 全高预览面板"))
                    } else {
                        assertTrue(scene.bounds("Studio 当前图片摘要").height < 180f)
                        assertTrue(chrome.layout.dragRegions.isEmpty())
                        scene.shot(if (w == 360) "extreme-narrow-collapsed" else "narrow-collapsed")
                        scene.click("展开预览")
                        val pane = scene.bounds("Studio 全高预览面板")
                        assertEquals(0f, pane.top, 1f); assertEquals(h.toFloat(), pane.bottom, 1f)
                        assertEquals(w.toFloat(), pane.right, 1f)
                        assertTrue(scene.bounds("收起预览").top >= 44f)
                        assertPreviewCaption()
                        scene.shot(if (w == 360) "extreme-narrow-expanded" else "narrow-expanded")
                        scene.click("收起预览")
                        assertEquals(footer, scene.bounds("Studio 固定生成栏"))
                        assertTrue(chrome.layout.dragRegions.isEmpty())
                    }
                    val l = chrome.layout
                    for (scale in listOf(1f, 1.25f, 1.5f, 2f)) {
                        fun hit(x: Float, y: Float) = DesktopChromeHitTest.hit(x * scale, y * scale, w * scale, h * scale, 8 * scale, false, l)
                        assertEquals(ChromeHit.SYSTEM_MENU, hit(20f, 20f))
                        assertEquals(ChromeHit.CLIENT, hit(60f, 20f))
                        assertEquals(ChromeHit.CAPTION, hit(l.title!!.right - 15f, 20f))
                        assertEquals(ChromeHit.MAXIMIZE, hit(w - 63f, 20f))
                        if (w >= 900) {
                            val p = scene.bounds("Studio 全高预览面板")
                            assertEquals(ChromeHit.CAPTION, hit(p.left + 20f, 20f))
                            assertEquals(ChromeHit.CLIENT, hit(p.left + 20f, 60f))
                            assertEquals(ChromeHit.CLIENT, hit(p.left - 6f, 20f))
                            assertEquals(ChromeHit.CLIENT, hit(p.left - 6f, h / 2f))
                        }
                    }
                    assertEquals(draft, c.draft.value); assertEquals(results, c.state.value.results); assertEquals(history, c.history.first())
                } finally { scene.close() }
                assertNull(chrome.layout.title); assertTrue(chrome.layout.captions.isEmpty())
                assertTrue(chrome.layout.dragRegions.isEmpty())
            }
            assertTrue(f.requests.isEmpty()); assertTrue(c.taskEntries.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun `real divider drag snaps restores and clamps while editor composition survives mode and resize`() = runBlocking(awt) {
        val state = DesktopStudioWorkspaceState()
        var text = ""
        val scene = ImageComposeScene(1240, 800) {
            DesktopStudioWorkspace(state,
                compact = { expand -> StudioAction("展开预览", onClick = expand) },
                editor = { var input by remember { mutableStateOf("draft") }; StudioField("本地草稿", input) { input = it; text = it } },
                footer = { StatusText("Generate") }, preview = { StatusText("本地预览") })
        }
        try {
            scene.frames()
            val field = scene.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
            field.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("unsent")); scene.frames()
            val initial = scene.bounds("调整预览宽度")
            scene.drag(initial.center, initial.center - Offset(120f, 0f))
            val enlarged = scene.bounds("Studio 全高预览面板").width
            assertTrue(enlarged > 680f)
            scene.drag(scene.bounds("调整预览宽度").center, Offset(1130f, 400f))
            assertEquals("仅缩略图", state.mode); assertEquals(116f, scene.bounds("Studio 全高预览面板").width, 1f)
            scene.click("展开预览")
            assertTrue(scene.bounds("Studio 全高预览面板").width >= 300f)
            // Explicit modes keep the last expanded width; drag has intentionally changed it.
            val expandedWidth = scene.bounds("Studio 全高预览面板").width
            scene.click("预览 · 展开预览 ▾"); scene.click("预览专注"); scene.click("返回编辑")
            assertEquals(expandedWidth, scene.bounds("Studio 全高预览面板").width, 1f)
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "unsent" })
            scene.constraints = Constraints.fixed(360, 620); scene.frames(); scene.click("展开预览"); scene.click("收起预览")
            scene.constraints = Constraints.fixed(900, 700); scene.frames()
            assertTrue(scene.bounds("Studio 全高预览面板").width in 300f..568f)
            assertEquals("unsent", text)
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "unsent" })
        } finally { scene.close() }
    }
}
