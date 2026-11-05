package com.example.chatbar.desktop

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

internal data class DesktopWordTextRun(val text: String, val bold: Boolean = false,
    val italic: Boolean = false, val code: Boolean = false, val fontSizeSp: Float? = null)

/** Consume the real chat Markdown projection; never add a second parser or fetch remote resources. */
internal fun desktopWordMarkdown(source: String, right: Boolean): List<DesktopScreenshotDocumentBlock.Text> {
    val shown = desktopMarkdown(source)
    if (shown.text.isBlank()) return emptyList()
    val boundaries = (listOf(0, shown.length) + shown.spanStyles.flatMap { listOf(it.start, it.end) }).distinct().sorted()
    val runs = boundaries.zipWithNext().mapNotNull { (start, end) ->
        if (start == end) return@mapNotNull null
        val styles = shown.spanStyles.filter { it.start <= start && it.end >= end }.map { it.item }
        val size = styles.map { it.fontSize }.lastOrNull { it.isSp }?.value
        DesktopWordTextRun(shown.text.substring(start, end),
            bold = styles.any { (it.fontWeight?.weight ?: 0) >= FontWeight.SemiBold.weight },
            italic = styles.any { it.fontStyle == FontStyle.Italic },
            code = styles.any { it.fontFamily == FontFamily.Monospace }, fontSizeSp = size)
    }
    return listOf(DesktopScreenshotDocumentBlock.Text(shown.text, right = right, runs = runs))
}
