package com.example.chatbar.desktop

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.data.local.entity.resolveDarkTheme
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.appearance.ThemeColorHsv
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal data class DesktopSemanticColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val border: Color,
    val input: Color,
    val primary: Color,
    val primaryForeground: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val warning: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    val overlay: Color,
    val dim: Color,
)

internal fun desktopSemanticColors(
    mode: ThemeMode,
    themeColor: ThemeColorHsv,
    systemDark: Boolean,
): DesktopSemanticColors {
    val dark = mode.resolveDarkTheme(systemDark)
    val primary = Color(themeColor.normalized().toOpaqueArgb())
    val primaryForeground = if (desktopContrastRatio(primary, Color.White) >=
        desktopContrastRatio(primary, Color.Black)) Color.White else Color.Black
    val background = if (dark) Color(0xFF0D1117) else Color(0xFFF8FAFC)
    val card = if (dark) Color(0xFF161B22) else Color.White
    val foreground = if (dark) Color(0xFFE6EDF3) else Color(0xFF0F172A)
    val muted = if (dark) Color(0xFF222A35) else Color(0xFFF1F5F9)
    return DesktopSemanticColors(
        background = background,
        foreground = foreground,
        card = card,
        cardForeground = foreground,
        muted = muted,
        mutedForeground = if (dark) Color(0xFFAAB6C5) else Color(0xFF5A687A),
        border = if (dark) Color(0xFF3C4654) else Color(0xFFD9E1EA),
        input = if (dark) Color(0xFF1B222C) else Color.White,
        primary = primary,
        primaryForeground = primaryForeground,
        secondary = muted,
        secondaryForeground = foreground,
        accent = mixColor(primary, card, if (dark) 0.28f else 0.14f),
        accentForeground = foreground,
        warning = if (dark) Color(0xFFFFC66D) else Color(0xFF9A5700),
        destructive = if (dark) Color(0xFFFF8B8B) else Color(0xFFB91C1C),
        destructiveForeground = if (dark) Color.Black else Color.White,
        overlay = card.copy(alpha = 0.97f),
        dim = Color.Black.copy(alpha = if (dark) 0.7f else 0.5f),
    )
}

private fun mixColor(top: Color, bottom: Color, fraction: Float): Color = Color(
    red = top.red * fraction + bottom.red * (1f - fraction),
    green = top.green * fraction + bottom.green * (1f - fraction),
    blue = top.blue * fraction + bottom.blue * (1f - fraction),
)

internal fun desktopContrastRatio(first: Color, second: Color): Double {
    fun linear(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    fun luminance(color: Color): Double = 0.2126 * linear(color.red) +
        0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    val a = luminance(first)
    val b = luminance(second)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

internal val LocalDesktopPalette = compositionLocalOf {
    desktopSemanticColors(ThemeMode.SYSTEM, DefaultThemeColorHsv, systemDark = false)
}
