@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopChatBubbleParityTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private val isolatedClipboard = DesktopSafeClipboard(object : Clipboard {
        override val nativeClipboard: Any get() = error("No system clipboard")
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
    })

    @Test fun `baseline segment geometry and typography remain distinct`() {
        val styles = RoleplaySegmentKind.entries.associateWith(::desktopSegmentMetrics)
        assertEquals(.96f, styles.getValue(RoleplaySegmentKind.NARRATION).widthFraction)
        assertEquals(.86f, styles.getValue(RoleplaySegmentKind.DIALOGUE).widthFraction)
        assertEquals(.72f, styles.getValue(RoleplaySegmentKind.THOUGHT).widthFraction)
        assertEquals(13, styles.getValue(RoleplaySegmentKind.NARRATION).fontSize)
        assertEquals(14, styles.getValue(RoleplaySegmentKind.DIALOGUE).fontSize)
        assertEquals(12, styles.getValue(RoleplaySegmentKind.THOUGHT).fontSize)
        assertEquals(3, styles.getValue(RoleplaySegmentKind.DIALOGUE).topStartRadius)
        assertEquals(4, styles.getValue(RoleplaySegmentKind.THOUGHT).topStartRadius)
    }

    @Test fun `actual mixed bubbles use baseline widths font scales and one grouped speaker header`() = runBlocking(awt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val narration = "Narration ".repeat(35).trim()
            val dialogue = "Dialogue ".repeat(35).trim()
            val thought = "『${"Thought ".repeat(35).trim()}』"
            val message = f.source.copy(content = "$narration<n=\"Alice\"/>[$dialogue]()$thought\n```status\nHidden\n```")
            var state by mutableStateOf(DesktopPrimaryChatState(selectedSession = f.c.chatRepository.getSession("session"),
                selectedCharacter = f.c.characterRepository.getById("card"), assistantSegmentedBubblesEnabled = true,
                chatBubbleFontScale = 1f))
            var expectedThoughtColor = Color.Unspecified
            val scene = ImageComposeScene(800, 1800) {
                expectedThoughtColor = DesktopBootstrapColors.mutedForeground
                PrimaryMessageBubble(message, state, f.c.primaryChatController, isolatedClipboard)
            }
            try {
                scene.until { scene.nodes().any { it.label(thought) } }
                val n = scene.tag("message-segment-0").boundsInRoot
                val d = scene.tag("message-segment-1").boundsInRoot
                val t = scene.tag("message-segment-2").boundsInRoot
                assertEquals(768f, n.width, 1f)
                assertEquals(16f, n.left, 1f)
                assertTrue(d.width <= 688f && t.width <= 576f && t.width < d.width)
                assertEquals(48f, d.left, 1f)
                assertEquals(d.left, t.left, 1f)
                assertEquals(40f, scene.tag("message-segment-1-avatar").boundsInRoot.width, 1f)
                assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith("-speaker") == true })
                assertEquals(13f, scene.layout(narration).layoutInput.style.fontSize.value)
                assertEquals(14f, scene.layout(dialogue).layoutInput.style.fontSize.value)
                assertEquals(12f, scene.layout(thought).layoutInput.style.fontSize.value)
                assertEquals(expectedThoughtColor, scene.layout(thought).layoutInput.style.color)
                state = state.copy(chatBubbleFontScale = 1.5f)
                scene.until { scene.layout(thought).layoutInput.style.fontSize.value == 18f }
                assertEquals(19.5f, scene.layout(narration).layoutInput.style.fontSize.value)
                assertEquals(21f, scene.layout(dialogue).layoutInput.style.fontSize.value)
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `status defaults and pointer toggles are isolated across alternatives and bubble modes`() = runBlocking(awt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val body = "Narration<n=\"Alice\"/>[Hello]()『Think』\n```status\n**Health: 3**\n```\n---\nOption A\n---"
            var message by mutableStateOf(f.source.copy(content = body, alternatives = listOf(body, body)))
            var state by mutableStateOf(DesktopPrimaryChatState(selectedSession = f.c.chatRepository.getSession("session"),
                selectedCharacter = f.c.characterRepository.getById("card"), assistantSegmentedBubblesEnabled = true))
            val scene = ImageComposeScene(600, 1400) { PrimaryMessageBubble(message, state, f.c.primaryChatController, isolatedClipboard) }
            try {
                scene.until { scene.nodes().any { it.label("Option A") } }
                assertFalse(scene.nodes().any { it.label("**Health: 3**") })
                scene.toggleFirstStatus()
                scene.until { scene.nodes().any { it.label("**Health: 3**") } }
                message = message.copy(currentAlternativeIndex = 1)
                scene.until { !scene.nodes().any { it.label("**Health: 3**") } }
                scene.toggleFirstStatus()
                scene.until { scene.nodes().any { it.label("**Health: 3**") } }
                scene.toggleFirstStatus()
                scene.until { !scene.nodes().any { it.label("**Health: 3**") } }
                state = state.copy(assistantSegmentedBubblesEnabled = false)
                scene.until { scene.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith("-avatar") == true } }
                assertTrue(scene.nodes().any { it.label("Option A") })
                assertFalse(scene.nodes().any { it.label("**Health: 3**") })
                scene.toggleFirstStatus()
                scene.until { scene.nodes().any { it.label("**Health: 3**") } }
                assertEquals(body, message.content)
                assertEquals(listOf(body, body), message.alternatives)
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `narrow long bubbles remain bounded in both presentation modes`() = runBlocking(awt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val body = "Long narration ".repeat(12) + "<n=\"Alice\"/>[${"Long dialogue ".repeat(12)}]()『${"Long thought ".repeat(12)}』"
            var state by mutableStateOf(DesktopPrimaryChatState(assistantSegmentedBubblesEnabled = true, chatBubbleFontScale = 1.2f))
            val scene = ImageComposeScene(260, 2400) { PrimaryMessageBubble(f.source.copy(content = body), state,
                f.c.primaryChatController, isolatedClipboard) }
            try {
                scene.until { scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "message-segment-2" } }
                for (index in 0..2) {
                    val bounds = scene.tag("message-segment-$index").boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= 260 && bounds.height > 0, "$bounds")
                }
                state = state.copy(assistantSegmentedBubblesEnabled = false)
                scene.until { scene.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "message-segment-2" } }
                val bounds = scene.tag("message-segment-0").boundsInRoot
                assertTrue(bounds.right <= 260 * .86f && bounds.height > 0, "$bounds")
                assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith("-speaker") == true })
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `unmarked dialogue keeps fallback avatar without inventing a speaker title`() = runBlocking(awt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val state = DesktopPrimaryChatState(selectedCharacter = f.c.characterRepository.getById("card"),
                assistantSegmentedBubblesEnabled = true)
            val scene = ImageComposeScene(400, 500) {
                PrimaryMessageBubble(f.source.copy(content = "[Unmarked dialogue]()『Following thought』"),
                    state, f.c.primaryChatController, isolatedClipboard)
            }
            try {
                scene.until { scene.nodes().any { it.label("Unmarked dialogue") } }
                assertEquals(40f, scene.tag("message-segment-0-avatar").boundsInRoot.width, 1f)
                assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith("-speaker") == true })
                assertEquals(scene.tag("message-segment-0").boundsInRoot.left,
                    scene.tag("message-segment-1").boundsInRoot.left, 1f)
            } finally { scene.close() }
        } finally { f.close() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(text: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    private fun ImageComposeScene.tag(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun ImageComposeScene.layout(text: String): TextLayoutResult {
        val node = nodes().single { it.label(text) }
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }
    private suspend fun ImageComposeScene.toggleFirstStatus() {
        val label = DesktopUiStrings(DesktopUiLanguage.ZH_CN)(DesktopUiText.STATUS_OPTIONS)
        var node = nodes().filter { it.label(label) }.minBy { it.boundsInRoot.top }
        while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = checkNotNull(node.parent)
        sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center, button = PointerButton.Primary)
        yield()
    }
    private suspend fun ImageComposeScene.until(predicate: () -> Boolean) = withTimeout(5000) {
        do { render(System.nanoTime()).close(); yield() } while (!predicate())
    }
}
