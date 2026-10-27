package com.example.chatbar.desktop

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.example.chatbar.domain.chat.roleplayDialogueMarkerPattern
import com.example.chatbar.domain.chat.stripRoleplaySpeakerMarkers
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// The baseline's CommonMark core only: no GFM tables, strikethrough or URL autolinking plugin.
private val desktopCommonMark = Parser.builder().build()
private val desktopCommonMarkHtml = HtmlRenderer.builder().build()
private const val dialogueColorMarker = '\u200B'

/** Android 1.4.1 preprocessing, including its pre-parse treatment of markers inside code. */
internal fun desktopMarkdownInput(source: String): String {
    val visible = desktopStripMarkdownComments(stripRoleplaySpeakerMarkers(source))
    val breaks = Regex("(?<!\n)\n(?!\n)").replace(visible, "  \n")
    return roleplayDialogueMarkerPattern.replace(breaks) { match ->
        val body = match.groupValues[1]
        val annotation = match.groupValues[2]
        if (annotation.isEmpty()) "$dialogueColorMarker$body$dialogueColorMarker" else "[$body]($annotation)"
    }
}

/** Complete nested comments disappear; incomplete streaming comments remain literal. */
private fun desktopStripMarkdownComments(source: String): String {
    val result = StringBuilder(source.length)
    var cursor = 0
    while (cursor < source.length) {
        val open = source.indexOf("<!--", cursor)
        if (open < 0) { result.append(source, cursor, source.length); break }
        var depth = 1
        var search = open + 4
        var closedAt = -1
        while (search < source.length) {
            val nextOpen = source.indexOf("<!--", search)
            val nextClose = source.indexOf("-->", search)
            if (nextClose < 0) break
            if (nextOpen >= 0 && nextOpen < nextClose) { depth++; search = nextOpen + 4 }
            else {
                depth--; search = nextClose + 3
                if (depth == 0) { closedAt = search; break }
            }
        }
        if (closedAt < 0) { result.append(source, cursor, source.length); break }
        val lineStart = source.lastIndexOf('\n', open - 1).let { if (it < 0) 0 else it + 1 }
        fun horizontal(c: Char) = c == ' ' || c == '\t' || c == '\r'
        var lineEnd = closedAt
        while (lineEnd < source.length && horizontal(source[lineEnd])) lineEnd++
        val standalone = source.substring(lineStart, open).all(::horizontal) &&
            (lineEnd == source.length || source[lineEnd] == '\n')
        result.append(source, cursor, if (standalone) lineStart else open)
        cursor = if (standalone && lineEnd < source.length) lineEnd + 1 else closedAt
    }
    return result.toString()
}

