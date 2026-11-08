@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.semantics.*
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopChatCompactStatusAndVersionsTest {
    private val edt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private val clipboard = DesktopSafeClipboard(object : Clipboard {
        override val nativeClipboard: Any get() = error("No native clipboard in synthetic layout check")
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
    })
    private suspend fun ImageComposeScene.frames() { repeat(5) { render(System.nanoTime()).close(); delay(15) } }
    private suspend fun ImageComposeScene.until(predicate: () -> Boolean) { withTimeout(10000) { while (!predicate()) frames() } }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(text: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription) == listOf(text)
    private fun ImageComposeScene.action(text: String) = nodes().single { it.label(text) && it.config.contains(SemanticsActions.OnClick) }

    @Test fun `status caption uses only explicit block ownership with localized fallback and no preceding-speaker inference`() {
        val zh = DesktopUiStrings(DesktopUiLanguage.ZH_CN); val en = DesktopUiStrings(DesktopUiLanguage.EN)
        fun status(text: String) = desktopPresentMessage(
            com.example.chatbar.data.local.entity.ChatMessage.create("synthetic", com.example.chatbar.data.local.entity.MessageRole.ASSISTANT,
                "<n=\"其他人\"/>[此前发言]()\n```status\n$text\n```"), "User", "Card", null, true)
            .segments.single { it.kind == RoleplaySegmentKind.STATUS }
        val named = status("👤姓名：小宁\n位置：旅馆大厅")
        assertEquals("状态栏：小宁", desktopStatusDisclosureLabel(named, zh))
        assertEquals("Status: 小宁", desktopStatusDisclosureLabel(named, en))
        assertEquals("状态栏", desktopStatusDisclosureLabel(status("位置：旅馆大厅\n当前动作：整理地图"), zh))
        assertEquals("状态栏", desktopStatusDisclosureLabel(status("姓名：小宁\n姓名：阿岚\n位置：大厅"), zh))
        assertEquals("状态栏", desktopStatusDisclosureLabel(named.copy(source = null), zh))
    }

    @Test fun `real bubble has compact named statuses and a single version group after regenerate on the same footer row`() = runBlocking(edt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize(); val controller = f.c.primaryChatController
            val body = "<n=\"其他人\"/>[请看地图。]()\n```status\n姓名：小宁\n位置：旅馆大厅\n```\n" +
                "```status\n姓名：阿岚\n位置：渡口\n```\n```行动选项\n1. 查看地图\n2. 等待同伴\n```"
            val second = "<n=\"其他人\"/>[第二份回复。]()\n```status\n姓名：小宁\n位置：渡口\n```"
            f.c.chatRepository.updateMessage(f.source.copy(content = body, alternatives = listOf(body, second), currentAlternativeIndex = 0))
            controller.refresh()
            val strings = DesktopUiStrings(DesktopUiLanguage.ZH_CN)
            for (width in listOf(360, 800)) {
                val scene = ImageComposeScene(width, 650) {
                    val state by controller.state.collectAsState()
                    state.messages.firstOrNull { it.id == f.source.id }?.let { message ->
                        PrimaryMessageBubble(message, state.copy(assistantSegmentedBubblesEnabled = true), controller, clipboard,
                            actions = listOf(DesktopMessageAction.COPY, DesktopMessageAction.REGENERATE))
                    }
                }
                try {
                    scene.until { scene.nodes().any { it.label("状态栏：阿岚") } }
                    for (name in listOf("小宁", "阿岚")) {
                        val caption = scene.nodes().single { it.label("状态栏：$name") }
                        val surface = generateSequence(caption) { it.parent }.first {
                            it.config.getOrNull(SemanticsProperties.TestTag)?.matches(Regex("message-segment-\\d+")) == true }
                        assertTrue(surface.boundsInRoot.height <= 32f)
                    }
                    assertTrue(scene.nodes().any { it.label("行动选项") })
                    assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("状态 / 选项") } == true })
                    val copy = scene.action(strings(DesktopMessageAction.COPY.label))
                    val regenerate = scene.action(strings(DesktopMessageAction.REGENERATE.label))
                    val previous = scene.action(strings(DesktopUiText.PREVIOUS_ALTERNATIVE))
                    val next = scene.action(strings(DesktopUiText.NEXT_ALTERNATIVE))
                    val count = scene.nodes().single { it.label("1 / 2") }
                    val more = scene.action(strings(DesktopUiText.MORE_MESSAGE_ACTIONS))
                    val xs = listOf(copy, regenerate, previous, count, next, more).map { it.boundsInRoot.center.x }
                    assertEquals(xs.sorted(), xs)
                    for (node in listOf(regenerate, previous, count, next, more)) assertEquals(copy.boundsInRoot.center.y, node.boundsInRoot.center.y, 1f)
                    assertTrue(previous.config.contains(SemanticsProperties.Disabled))
                    assertFalse(next.config.contains(SemanticsProperties.Disabled))
                    System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { dir ->
                        val p = java.nio.file.Path.of(dir).resolve("chat-status-version-$width.png"); java.nio.file.Files.createDirectories(p.parent)
                        scene.render(System.nanoTime()).use { it.encodeToData()!!.use { image -> java.nio.file.Files.write(p, image.bytes) } }
                    }
                    assertTrue(next.config[SemanticsActions.OnClick].action!!.invoke())
                    scene.until { controller.state.value.messages.firstOrNull { it.id == f.source.id }?.currentAlternativeIndex == 1 }
                    scene.until { scene.nodes().any { it.label("2 / 2") } }
                    assertTrue(scene.action(strings(DesktopUiText.NEXT_ALTERNATIVE)).config.contains(SemanticsProperties.Disabled))
                    assertTrue(scene.action(strings(DesktopUiText.PREVIOUS_ALTERNATIVE)).config[SemanticsActions.OnClick].action!!.invoke())
                    scene.until { controller.state.value.messages.firstOrNull { it.id == f.source.id }?.currentAlternativeIndex == 0 }
                    assertTrue(f.requests.isEmpty())
                } finally { scene.close() }
            }
        } finally { f.close() }
    }
}
