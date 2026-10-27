@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.parseRoleplayTextSegments
import java.awt.datatransfer.DataFlavor
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopMessageActionsUxTest {
    private val card = CharacterCard.create("Card").copy(botName = "Bot")
    private val session = ChatSession.create(card.id, "Session").copy(playerName = "Player")

    @Test fun `whole copy preserves raw multi segment Markdown and role markers`() {
        val raw = "# Heading\n**bold**\n- first\n- second\n```kotlin\nval n = 1\n```\n" +
            "旁白<n=\"Alice\"/>[Hello ${'$'}username]()『Thinking ${'$'}botname』" +
            "<n=\"Bot\"/>[Second dialogue]()\n```status\nHealthy\n```"
        val message = ChatMessage.create(session.id, MessageRole.ASSISTANT, raw)
            .copy(reasoningContent = "separate private reasoning", images = listOf("images/not-body.png"))
        val expected = raw.replace("${'$'}username", "Player").replace("${'$'}botname", "Bot")
        assertEquals(expected, desktopWholeMessageCopyText(message, session, card, "Global"))
        // The existing display projection intentionally has different semantics.
        assertNotEquals(expected, desktopPresentMessage(message, card, "Global", true, session = session).copyText)
        assertEquals(raw, message.content)
    }

    @Test fun `middle and last copies use exact raw source ranges not display text`() {
        val message = ChatMessage.create(session.id, MessageRole.ASSISTANT,
            "旁白<n=\"Alice\"/>[Hello ${'$'}username]()『Thinking』\n```status\n${'$'}botname healthy\n```")
        val segments = parseRoleplayTextSegments(message.displayContent)
        assertEquals(4, segments.size)
        assertEquals("<n=\"Alice\"/>[Hello Player]()",
            desktopSegmentCopyText(message, segments[1], session, card, "Global"))
        assertEquals("『Thinking』", desktopSegmentCopyText(message, segments[2], session, card, "Global"))
        val last = segments.last()
        assertEquals(message.displayContent.substring(last.start, last.endExclusive).replace("${'$'}botname", "Bot"),
            desktopSegmentCopyText(message, last, session, card, "Global"))
        assertTrue(desktopSegmentCopyText(message, last, session, card, "Global").contains("```status"))
    }

    @Test fun `copy reads selected candidate only and user empty boundaries remain raw`() {
        val message = ChatMessage.create(session.id, MessageRole.ASSISTANT, "old base")
            .copy(alternatives = listOf("not selected", "**selected**\n${'$'}username"), currentAlternativeIndex = 1)
        assertEquals("**selected**\nPlayer", desktopWholeMessageCopyText(message, session, card, "Global"))
        val user = ChatMessage.create(session.id, MessageRole.USER, "  # ${'$'}botname\n${'$'}username  ")
        assertEquals("  # Bot\nGlobal  ", desktopWholeMessageCopyText(user, session.copy(playerName = ""), card, "Global"))
        assertEquals("", desktopWholeMessageCopyText(user.copy(content = ""), session, card, "Global"))
    }

    @Test fun `context groups preserve eligibility and separate source and whole scopes`() {
        val actions = listOf(DesktopMessageAction.COPY, DesktopMessageAction.EDIT, DesktopMessageAction.DELETE,
            DesktopMessageAction.REGENERATE)
        val grouped = desktopMessageContextActions(actions, true)
        assertEquals(listOf(DesktopMessageActionScope.REPLY) + List(3) { DesktopMessageActionScope.SEGMENT } +
            List(3) { DesktopMessageActionScope.MESSAGE }, grouped.map { it.scope })
        assertEquals(actions.take(3), grouped.filter { it.scope == DesktopMessageActionScope.SEGMENT }.map { it.action })
        assertEquals(actions.take(3), grouped.filter { it.scope == DesktopMessageActionScope.MESSAGE }.map { it.action })
        assertTrue(desktopMessageContextActions(actions, false).none { it.scope == DesktopMessageActionScope.SEGMENT })
        assertEquals(listOf(DesktopMessageAction.COPY, DesktopMessageAction.COPY),
            desktopMessageContextActions(listOf(DesktopMessageAction.COPY), true).map { it.action })
        assertTrue(desktopMessageContextActions(emptyList(), true).isEmpty())
    }

    @Test fun `pointer segment menus copy exact source or whole and keep edit delete ownership`() = runBlocking(awt) {
        val fixture = DesktopAssistantImageActionTest.Fixture()
        try {
            fixture.initialize()
            val c = fixture.c
            val message = fixture.source.copy(content = "First<n=\"Alice\"/>[Middle]()『Last』")
            val state = DesktopPrimaryChatState(selectedSession = c.chatRepository.getSession("session"),
                selectedCharacter = c.characterRepository.getById("card"), messages = listOf(message),
                assistantSegmentedBubblesEnabled = true)
            val platform = MemoryClipboard()
            val clipboard = DesktopSafeClipboard(platform)
            val wholeActions = mutableListOf<DesktopMessageAction>()
            val edited = mutableListOf<DesktopPresentedSegment>()
            val deleted = mutableListOf<DesktopPresentedSegment>()
            val actions = listOf(DesktopMessageAction.COPY, DesktopMessageAction.EDIT, DesktopMessageAction.DELETE,
                DesktopMessageAction.REGENERATE)
            val scene = ImageComposeScene(800, 700) {
                val scope = rememberCoroutineScope()
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    PrimaryMessageBubble(message, state, c.primaryChatController, clipboard, actions,
                        onAction = { action ->
                            wholeActions += action
                            if (action == DesktopMessageAction.COPY) scope.launch {
                                clipboard.copyText(desktopWholeMessageCopyText(message, state.selectedSession,
                                    state.selectedCharacter, state.globalPlayerName))
                            }
                        }, onEditSegment = { edited += it }, onDeleteSegment = { deleted += it })
                }
            }
            try {
                scene.until { scene.nodes().any { it.label("Middle") } }
                suspend fun open(body: String) {
                    scene.pointer(scene.nodes().first { it.label(body) }.boundsInRoot.center, PointerButton.Secondary)
                    scene.until { scene.nodes().any { it.label("整条 · 复制") } }
                    // One selection-owned popup, one item per scoped action, ordered by group.
                    val labels = listOf("回复 · 重新生成", "本段 · 复制", "本段 · 编辑", "本段 · 删除",
                        "整条 · 复制", "整条 · 编辑", "整条 · 删除")
                    val positions = labels.map { label -> scene.nodes().single { it.label(label) }.boundsInRoot.top }
                    assertEquals(positions.sorted(), positions)
                }
                suspend fun choose(label: String) {
                    scene.pointer(scene.nodes().single { it.label(label) }.boundsInRoot.center, PointerButton.Primary)
                    scene.until { scene.nodes().none { it.label("整条 · 复制") } }
                }
                open("Middle"); choose("本段 · 复制")
                scene.until { platform.values.size == 1 }
                assertEquals("<n=\"Alice\"/>[Middle]()", platform.values.last())
                assertTrue(wholeActions.isEmpty())
                open("『Last』"); choose("本段 · 复制")
                scene.until { platform.values.size == 2 }
                assertEquals("『Last』", platform.values.last())
                open("『Last』"); choose("整条 · 复制")
                scene.until { platform.values.size == 3 }
                assertEquals(message.displayContent, platform.values.last())
                open("Middle"); choose("本段 · 编辑")
                open("『Last』"); choose("本段 · 删除")
                assertEquals(parseRoleplayTextSegments(message.displayContent)[1], edited.single().source)
                assertEquals(parseRoleplayTextSegments(message.displayContent)[2], deleted.single().source)
                open("Middle"); choose("整条 · 编辑")
                open("『Last』"); choose("整条 · 删除")
                open("『Last』"); choose("回复 · 重新生成")
                assertEquals(listOf(DesktopMessageAction.COPY, DesktopMessageAction.EDIT,
                    DesktopMessageAction.DELETE, DesktopMessageAction.REGENERATE), wholeActions)
                // Native selected-text Clipboard entries remain independent of either source-copy strategy.
                clipboard.setClipEntry(ClipEntry(java.awt.datatransfer.StringSelection("idd")))
                assertEquals("idd", platform.values.last())
            } finally { scene.close() }
        } finally { fixture.close() }
    }

    @Test fun `narrow toolbar scrolls left actions while image actions stay pinned right`() = runBlocking(awt) {
        var generated = 0
        var requirements = 0
        var direction = LayoutDirection.Ltr
        var scrollScope: CoroutineScope? = null
        val scene = ImageComposeScene(110, 100) {
            direction = LocalLayoutDirection.current
            scrollScope = rememberCoroutineScope()
            DesktopChatMessageToolbar(listOf(DesktopMessageAction.COPY, DesktopMessageAction.REGENERATE,
                DesktopMessageAction.EDIT, DesktopMessageAction.DELETE), {}) {
                DesktopChatIconAction("生成图片", DesktopAppIcons.ImageAdd) { generated++ }
                DesktopChatIconAction("生图要求", DesktopAppIcons.Settings) { requirements++ }
            }
        }
        try {
            scene.until { scene.nodes().any { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue?.invoke()?.let { max -> max > 0 } == true } }
            val scroll = scene.nodes().single { it.config.getOrNull(SemanticsActions.ScrollByOffset) != null &&
                (it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f }
            val range = scroll.config[SemanticsProperties.HorizontalScrollAxisRange]
            val initial = range.value()
            println("C2 initial value=$initial max=${range.maxValue()} reverse=${range.reverseScrolling} direction=$direction")
            val originalY = scene.nodes().single { it.label("复制整条") }.boundsInRoot.center.y
            // This suspend action reports actual consumption, unlike ScrollBy's acceptance Boolean.
            // The scene scope supplies MonotonicFrameClock. Keep rendering while the action is suspended;
            // awaiting it alone on the AWT dispatcher can starve frames.
            val request = checkNotNull(scrollScope).async(start = CoroutineStart.UNDISPATCHED) {
                scroll.config[SemanticsActions.ScrollByOffset].invoke(Offset(1000f, 0f))
            }
            var previous = initial
            var changes = 0
            try {
                scene.until {
                    val current = range.value()
                    if (current != previous) {
                        if (changes < 12) println("C2 scrolling value=$current max=${range.maxValue()}")
                        changes++
                        previous = current
                    }
                    request.isCompleted
                }
                val consumed = request.await()
                scene.until { kotlin.math.abs(range.value() - range.maxValue()) <= 0.5f }
                println("C2 completed consumed=$consumed value=${range.value()} max=${range.maxValue()} changes=$changes")
                assertTrue(consumed.x > 0f, "No positive scroll consumed: $consumed")
                assertTrue(range.value() > initial, "Toolbar did not move forward")
            } finally {
                println("C2 final value=${range.value()} max=${range.maxValue()} completed=${request.isCompleted}")
                request.cancel()
            }
            for (label in listOf("生成图片", "生图要求")) {
                val bounds = scene.nodes().single { it.label(label) }.boundsInRoot
                println("C2 $label bounds=$bounds")
                assertTrue(bounds.left >= 0 && bounds.right <= 110, "$label: $bounds")
                assertEquals(originalY, bounds.center.y, 1f)
                scene.pointer(bounds.center, PointerButton.Primary)
            }
            scene.until { generated == 1 && requirements == 1 }
        } finally { scene.close() }
    }

    @Test fun `combined toolbar disables image controls during source task and keeps one process card`() = runBlocking(awt) {
        val fixture = DesktopAssistantImageActionTest.Fixture()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.initialize()
            val c = fixture.c
            val taskId = c.taskRuntime.launchNovelAi("fixture", "session", fixture.source.id) {
                it.designSnapshot("【研究】\nsynthetic progress")
                release.await()
            }
            val state = DesktopPrimaryChatState(selectedSession = c.chatRepository.getSession("session"),
                messages = listOf(fixture.source), assistantSegmentedBubblesEnabled = false)
            val scene = ImageComposeScene(700, 500) {
                PrimaryMessageBubble(fixture.source, state, c.primaryChatController, DesktopSafeClipboard(MemoryClipboard()),
                    actions = listOf(DesktopMessageAction.COPY))
            }
            try {
                scene.until { scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "image-process-card-$taskId" } }
                val copy = scene.nodes().single { it.label("复制整条") }.boundsInRoot
                for (label in listOf("生成图片", "生图要求")) {
                    val node = scene.nodes().single { it.label(label) }
                    assertTrue(node.config.contains(SemanticsProperties.Disabled))
                    assertEquals(copy.center.y, node.boundsInRoot.center.y, 1f)
                    scene.pointer(node.boundsInRoot.center, PointerButton.Primary)
                }
                val cards = scene.nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("image-process-card-") == true }
                assertEquals(1, cards.size)
                assertTrue(cards.single().boundsInRoot.top >= copy.bottom)
                assertEquals(listOf(taskId), c.taskRuntime.tasks.value.map { it.taskId })
                assertTrue(fixture.requests.isEmpty())
                assertTrue(fixture.design.requests.isEmpty())
            } finally { scene.close(); release.complete(Unit); fixture.terminal(taskId) }
        } finally { release.complete(Unit); fixture.close() }
    }

    private class MemoryClipboard : Clipboard {
        val values = mutableListOf<String>()
        override val nativeClipboard: Any get() = error("Must use the isolated clipboard")
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            values += clipEntry!!.asAwtTransferable!!.getTransferData(DataFlavor.stringFlavor) as String
        }
    }
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(value: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
    private suspend fun ImageComposeScene.until(predicate: () -> Boolean) = withTimeout(5000) {
        // ImageComposeScene.render() defaults to time zero; animated semantic actions need advancing frames.
        do { render(System.nanoTime()).close(); yield() } while (!predicate())
    }
    private fun ImageComposeScene.pointer(point: Offset, button: PointerButton) {
        sendPointerEvent(PointerEventType.Press, point, button = button)
        sendPointerEvent(PointerEventType.Release, point, button = button)
    }
}
