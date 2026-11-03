package com.example.chatbar.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*

class DesktopStudioTextHistoryTest {
    private fun DesktopStudioTextHistory.type(text: String) = edit(TextFieldValue(text, TextRange(text.length))) {}

    @Test fun `independent fields multi step undo redo do not touch sibling and accept delayed echoes`() {
        var now = 1_000_000_000L
        val a = DesktopStudioTextHistory("initial", { now })
        val b = DesktopStudioTextHistory("other", { now })
        a.type("first paste"); now += 1_000_000_000; a.type("second paste")
        b.type("sibling paste")
        a.echo("first paste"); assertEquals("second paste", a.value.text)
        a.replay(false) {}; assertEquals("first paste", a.value.text)
        a.replay(false) {}; assertEquals("initial", a.value.text)
        assertEquals("sibling paste", b.value.text)
        a.replay(true) {}; assertEquals("first paste", a.value.text)
        a.type("branch paste"); a.replay(true) {}; assertEquals("branch paste", a.value.text)
    }

    @Test fun `global restoration rebases changed field and refuses stale local redo while unchanged sibling keeps history`() {
        val a = DesktopStudioTextHistory("A"); val b = DesktopStudioTextHistory("B")
        a.type("changed A"); b.type("changed B")
        a.replay(false) {}; a.echo("global result", forced = true)
        a.replay(true) {}; assertEquals("global result", a.value.text)
        b.echo("changed B", forced = true); b.replay(false) {}; assertEquals("B", b.value.text)
    }

    @Test fun `composition selection and expanded cancel preserve original field history`() {
        val a = DesktopStudioTextHistory("raw source")
        a.type("first paste")
        val expanded = a.fork(); expanded.type("expanded draft"); expanded.replay(false) {}
        assertEquals("first paste", expanded.value.text)
        expanded.type("cancel this"); assertEquals("first paste", a.value.text)
        a.replay(false) {}; assertEquals("raw source", a.value.text)
        a.edit(TextFieldValue("拼音", TextRange(2), TextRange(0, 2))) {}
        assertFalse(a.replay(false) {}); assertEquals(TextRange(0, 2), a.value.composition)
    }

    @Test fun `stable role field identities survive reorder and positive negative toggle`() {
        val fields = DesktopStudioTextHistories()
        val a = fields.field("person:a:positive", "A")
        val b = fields.field("person:b:positive", "B")
        a.type("new A"); b.type("new B")
        assertSame(a, fields.field("person:a:positive", "ignored echo"))
        fields.field("person:a:negative", "blur").type("negative paste")
        a.replay(false) {}; assertEquals("A", a.value.text); assertEquals("new B", b.value.text)
    }

    @Test fun `selection move and equal length replacement end the typing group`() {
        val a = DesktopStudioTextHistory("a".repeat(200), { 1_000_000_000L })
        a.type("a".repeat(200) + "b")
        a.type("z".repeat(201))
        a.replay(false) {}; assertEquals("a".repeat(200) + "b", a.value.text)
        a.edit(a.value.copy(selection = TextRange(0))) {}
        a.edit(TextFieldValue("x" + a.value.text, TextRange(1))) {}
        a.replay(false) {}; assertEquals("a".repeat(200) + "b", a.value.text)
    }
}
