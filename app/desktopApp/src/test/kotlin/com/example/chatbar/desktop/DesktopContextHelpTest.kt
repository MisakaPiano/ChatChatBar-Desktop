@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopContextHelpTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `context instructions use the current route and retain a reachable close action in both languages`() = runBlocking(edt) {
        for (language in DesktopUiLanguage.entries) for (width in listOf(330, 1100)) {
            val strings = DesktopUiStrings(language)
            var route by mutableStateOf(DesktopPrimaryRoute.CHAT)
            var closed = 0
            val scene = ImageComposeScene(width, 650) {
                CompositionLocalProvider(LocalDesktopUiStrings provides strings) { DesktopContextHelpPanel(route) { closed++ } }
            }
            try {
                for (page in DesktopPrimaryRoute.entries) {
                    route = page; frames(scene)
                    val heading = "${strings(DesktopUiText.CONTEXT_HELP)} · ${strings(desktopContextHelp(page).first)}"
                    assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == heading } == true })
                    val close = button(scene, strings(DesktopUiText.CLOSE_CONTEXT_HELP))
                    assertTrue(close.boundsInRoot.left >= 0 && close.boundsInRoot.right <= width + 1)
                    assertTrue(close.boundsInRoot.top >= 0 && close.boundsInRoot.bottom <= 650)
                    close.config[SemanticsActions.OnClick].action!!.invoke()
                    if (page == DesktopPrimaryRoute.CHAT) capture(scene, "context-help-${language.name}-$width")
                }
                assertEquals(DesktopPrimaryRoute.entries.size, closed)
            } finally { scene.close() }
        }
    }

    @Test fun `smallest title bar keeps help tasks and native captions reachable while feature tabs scroll`() = runBlocking(edt) {
        for (width in listOf(330, 420, 720, 1100)) {
            val chrome = DesktopWindowChrome {}
            val recorder = DesktopChromeLayoutRecorder(chrome)
            var enabled by mutableStateOf(true)
            var route by mutableStateOf(DesktopPrimaryRoute.CHAT)
            var opened = 0
            val scene = ImageComposeScene(width, 400) {
                Box(Modifier.fillMaxSize().onGloballyPositioned(recorder::root)) {
                    DesktopTitleBar(DesktopShellLayoutPolicy.sizeForWidth(width.toFloat()), route, false,
                        chrome, recorder, momentsEnabled = true, onOpenTasks = {}, onOpenHelp = { opened++ },
                        helpEnabled = enabled, onNavigate = { route = it })
                }
            }
            try {
                frames(scene)
                val help = button(scene, DesktopUiText.CONTEXT_HELP.zhCn)
                val tasks = button(scene, "任务中心，进行中 0 项")
                val h = help.boundsInRoot; val q = tasks.boundsInRoot
                assertTrue(h.left >= 40 && h.right <= q.left + 1)
                assertTrue(q.right <= width)
                assertEquals(ChromeHit.CLIENT, DesktopChromeHitTest.hit(h.center.x, h.center.y, width.toFloat(), 400f, 8f, false, chrome.layout))
                assertEquals(ChromeHit.CLIENT, DesktopChromeHitTest.hit(q.center.x, q.center.y, width.toFloat(), 400f, 8f, false, chrome.layout))
                if (DesktopWindowChrome.isWindows) {
                    assertEquals(3, chrome.layout.captions.size)
                    assertTrue(q.right <= chrome.layout.captions.values.minOf { it.left } + 1)
                    for ((hit, rect) in chrome.layout.captions) {
                        assertTrue(rect.left >= 0 && rect.right <= width + 1)
                        assertEquals(hit, DesktopChromeHitTest.hit((rect.left + rect.right) / 2, 22f, width.toFloat(), 400f, 8f, false, chrome.layout))
                    }
                }
                // No feature is removed just to reserve optional controls.
                for (key in listOf(DesktopUiText.CHAT, DesktopUiText.MOMENTS, DesktopUiText.MANAGE, DesktopUiText.TOOLS, DesktopUiText.DATA)) {
                    assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(key.zhCn) == true })
                }
                help.config[SemanticsActions.OnClick].action!!.invoke(); assertEquals(1, opened)
                route = DesktopPrimaryRoute.DATA; frames(scene)
                fun dataTab() = nodes(scene).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(DesktopUiText.DATA.zhCn) == true }
                withTimeout(5000) {
                    while (dataTab().boundsInRoot.width <= 30 || dataTab().boundsInRoot.left < 40 || dataTab().boundsInRoot.right > h.left + 1) frames(scene)
                }
                val data = dataTab()
                assertTrue(data.boundsInRoot.left >= 40 && data.boundsInRoot.right <= h.left + 1)
                assertTrue(data.boundsInRoot.width > 30)
                enabled = false; frames(scene)
                assertTrue(button(scene, DesktopUiText.CONTEXT_HELP.zhCn).config.contains(SemanticsProperties.Disabled))
                capture(scene, "context-help-title-$width")
            } finally { scene.close() }
            assertNull(chrome.layout.title)
            assertTrue(chrome.layout.interactive.isEmpty())
        }
    }

    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render().close(); yield(); delay(20) } }
    private fun button(scene: ImageComposeScene, label: String) = nodes(scene).first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun capture(scene: ImageComposeScene, name: String) {
        System.getenv("CCB_CONTEXT_HELP_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("$name.png"); Files.createDirectories(path.parent)
            scene.render().use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
        }
    }
}
