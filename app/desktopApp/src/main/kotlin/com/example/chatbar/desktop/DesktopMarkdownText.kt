package com.example.chatbar.desktop

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/** Lightweight Desktop Markdown presentation; the input is already sanitized by shared Roleplay authority. */
internal fun desktopMarkdown(text: String, typography: DesktopChatTypography = DesktopChatTypography(1f)): AnnotatedString {
    val result = AnnotatedString.Builder()
    var inCodeFence = false
    text.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            inCodeFence = !inCodeFence
            return@forEach
        }
        if (result.length > 0) result.append('\n')
        if (inCodeFence) {
            result.withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                append(line)
            }
            return@forEach
        }
        val heading = line.takeWhile { it == '#' }.length.takeIf { it in 1..6 && line.getOrNull(it) == ' ' }
        val body = if (heading == null) line else line.drop(heading + 1)
        if (heading != null) {
            result.withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = typography.size((20 - heading).sp))) {
                appendInlineMarkdown(body)
            }
        } else {
            val quote = body.startsWith("> ")
            val unquoted = if (quote) body.drop(2) else body
            val listBody = when {
                unquoted.startsWith("- ") || unquoted.startsWith("* ") -> "• ${unquoted.drop(2)}"
                else -> unquoted
            }
            if (quote) result.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append("❝ ")
                appendInlineMarkdown(listBody)
            } else result.appendInlineMarkdown(listBody)
        }
    }
    return result.toAnnotatedString()
}

private fun AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    var cursor = 0
    while (cursor < text.length) {
        val marker = when {
            text.startsWith("**", cursor) -> "**"
            text.startsWith("~~", cursor) -> "~~"
            text[cursor] == '`' -> "`"
            text[cursor] == '*' -> "*"
            else -> null
        }
        if (marker != null) {
            val close = text.indexOf(marker, cursor + marker.length)
            if (close > cursor + marker.length) {
                val style = when (marker) {
                    "**" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    "`" -> SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                withStyle(style) { append(text.substring(cursor + marker.length, close)) }
                cursor = close + marker.length
                continue
            }
        }
        if (text[cursor] == '[') {
            val labelEnd = text.indexOf(']', cursor + 1)
            if (labelEnd > cursor + 1 && text.getOrNull(labelEnd + 1) == '(') {
                val targetEnd = text.indexOf(')', labelEnd + 2)
                if (targetEnd >= 0) {
                    withStyle(SpanStyle(color = Color(0xFF3776B6), textDecoration = TextDecoration.Underline)) {
                        append(text.substring(cursor + 1, labelEnd))
                    }
                    cursor = targetEnd + 1
                    continue
                }
            }
        }
        append(text[cursor])
        cursor++
    }
}

@Composable
internal fun DesktopMarkdownText(
    text: String,
    color: Color = DesktopBootstrapColors.foreground,
    typography: DesktopChatTypography = DesktopChatTypography(1f),
) {
    BasicText(
        text = desktopMarkdown(text, typography),
        style = typography.style(TextStyle(color = color, fontSize = 14.sp)),
    )
}
