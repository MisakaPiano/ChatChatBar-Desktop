package com.example.chatbar.desktop

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import kotlin.math.roundToInt

/** Presentation safety only: reading invalid legacy values never writes a migration. */
internal fun desktopSafeBubbleFontScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.5f, 1.5f) else 1.0f

internal fun desktopBubbleFontScaleStep(value: Float): Float =
    (desktopSafeBubbleFontScale(value) * 10).roundToInt() / 10f

/** Explicitly applied only to message content, never to ambient/chrome typography. */
internal class DesktopChatTypography(scale: Float) {
    private val scale = desktopSafeBubbleFontScale(scale)
    fun size(base: TextUnit): TextUnit = base * scale
    fun style(base: TextStyle): TextStyle = base.copy(fontSize = size(base.fontSize),
        lineHeight = if (base.lineHeight == TextUnit.Unspecified) base.lineHeight else size(base.lineHeight))
}

/** Android 1.4.1 ChatBubble's segment geometry and body typography (not Markdown parsing). */
internal data class DesktopSegmentMetrics(
    val widthFraction: Float,
    val fontSize: Int,
    val lineExtra: Float,
    val horizontalPadding: Int,
    val verticalPadding: Int,
    val topStartRadius: Int,
)

internal fun desktopSegmentMetrics(kind: RoleplaySegmentKind): DesktopSegmentMetrics = when (kind) {
    RoleplaySegmentKind.NARRATION -> DesktopSegmentMetrics(.96f, 13, 2f, 4, 4, 12)
    RoleplaySegmentKind.DIALOGUE -> DesktopSegmentMetrics(.86f, 14, 2f, 12, 10, 3)
    RoleplaySegmentKind.THOUGHT -> DesktopSegmentMetrics(.72f, 12, 1.5f, 10, 8, 4)
    RoleplaySegmentKind.STATUS -> DesktopSegmentMetrics(.96f, 12, 4f, 12, 10, 12)
}
