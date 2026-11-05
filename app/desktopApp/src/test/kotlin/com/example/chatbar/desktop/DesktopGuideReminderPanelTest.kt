@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopGuideReminderPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `first guide reminder stays bounded in both languages and defers without consuming pending choice`() = runBlocking(edt) {
        for (language in DesktopUiLanguage.entries) for ((width, height) in listOf(330 to 420, 1100 to 650)) {
            val t = DesktopUiStrings(language)
            var state by mutableStateOf(DesktopGuideReminderState(ready = true))
            var blocked by mutableStateOf(true)
            var views = 0; var later = 0; var never = 0; var retry = 0
            val scene = ImageComposeScene(width, height, coroutineContext = edt) {
                CompositionLocalProvider(LocalDesktopUiStrings provides t) {
                    DesktopGuideReminder(state, blocked, { views++ }, { later++ }, { never++ }, { retry++ })
                }
            }
            try {
                frames(scene); assertFalse(texts(scene).contains(t(DesktopUiText.GUIDE_REMINDER)))
                blocked = false; frames(scene)
                for (problem in listOf<DesktopGuideReminderProblem?>(null, DesktopGuideReminderProblem.READ, DesktopGuideReminderProblem.SAVE)) {
                    state = state.copy(problem = problem); frames(scene)
                    if (problem != null) {
                        val label = t(if (problem == DesktopGuideReminderProblem.READ)
                            DesktopUiText.GUIDE_REMINDER_READ_ERROR else DesktopUiText.GUIDE_REMINDER_SAVE_ERROR)
                        val cue = nodes(scene).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }.boundsInRoot
                        val viewport = nodes(scene).first { it.config.getOrNull(SemanticsProperties.TestTag) == "guide-reminder-body" }.boundsInRoot
                        assertTrue(cue.top >= viewport.top && cue.top + 20 <= viewport.bottom,
                            "$language/$width/$problem initial failure cue is not readable: $cue in $viewport")
                    }
                    for (label in listOf(DesktopUiText.GUIDE_REMINDER_OPEN, DesktopUiText.GUIDE_REMINDER_LATER, DesktopUiText.GUIDE_REMINDER_NEVER)) {
                        val node = button(scene, t(label)); val b = node.boundsInRoot
                        assertTrue(b.width > 0 && b.height >= 47 && b.left >= 0 && b.right <= width && b.top >= 0 && b.bottom <= height, "$language/$width/$problem/$label $b")
                    }
                    if (problem == DesktopGuideReminderProblem.READ) {
                        assertTrue(button(scene, t(DesktopUiText.GUIDE_REMINDER_NEVER)).config.contains(SemanticsProperties.Disabled))
                        val action = button(scene, t(DesktopUiText.GUIDE_REMINDER_RETRY))
                        assertTrue(action.boundsInRoot.height >= 47 && action.boundsInRoot.bottom <= height)
                        action.config[SemanticsActions.OnClick].action!!.invoke()
                    }
                    capture(scene, "guide-reminder-${language.name}-${width}-${problem ?: "ready"}")
                }
                state = state.copy(problem = null); frames(scene)
                val close = nodes(scene).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(t(DesktopUiText.GUIDE_REMINDER_LATER)) == true &&
                    it.config.contains(SemanticsActions.OnClick) }
                assertTrue(close.boundsInRoot.height >= 47 && close.boundsInRoot.bottom <= height)
                button(scene, t(DesktopUiText.GUIDE_REMINDER_OPEN)).config[SemanticsActions.OnClick].action!!.invoke()
                button(scene, t(DesktopUiText.GUIDE_REMINDER_LATER)).config[SemanticsActions.OnClick].action!!.invoke()
                button(scene, t(DesktopUiText.GUIDE_REMINDER_NEVER)).config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(listOf(1, 1, 1, 1), listOf(views, later, never, retry))
                state = state.copy(busy = true); frames(scene)
                assertTrue(button(scene, t(DesktopUiText.GUIDE_REMINDER_OPEN)).config.contains(SemanticsProperties.Disabled))
                state = state.copy(acknowledgedVersion = CURRENT_DESKTOP_GUIDE_VERSION); frames(scene)
                assertFalse(texts(scene).contains(t(DesktopUiText.GUIDE_REMINDER)))
            } finally { scene.close() }
        }
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render(System.nanoTime()).close(); yield(); delay(20) } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun button(scene: ImageComposeScene, label: String): SemanticsNode {
        fun has(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.children.any(::has)
        return nodes(scene).first { has(it) && it.config.contains(SemanticsActions.OnClick) }
    }
    private fun capture(scene: ImageComposeScene, name: String) {
        System.getenv("CCB_CONTEXT_HELP_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("$name.png"); Files.createDirectories(path.parent)
            scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
        }
    }
}
