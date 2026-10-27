@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopR1LayoutMenuTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private suspend fun frame(scene: ImageComposeScene) { repeat(8) { scene.render(System.nanoTime()).close(); yield(); delay(15) } }

    @Test fun `real message roles expose only their own context actions and targets`() = runBlocking(edt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize(); val c = f.c.primaryChatController; c.refresh(); c.selectSession("session")
            for (message in c.screenshotTimeline()) {
                val actions = mutableListOf<DesktopMessageAction>()
                val shots = mutableListOf<Pair<ChatMessage, String?>>()
                val scene = ImageComposeScene(600, 700) {
                    CompositionLocalProvider(LocalDesktopChatScreenshot provides { m, b -> shots.add(m to b); Unit }) {
                        PrimaryMessageBubble(message, c.state.value, c, DesktopSafeClipboard(LocalClipboard.current),
                            desktopMessageActions(c.state.value.messages, message, null), { actions.add(it) })
                    }
                }
                try {
                    frame(scene)
                    val text = nodes(scene).first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains(message.content) } == true }
                    fun open() {
                        scene.sendPointerEvent(PointerEventType.Press, text.boundsInRoot.center, button = PointerButton.Secondary)
                        scene.sendPointerEvent(PointerEventType.Release, text.boundsInRoot.center, button = PointerButton.Secondary)
                    }
                    open(); frame(scene)
                    fun entries() = nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) }
                        .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.filter { " · " in it }
                    if (message.role == MessageRole.USER) {
                        assertEquals(setOf("消息 · 复制", "消息 · 编辑", "消息 · 删除", "消息 · 截图"), entries().toSet())
                        for ((label, expected) in listOf("复制" to DesktopMessageAction.COPY, "编辑" to DesktopMessageAction.EDIT, "删除" to DesktopMessageAction.DELETE)) {
                            nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("消息 · $label") == true }
                                .config[SemanticsActions.OnClick].action!!()
                            assertEquals(expected, actions.last()); frame(scene); open(); frame(scene)
                        }
                        nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("消息 · 截图") == true }
                            .config[SemanticsActions.OnClick].action!!()
                        assertEquals(listOf<Pair<ChatMessage, String?>>(message to null), shots)
                    } else {
                        assertEquals(setOf("回复", "本段", "整条"), entries().map { it.substringBefore(" · ") }.toSet())
                        assertEquals(12, entries().size)
                    }
                } finally { scene.close() }
            }
        } finally { f.close() }
    }

    @Test fun `actual compact and normal chat header stays single line with accessible composer`() = runBlocking(edt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize(); val c = f.c.primaryChatController
            val session = f.c.chatRepository.getSession("session")!!
            f.c.chatRepository.updateSession(session.copy(title = "极窄窗口中的长会话标题".repeat(12)))
            c.refresh(); c.selectSession("session")
            for (width in listOf(360, 1100)) {
                val navigation = DesktopCompactChatNavigation().also { it.onSessionEntered("session", c.state.value) }
                val scene = ImageComposeScene(width, 800) {
                    DesktopPrimaryChatPanel(c, if (width == 360) DesktopShellSize.COMPACT else DesktopShellSize.WIDE, DesktopComposerLayoutState(), compactNavigation = navigation)
                }
                try {
                    frame(scene)
                    val title = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "chat-header-title" }
                    val layouts = mutableListOf<TextLayoutResult>()
                    title.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
                    assertEquals(1, layouts.single().lineCount, "viewport=$width x 800 title=${title.boundsInRoot}")
                    assertTrue(title.boundsInRoot.height <= 32f)
                    val model = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "chat-header-model" }
                    val modelLayouts = mutableListOf<TextLayoutResult>()
                    model.config[SemanticsActions.GetTextLayoutResult].action!!(modelLayouts)
                    assertEquals(1, modelLayouts.single().lineCount)
                    // Compose Desktop 1.10.3 SkiaParagraph.isLineEllipsized is a constant false stub.
                    // Use native truncation plus the actual layout policy, not that unsupported query.
                    assertEquals(androidx.compose.ui.text.style.TextOverflow.Ellipsis, layouts.single().layoutInput.overflow)
                    assertFalse(layouts.single().layoutInput.softWrap)
                    if (width == 360) assertTrue(layouts.single().multiParagraph.didExceedMaxLines)
                    val input = nodes(scene).filter { it.config.contains(SemanticsActions.SetText) }
                    assertTrue(input.any { it.boundsInRoot.height >= 40 && it.boundsInRoot.bottom <= 800 })
                    println("R1_LAYOUT viewport=${width}x800 title=${title.boundsInRoot} input=${input.map { it.boundsInRoot }}")
                    saveR1Scene(scene, "chat-$width")
                } finally { scene.close() }
            }
        } finally { f.close() }
    }

    @Test fun `backup and update actual panels expose separate prominent headings`() = runBlocking(edt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val scene = ImageComposeScene(1000, 950) {
                androidx.compose.foundation.layout.Column(Modifier.background(DesktopBootstrapColors.background)) {
                    DesktopFullBackupPanel(f.c.fullBackupController) {}
                    DesktopUpdatePanel()
                }
            }
            try {
                frame(scene)
                val headings = nodes(scene).filter { it.config.contains(SemanticsProperties.Heading) }
                assertEquals(2, headings.size)
                for (heading in headings) {
                    val results = mutableListOf<TextLayoutResult>()
                    heading.config[SemanticsActions.GetTextLayoutResult].action!!(results)
                    assertEquals(18f, results.single().layoutInput.style.fontSize.value)
                    assertTrue(heading.boundsInRoot.height >= 20f)
                }
                assertTrue(headings[1].boundsInRoot.top > headings[0].boundsInRoot.bottom)
                saveR1Scene(scene, "data-sections")
                assertTrue(f.requests.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `persisted assistant recording follows real short and long bubbles beside user messages`() = runBlocking(edt) {
        for (long in listOf(false, true)) {
            val f = DesktopAssistantImageActionTest.Fixture()
            try {
                f.initialize()
                f.c.chatRepository.updateMessage(f.source.copy(content = "<n=\"Alice\"/>[${if (long) "合成验收内容。".repeat(80) else "好。"}]()"))
                f.source = f.c.chatRepository.getMessage(f.source.id, "session")!!
                val voice = persistR1Recording(f)
                val c = f.c.primaryChatController; c.refresh(); c.selectSession("session")
                val user = c.screenshotTimeline().first { it.role == MessageRole.USER }
                for (width in listOf(360, 1100)) {
                    val scene = ImageComposeScene(width, 1200) {
                        androidx.compose.foundation.layout.Column(Modifier.background(DesktopBootstrapColors.background)) {
                            for (m in listOf(user, f.source)) PrimaryMessageBubble(m, c.state.value, c,
                                DesktopSafeClipboard(LocalClipboard.current), desktopMessageActions(c.state.value.messages, m, null))
                        }
                    }
                    try {
                        withTimeout(6000) {
                            while (nodes(scene).none { it.config.getOrNull(SemanticsProperties.TestTag) == "recording-row-${voice.id}" }) frame(scene)
                        }
                        frame(scene)
                        val row = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "recording-row-${voice.id}" }
                        val text = nodes(scene).last { it.config.getOrNull(SemanticsProperties.TestTag) == "message-segment-0" }
                        assertEquals(minOf(width.toFloat(), maxOf(200f, text.boundsInRoot.width)), row.boundsInRoot.width)
                        assertTrue(row.boundsInRoot.right <= width)
                        assertEquals(1, nodes(scene).count { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("recording-row-") == true })
                        val userBubble = nodes(scene).first { it.config.getOrNull(SemanticsProperties.TestTag) == "message-content-bubble" }
                        assertEquals(width.toFloat(), userBubble.boundsInRoot.right)
                        saveR1Scene(scene, "messages-$width-${if (long) "long" else "short"}")
                        println("R1_MESSAGE_WIDTH viewport=$width long=$long text=${text.boundsInRoot} recording=${row.boundsInRoot}")
                    } finally { scene.close() }
                }
            } finally { f.close() }
        }
    }
}
