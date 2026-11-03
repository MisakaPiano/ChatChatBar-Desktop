package com.example.chatbar.desktop

import com.example.chatbar.domain.chat.RoleplaySegmentKind
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
    segment.kind != RoleplaySegmentKind.STATUS ||
        if (desktopScreenshotActionBlock(segment)) options.includeActions else options.includeStatus

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
