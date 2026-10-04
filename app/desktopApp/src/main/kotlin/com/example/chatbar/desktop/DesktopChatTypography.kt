package com.example.chatbar.desktop

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
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
    fun style(base: TextStyle): TextStyle = base.copy(fontSize = size(base.fontSize))
}
