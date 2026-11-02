@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopTutorialGuideTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `guide reads topics in both languages with real selected-list visibility stale paging guards and return to page help`() = runBlocking(edt) {
        for (language in DesktopUiLanguage.entries) for ((width, height) in listOf(330 to 650, 330 to 420, 1100 to 650)) {
            val t = DesktopUiStrings(language)
            var closed = 0
            val scene = ImageComposeScene(width, height, coroutineContext = edt) {
                CompositionLocalProvider(LocalDesktopUiStrings provides t) { DesktopContextHelpPanel(DesktopPrimaryRoute.CHAT) { closed++ } }
            }
            try {
                frames(scene); click(scene, t(DesktopUiText.GUIDE)); frames(scene)
                val close = described(scene, t(DesktopUiText.GUIDE_CLOSE))
                assertTrue(close.boundsInRoot.width > 0 && close.boundsInRoot.left >= 0 && close.boundsInRoot.right <= width)
                assertTrue(close.boundsInRoot.top >= 0 && close.boundsInRoot.bottom <= height)
                val back = button(scene, t(DesktopUiText.GUIDE_BACK)).boundsInRoot
                assertTrue(back.height > 0 && back.left >= 0 && back.right <= width && back.bottom <= height)
                assertTrue(button(scene, t(DesktopUiText.GUIDE_PREVIOUS)).config.contains(SemanticsProperties.Disabled))
                assertBody(scene, t, desktopTutorialTopics.first())
                capture(scene, "guide-${language.name}-${width}x$height-first")
                val staleNext = button(scene, t(DesktopUiText.GUIDE_NEXT))
                repeat(desktopTutorialTopics.size - 1) {
                    click(scene, t(DesktopUiText.GUIDE_NEXT)); frames(scene)
                }
                val last = desktopTutorialTopics.last()
                assertBody(scene, t, last)
                assertTrue(button(scene, t(DesktopUiText.GUIDE_NEXT)).config.contains(SemanticsProperties.Disabled))
                staleNext.config[SemanticsActions.OnClick].action?.invoke(); frames(scene)
                assertBody(scene, t, last)
                if (width < 720) { click(scene, t(DesktopUiText.GUIDE_TOPICS)); frames(scene) }
                if (width < 720) assertFalse(nodes(scene).any { matchesDescription(it, t(DesktopUiText.GUIDE_BODY)) })
                await(scene) { fullyVisibleTopic(scene, t, last) }
                capture(scene, "guide-${language.name}-${width}x$height-last-topics")
                if (width < 720) {
                    click(scene, t(DesktopUiText.GUIDE_TOPICS)); frames(scene)
                    assertFalse(nodes(scene).any { matchesDescription(it, t(DesktopUiText.GUIDE_TOPIC_LIST)) })
                    click(scene, t(DesktopUiText.GUIDE_TOPICS)); frames(scene)
                    await(scene) { fullyVisibleTopic(scene, t, last) }
                }
                val list = described(scene, t(DesktopUiText.GUIDE_TOPIC_LIST))
                val scroll = walk(list).first { it.config.contains(SemanticsActions.ScrollBy) }
                assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, -100_000f))
                await(scene) { fullyVisibleTopic(scene, t, desktopTutorialTopics.first()) }
                val first = described(scene, t(desktopTutorialTopics.first().title))
                assertTrue(first.config[SemanticsActions.OnClick].action!!.invoke()); frames(scene)
                assertBody(scene, t, desktopTutorialTopics.first())
                click(scene, t(DesktopUiText.GUIDE_BACK)); frames(scene)
                assertFalse(nodes(scene).any { matchesDescription(it, t(DesktopUiText.GUIDE_CLOSE)) })
                assertNotNull(described(scene, t(DesktopUiText.CLOSE_CONTEXT_HELP)))
                clickDescription(scene, t(DesktopUiText.CLOSE_CONTEXT_HELP)); assertEquals(1, closed)
            } catch (failure: Throwable) {
                capture(scene, "guide-${language.name}-${width}x$height-failure")
                throw failure
            } finally { scene.close() }
        }
    }

    private fun assertBody(scene: ImageComposeScene, t: DesktopUiStrings, topic: DesktopGuideTopic) {
        val body = described(scene, t(DesktopUiText.GUIDE_BODY))
        assertTrue(walk(body).any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == t(topic.title) } == true })
        assertTrue(body.boundsInRoot.width > 0 && body.boundsInRoot.height > 0)
    }
    private fun fullyVisibleTopic(scene: ImageComposeScene, t: DesktopUiStrings, topic: DesktopGuideTopic): Boolean {
        val list = nodes(scene).firstOrNull { matchesDescription(it, t(DesktopUiText.GUIDE_TOPIC_LIST)) } ?: return false
        val node = walk(list).firstOrNull { matchesDescription(it, t(topic.title)) } ?: return false
        val bounds = node.boundsInRoot; val viewport = list.boundsInRoot
        return bounds.height >= 39 && bounds.width > 0 && bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1 &&
            bounds.top >= viewport.top - 1 && bounds.bottom <= viewport.bottom + 1
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render(System.nanoTime()).close(); yield(); delay(20) } }
    private suspend fun await(scene: ImageComposeScene, condition: () -> Boolean) { withTimeout(5000) { do { frames(scene) } while (!condition()) } }
    private fun matchesDescription(node: SemanticsNode, text: String) = node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    private fun described(scene: ImageComposeScene, label: String) = nodes(scene).first { matchesDescription(it, label) }
    private fun button(scene: ImageComposeScene, label: String): SemanticsNode {
        fun has(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || node.children.any(::has)
        return nodes(scene).first { has(it) && it.config.contains(SemanticsActions.OnClick) }
    }
    private fun click(scene: ImageComposeScene, label: String) { assertTrue(button(scene, label).config[SemanticsActions.OnClick].action!!.invoke()) }
    private fun clickDescription(scene: ImageComposeScene, label: String) { assertTrue(described(scene, label).config[SemanticsActions.OnClick].action!!.invoke()) }
    private fun capture(scene: ImageComposeScene, name: String) {
        System.getenv("CCB_CONTEXT_HELP_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("$name.png"); Files.createDirectories(path.parent)
            scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
        }
    }
}
