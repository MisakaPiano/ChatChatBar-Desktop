package com.example.chatbar.desktop

import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.roleplayLongDashRawRanges
import com.example.chatbar.domain.chat.roleplaySpeakerHeaderIndexes
import com.example.chatbar.domain.chat.roleplayImageBlockId
import com.example.chatbar.domain.chat.roleplayLegacyTextBlockId
import com.example.chatbar.data.local.entity.ChatMessage

/** Export-local choices; all default on, and included blocks are always fully expanded. */
internal data class DesktopScreenshotContentOptions(
    val includeStatus: Boolean = true,
    val includeActions: Boolean = true,
    val includeBackground: Boolean = true,
)

/** Only classify the existing parsed STATUS blocks and explicit headings, never narration keywords. */
internal fun desktopScreenshotActionBlock(segment: DesktopPresentedSegment): Boolean {
    if (segment.kind != RoleplaySegmentKind.STATUS) return false
    val source = segment.source ?: return false
    val header = source.rawText.trimStart().lineSequence().firstOrNull().orEmpty()
        .takeIf { it.startsWith("```") }?.removePrefix("```").orEmpty()
    return desktopIsActionHeading(header) || desktopIsActionHeading(source.displayText.lineSequence().firstOrNull().orEmpty())
}

internal fun desktopScreenshotIncludes(segment: DesktopPresentedSegment, options: DesktopScreenshotContentOptions): Boolean =
    if (segment.kind == RoleplaySegmentKind.STATUS) {
        if (desktopScreenshotActionBlock(segment)) options.includeActions else options.includeStatus
    } else options.includeActions || segment.text.isNotBlank() || segment.markdownSource.isNotBlank()

internal fun desktopScreenshotStatusLabel(segment: DesktopPresentedSegment): String =
    if (desktopScreenshotActionBlock(segment)) "行动选项" else "状态栏"

/** An excluded-only reply must not leave an empty bubble/header or read its unused avatar. */
internal fun desktopScreenshotMessageVisible(message: ChatMessage, presented: DesktopPresentedMessage,
    selected: Set<String>, options: DesktopScreenshotContentOptions): Boolean =
    message.images.indices.any { roleplayImageBlockId(message.id, it) in selected } ||
        presented.segments.any { segment ->
            val id = if (presented.segmented) segment.blockId else roleplayLegacyTextBlockId(message.id)
            id in selected && desktopScreenshotIncludes(segment, options)
        }

/** Preserve shared group boundaries while giving a selected later sentence its own visible header. */
internal fun desktopScreenshotSpeakerHeaders(presented: DesktopPresentedMessage, selected: Set<String>,
    options: DesktopScreenshotContentOptions): Set<Int> {
    if (!presented.segmented) return emptySet()
    val sources = presented.segments.map { it.source ?: return presented.speakerHeaderIndexes }
    val visible = presented.segments.indices.filterTo(mutableSetOf()) { index ->
        val segment = presented.segments[index]
        segment.blockId in selected && desktopScreenshotIncludes(segment, options)
    }
    return roleplaySpeakerHeaderIndexes(sources, visible)
}

/** Reuse the shared long-dash narration boundaries; only explicit action lists are excluded. */
internal fun desktopScreenshotWithoutInlineActions(text: String): String {
    val numbered = Regex("^([1-9][0-9]?)[.、)）][ \\t]*(.+)$")
    fun rule(line: String): Boolean = line.trim().let { it.length >= 3 && it.all { c -> c in "-─━—" } }
    val excluded = roleplayLongDashRawRanges(text).filter { range ->
        val lines = text.substring(range).lines().filter(String::isNotBlank).toMutableList()
        if (lines.firstOrNull()?.let(::rule) == true) lines.removeAt(0)
        if (lines.lastOrNull()?.let(::rule) == true) lines.removeAt(lines.lastIndex)
        if (lines.firstOrNull()?.let(::desktopIsActionHeading) != true) return@filter false
        var ordinal = 1
        for (line in lines.drop(1)) {
            val match = numbered.matchEntire(line.trim())
            if (match != null && match.groupValues[1].toIntOrNull() == ordinal && ordinal <= 20) ordinal++
            else if (ordinal <= 1 || !(line.startsWith("  ") || line.startsWith("\t"))) return@filter false
        }
        ordinal >= 3
    }
    return excluded.sortedByDescending { it.first }.fold(text) { value, range -> value.removeRange(range) }
}

/** One shared immutable projection for raster, PDF and editable Word export. */
internal fun desktopScreenshotProjectMessage(presented: DesktopPresentedMessage,
    options: DesktopScreenshotContentOptions): DesktopPresentedMessage {
    if (options.includeActions) return presented
    return presented.copy(segments = presented.segments.map { segment ->
        if (segment.kind == RoleplaySegmentKind.STATUS) segment
        else segment.copy(text = desktopScreenshotWithoutInlineActions(segment.text),
            markdownSource = desktopScreenshotWithoutInlineActions(segment.markdownSource))
    })
}