/** Pure local rendering. Neither parser fetches resources; no URL/LinkAnnotation or click handler is emitted. */
internal fun desktopMarkdown(
    text: String,
    typography: DesktopChatTypography = DesktopChatTypography(1f),
    accentColor: Color = Color.Unspecified,
    bodyFontSize: Float = 14f,
): AnnotatedString {
    val input = desktopMarkdownInput(text)
    val html = desktopCommonMarkHtml.render(desktopCommonMark.parse(input))
    val body = Jsoup.parseBodyFragment(html).body()
    val out = AnnotatedString.Builder()
    var trailingNewlines = 0
    fun emit(value: String) {
        out.append(value)
        trailingNewlines = if (value.all { it == '\n' }) trailingNewlines + value.length
            else value.takeLastWhile { it == '\n' }.length
    }
    fun breaks(count: Int) {
        if (out.length > 0) emit("\n".repeat((count - trailingNewlines).coerceAtLeast(0)))
    }
    fun headingSize(level: Int) = typography.size(
        (bodyFontSize * listOf(2f, 1.5f, 1.17f, 1f, .83f, .67f)[level - 1]).sp)
    fun heading(level: Int) = SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(level))
    fun render(node: Node, pre: Boolean = false, listDepth: Int = 0) {
        if (node is TextNode) {
            val raw = if (!pre && (node.previousSibling() as? Element)?.normalName() == "br")
                node.wholeText.removePrefix("\n") else node.wholeText
            // HtmlRenderer inserts whitespace between block elements, not visible text.
            if (pre || raw.isNotBlank() || node.parent() is Element &&
                (node.parent() as Element).normalName() !in setOf("body", "ul", "ol", "blockquote")) emit(raw)
            return
        }
        if (node !is Element) return // Comments and document metadata are never displayed.
        val tag = node.normalName()
        if (tag in setOf("script", "style", "iframe", "object", "embed", "head")) return
        if (tag == "br") { emit("\n"); return }
        if (tag == "img") { emit(node.attr("alt")); return } // No image loader in Android's configured plugins.
        if (tag == "hr") { breaks(2); emit("―"); breaks(2); return }
        val block = tag in setOf("p", "div", "pre", "blockquote", "ul", "ol") || tag.matches(Regex("h[1-6]"))
        if (block && !(tag == "p" && node.parent()?.normalName() == "blockquote"))
            breaks(if (tag == "p" && node.parent()?.normalName() == "li") 1 else 2)
        if (tag == "li") {
            breaks(1)
            repeat((listDepth - 1).coerceAtLeast(0)) { emit("  ") }
            val parent = node.parent()
            if (parent?.normalName() == "ol") {
                val start = parent.attr("start").toIntOrNull() ?: 1
                val index = parent.children().filter { it.normalName() == "li" }.indexOf(node)
                emit("${start + index}. ")
            } else emit("• ")
        }
        if (tag == "blockquote") emit("❝ ")
        val style = when (tag) {
            "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
            "i", "em", "cite", "dfn" -> SpanStyle(fontStyle = FontStyle.Italic)
            // Baseline declares the strike extension but does not install its span factory.
            "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
            "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = typography.size((bodyFontSize * .75f).sp))
            "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = typography.size((bodyFontSize * .75f).sp))
            "a" -> SpanStyle(color = accentColor) // Intentionally no underline or clickable annotation.
            "code", "pre" -> SpanStyle(fontFamily = FontFamily.Monospace)
            "h1", "h2", "h3", "h4", "h5", "h6" -> heading(tag.last().digitToInt())
            else -> null
        }
        val start = out.length
        node.childNodes().forEach { render(it, pre || tag == "pre", listDepth + if (tag == "ul" || tag == "ol") 1 else 0) }
        if (style != null && out.length > start) out.addStyle(style, start, out.length)
        if (tag.matches(Regex("h[1-6]")) && out.length > start) {
            // A wrapped heading must not inherit a smaller F1 body line height.
            out.addStyle(ParagraphStyle(lineHeight = headingSize(tag.last().digitToInt()) * 1.2f), start, out.length)
        }
        if (tag == "li") breaks(1)
        else if (block) breaks(if (tag == "p" && node.parent()?.normalName() == "li") 1 else 2)
    }
    body.childNodes().forEach { render(it) }
    val rendered = out.toAnnotatedString()
    val end = rendered.text.trimEnd('\n').length
    val compact = AnnotatedString.Builder()
    val offsets = IntArray(end + 1)
    var accentStart: Int? = null
    for (index in 0 until end) {
        offsets[index] = compact.length
        if (rendered[index] == dialogueColorMarker) {
            val start = accentStart
            if (start == null) accentStart = compact.length
            else { if (start < compact.length) compact.addStyle(SpanStyle(color = accentColor), start, compact.length); accentStart = null }
        } else compact.append(rendered[index])
    }
    offsets[end] = compact.length
    rendered.spanStyles.forEach { range ->
        val start = offsets[range.start.coerceAtMost(end)]
        val finish = offsets[range.end.coerceAtMost(end)]
        if (start < finish) compact.addStyle(range.item, start, finish)
    }
    rendered.paragraphStyles.forEach { range ->
        val start = offsets[range.start.coerceAtMost(end)]
        val finish = offsets[range.end.coerceAtMost(end)]
        if (start < finish) compact.addStyle(range.item, start, finish)
    }
    return compact.toAnnotatedString()
}

@Composable
internal fun DesktopMarkdownText(
    text: String,
    color: Color = DesktopBootstrapColors.foreground,
    typography: DesktopChatTypography = DesktopChatTypography(1f),
    textStyle: TextStyle = TextStyle(fontSize = 14.sp),
) {
    val accent = DesktopBootstrapColors.primary
    val rendered = remember(text, typography, accent, textStyle.fontSize) {
        desktopMarkdown(text, typography, accent, textStyle.fontSize.value)
    }
    BasicText(text = rendered, style = typography.style(textStyle.copy(color = color)))
}
