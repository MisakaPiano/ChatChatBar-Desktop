@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.*

class DesktopActionChoicesTest {
    private fun message(text: String) = ChatMessage.create("session", MessageRole.ASSISTANT, text)
    private val block = "---\n行动选项\n1. 等待回答\n2. 离开房间\n---"

    @Test fun `only explicitly titled closed numbered assistant blocks are actionable`() {
        for (text in listOf(block, "```\n**行动选项：**\n1、等待回答\n2、离开房间\n```",
            "```行动选项\n1) 等待回答\n2) 离开房间\n```")) {
            assertEquals(listOf("等待回答", "离开房间"), desktopActionChoices(message(text)).map { it.text })
        }
        assertTrue(desktopActionChoices(message(block).copy(role = MessageRole.USER)).isEmpty())
        assertTrue(desktopActionChoices(message(block).copy(role = MessageRole.SYSTEM)).isEmpty())
    }

    @Test fun `code links generic status malformed numbering and unfinished fences stay inert`() {
        val rejected = listOf(
            block.removeSuffix("---"), "```\n行动选项\n1. A\n2. B", block.replace("行动选项", "人物状态"),
            block.replace("2.", "3."), block.replace("2.", "1."), block.replace("等待回答", ""),
            block.replace("离开房间", "等待回答"), block.replace("2. 离开房间", "补充说明\n2. 离开房间"),
            block.replace("等待回答", "[链接](https://example.invalid)"),
            block.replace("等待回答", "https://example.invalid"),
            "```python\n行动选项\n1. A\n2. B\n```", "行动选项\n1. A\n2. B",
            "---\n1. A\n2. B\n---", "---\n行动选项\n1. A\n---",
        )
        rejected.forEach { assertTrue(desktopActionChoices(message(it)).isEmpty(), it) }
    }

    @Test fun `current alternative and multiple block positions are the recognition source`() {
        val original = message("No choices").copy(alternatives = listOf("No choices", "Narration\n$block\n$block"),
            currentAlternativeIndex = 1)
        val choices = desktopActionChoices(original)
        assertEquals(4, choices.size)
        assertEquals(2, choices.map { it.blockStart }.distinct().size)
        assertTrue(desktopActionChoices(original.copy(currentAlternativeIndex = 0)).isEmpty())
    }

    @Test fun `insertion retains selected text places cursor and can undo exact original selection`() {
        for (full in listOf(false, true)) {
            val composer = DesktopComposerInput("session", "前文后文")
            if (full) composer.open(true)
            val before = TextFieldValue("前文后文", TextRange(0, 2))
            composer.edit(full, before) { }
            var persisted = before.text
            val persist: (String, String) -> Boolean = { old, next -> assertEquals(persisted, old); persisted = next; true }
            assertTrue(composer.insertChoice("选择", persist))
            assertEquals("前文\n选择\n后文", persisted)
            assertEquals(TextRange(6), composer.input.selection)
            composer.echo(persisted)
            assertTrue(composer.canUndoChoiceInsertion)
            assertTrue(composer.undoChoiceInsertion(persist))
            assertEquals(before, composer.input)
            assertEquals(before.text, persisted)
            assertFalse(composer.canUndoChoiceInsertion)
        }
    }

    @Test fun `multiple insertions undo separately while subsequent manual or external edits invalidate undo`() {
        val composer = DesktopComposerInput("session", "")
        assertTrue(composer.insertChoice("A") { _, _ -> true })
        assertTrue(composer.insertChoice("B") { _, _ -> true })
        assertEquals("A\nB", composer.input.text)
        assertTrue(composer.undoChoiceInsertion { _, _ -> true }); assertEquals("A", composer.input.text)
        composer.edit(false, TextFieldValue("new user edit")) { }
        assertFalse(composer.undoChoiceInsertion { _, _ -> fail("Overwrote user edit") })
        composer.insertChoice("C") { _, _ -> true }
        composer.echo("external change")
        assertFalse(composer.canUndoChoiceInsertion)
    }

    @Test fun `IME absent session stale persistence and rejected undo do not mutate input`() {
        val composing = TextFieldValue("ni", TextRange(2), TextRange(0, 2))
        val composer = DesktopComposerInput("session", "ni")
        composer.edit(false, composing) { }
        assertFalse(composer.insertChoice("A") { _, _ -> fail("IME write") })
        assertSame(composing, composer.input)
        composer.edit(false, composing.copy(composition = null)) { }
        val before = composer.input
        assertFalse(composer.insertChoice("A") { _, _ -> false }); assertSame(before, composer.input)
        assertTrue(composer.insertChoice("A") { _, _ -> true })
        val after = composer.input
        assertFalse(composer.undoChoiceInsertion { _, _ -> false }); assertSame(after, composer.input)
        assertFalse(DesktopComposerInput(null, "").insertChoice("A") { _, _ -> fail("No session") })
    }

    @Test fun `rendered choice buttons only insert and undo a draft`() = runBlocking {
        val choices = desktopActionChoices(message(block))
        val composer = DesktopComposerInput("session", "我的草稿")
        var persisted = composer.input.text
        val persist: (String, String) -> Boolean = { before, after -> assertEquals(persisted, before); persisted = after; true }
        val scene = ImageComposeScene(650, 450) { Column {
            DesktopActionChoiceList(choices, true) { composer.insertChoice(it.text, persist) }
            DesktopChoiceUndo(composer) { composer.undoChoiceInsertion(persist) }
        } }
        suspend fun frames() { repeat(4) { scene.render().close(); yield() } }
        fun nodes(): List<SemanticsNode> {
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        fun click(label: String) {
            var node = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
            while (!node.config.contains(SemanticsActions.OnClick)) node = requireNotNull(node.parent)
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        }
        try {
            frames(); click("1. 等待回答"); frames()
            assertEquals("等待回答\n我的草稿", persisted)
            click("撤销选项填入"); frames()
            assertEquals("我的草稿", persisted)
        } finally { scene.close() }
    }
}
