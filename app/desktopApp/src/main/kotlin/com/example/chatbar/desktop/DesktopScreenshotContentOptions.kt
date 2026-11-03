package com.example.chatbar.desktop

import com.example.chatbar.domain.chat.RoleplaySegmentKind

/** Export-local choices; both default on, and included blocks are always fully expanded. */
internal data class DesktopScreenshotContentOptions(
    val includeStatus: Boolean = true,
    val includeActions: Boolean = true,
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
