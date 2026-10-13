@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.example.chatbar.desktop

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopMarkdownParityTest {
    private val accent = Color(0xFF126744)
    private fun render(text: String) = desktopMarkdown(text, accentColor = accent)
    private fun AnnotatedString.stylesAt(text: String) = spanStyles.filter {
        it.start <= this.text.indexOf(text) && it.end >= this.text.indexOf(text) + text.length
    }.map { it.item }

    @Test fun `CommonMark nested inline styles escapes and unicode retain exact ranges`() {
        val shown = render("**bold *深层😀* end** and `code` \\*literal\\* &amp; é")
        assertEquals("bold 深层😀 end and code *literal* & é", shown.text)
        assertTrue(shown.stylesAt("深层😀").any { it.fontWeight == FontWeight.Bold })
        assertTrue(shown.stylesAt("深层😀").any { it.fontStyle == FontStyle.Italic })
        assertTrue(shown.stylesAt("code").any { it.fontFamily == FontFamily.Monospace })
        assertTrue(shown.stylesAt("*literal*").none { it.fontStyle == FontStyle.Italic })
    }

    @Test fun `headings paragraphs lists quotes and hard breaks use CommonMark structure`() {
        val shown = render("# Title\n\nFirst\nsecond\n\n- one\n- two\n\n3. third\n4. fourth\n\n> quote")
        assertEquals("Title\n\nFirst\nsecond\n\n• one\n• two\n\n3. third\n4. fourth\n\n❝ quote", shown.text)
        assertEquals(28.sp, shown.stylesAt("Title").single().fontSize)
    }

    @Test fun `baseline dialogue annotation and ordinary links have accent without interaction`() {
        val shown = render("<n=\"Alice\"/>[对白]() ［全角］（） [低语](soft) [docs](https://example.test)")
        assertEquals("对白 全角 低语 docs", shown.text)
        for (label in listOf("对白", "全角", "低语", "docs")) {
            assertTrue(shown.stylesAt(label).any { it.color == accent }, label)
            assertTrue(shown.stylesAt(label).none { it.textDecoration == TextDecoration.Underline }, label)
        }
        assertTrue(shown.getStringAnnotations(0, shown.length).isEmpty())
        assertFalse(shown.text.contains('\u200B'))
    }

    @Test fun `official preprocessing preserves annotation normalization and incomplete comments`() {
        assertEquals("\u200B全角\u200B [混合](低声)", desktopMarkdownInput("［全角］（） [混合]（低声)"))
        assertEquals("\u200B半角\u200B \u200B全角\u200B \u200B混合\u200B", desktopMarkdownInput("[半角] ［全角］ [混合］"))
        assertEquals("before <!-- hidden", desktopMarkdownInput("before <!-- hidden"))
        assertEquals("！［图片］（image.png） ![半角图片]", desktopMarkdownInput("！［图片］（image.png） ![半角图片]"))
    }

    @Test fun `complete nested and standalone comments never become visible text`() {
        assertEquals("before  after", render("before <!-- outer <!-- inner --> tail --> after").text)
        assertEquals("before\nafter", render("before\n<!-- outer\n<!-- inner -->\ntail\n-->\nafter").text)
        assertEquals("a  b  c", render("a <!-- one --> b <!-- two --> c").text)
    }

    @Test fun `finite HTML styles entities and breaks are local and noninteractive`() {
        val shown = render("<b>bold <i>nested</i></b> <u>under</u> <del>removed</del><br>H<sub>2</sub> &amp; <a href='https://example.test'>link</a>")
        assertEquals("bold nested under removed\nH2 & link", shown.text)
        assertTrue(shown.stylesAt("nested").any { it.fontWeight == FontWeight.Bold })
        assertTrue(shown.stylesAt("nested").any { it.fontStyle == FontStyle.Italic })
        assertTrue(shown.stylesAt("under").any { it.textDecoration == TextDecoration.Underline })
        assertTrue(shown.stylesAt("removed").none { it.textDecoration == TextDecoration.LineThrough })
        assertTrue(shown.stylesAt("link").any { it.color == accent })
        assertTrue(shown.stylesAt("link").none { it.textDecoration == TextDecoration.Underline })
        assertTrue(render("<script>secret()</script><p>safe</p><img src='https://invalid.test/a.png' alt='image'>").text.contains("safe"))
        assertFalse(render("<script>secret()</script>").text.contains("secret"))
        assertEquals("image", render("![image](https://invalid.test/a.png)").text)
    }

    @Test fun `unregistered GFM syntax stays literal and code follows official preprocessing`() {
        assertEquals("~~gone~~", render("~~gone~~").text)
        assertTrue(render("a | b\n--- | ---\nx | y").text.contains("a | b"))
        val code = render("```text\n**literal** [voice]()\n```")
        assertEquals("**literal** voice  ", code.text)
        assertTrue(code.stylesAt("**literal**").any { it.fontFamily == FontFamily.Monospace })
        assertTrue(code.stylesAt("**literal**").none { it.fontWeight == FontWeight.Bold })
        assertEquals("**open", render("**open").text)
        assertEquals("", render("").text)
    }

    @Test fun `display source survives both message modes while original copy and status stay untouched`() {
        val raw = "<n=\"Alice\"/>[Hello]()\n```status\n**literal** [state]()\n```"
        val message = ChatMessage.create("s", MessageRole.ASSISTANT, raw)
        for (segmented in listOf(true, false)) {
            val shown = desktopPresentMessage(message, null, "Player", segmented)
            val body = shown.segments.first()
            assertTrue(render(body.markdownSource).stylesAt("Hello").any { it.color == accent })
            val status = shown.segments.last()
            assertFalse(status.statusDefaultExpanded)
            // The status panel receives literal content, never this renderer.
            assertTrue(status.markdownSource.contains("**literal**"))
            assertEquals("**literal** [state]()", status.text.trim())
            assertEquals(raw, desktopWholeMessageCopyText(message, null, null, "Player"))
            assertEquals(raw, message.displayContent)
        }
    }

    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `Compose renders scaled headings and independent body styles without clickable links`() = runBlocking(awt) {
        for (size in listOf(12f, 13f, 14f)) {
            val heading = "Head ".repeat(6).trim()
            val scene = ImageComposeScene(260, 600) {
                SelectionContainer { DesktopMarkdownText("# $heading\n\nBody [link](https://example.test)",
                    typography = DesktopChatTypography(1.5f),
                    textStyle = TextStyle(fontSize = size.sp, lineHeight = (size + 2).sp)) }
            }
            try {
                var text: SemanticsNode? = null
                withTimeout(5000) {
                    while (text == null) {
                        scene.render(System.nanoTime()).close(); yield()
                        text = scene.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains("Body link") } == true }
                    }
                }
                val node = requireNotNull(text)
                val result = mutableListOf<TextLayoutResult>()
                assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(result))
                assertEquals((size * 1.5f).sp, result.single().layoutInput.style.fontSize)
                assertEquals((size * 3f).sp, result.single().layoutInput.text.stylesAt("Head").single().fontSize)
                assertTrue(result.single().getLineEnd(0) < heading.length, "Heading must actually wrap")
                assertTrue(result.single().getLineBottom(0) - result.single().getLineTop(0) >= size * 3f,
                    "Wrapped heading lines must accommodate their enlarged font")
                assertFalse(result.single().hasVisualOverflow)
                assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsActions.OnClick) != null })
            } finally { scene.close() }
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
}
