package com.example.chatbar.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

internal enum class DesktopChatNavigationPlacement { EXTERNAL_RAIL, OVERLAY }

internal data class DesktopChatNavigationLayout(
    val contentWidthDp: Float,
    val placement: DesktopChatNavigationPlacement,
    val railAllowanceDp: Float,
) {
    val workspaceWidthDp get() = contentWidthDp + railAllowanceDp
    val external get() = placement == DesktopChatNavigationPlacement.EXTERNAL_RAIL
}

internal object DesktopChatNavigationPlacementPolicy {
    const val RAIL_WIDTH_DP = 50f // 48dp action plus the surface's 1dp padding on each side.
    const val GAP_DP = 10f
    fun resolve(availableWidthDp: Float): DesktopChatNavigationLayout {
        val content = DesktopChatReadingWidth.forAvailableWidth(availableWidthDp)
        val external = availableWidthDp - content >= RAIL_WIDTH_DP + GAP_DP
        return DesktopChatNavigationLayout(content,
            if (external) DesktopChatNavigationPlacement.EXTERNAL_RAIL else DesktopChatNavigationPlacement.OVERLAY,
            if (external) RAIL_WIDTH_DP + GAP_DP else 0f)
    }
}

internal data class DesktopChatNavigationGroups(val earlier: List<DesktopChatJump>, val later: List<DesktopChatJump>)
internal fun desktopChatNavigationGroups(canEarlier: Boolean, canLater: Boolean, canBottom: Boolean) =
    DesktopChatNavigationGroups(
        if (canEarlier) listOf(DesktopChatJump.PREVIOUS, DesktopChatJump.FIRST) else emptyList(),
        buildList {
            if (canLater) add(DesktopChatJump.NEXT)
            if (canBottom) add(DesktopChatJump.BOTTOM)
        },
    )

internal object DesktopComposerHeightPolicy {
    const val MIN_DP = 48f
    const val DEFAULT_DP = 96f
    const val ABSOLUTE_MAX_DP = 360f
    fun maximum(workspaceHeightDp: Float) = (workspaceHeightDp * 0.42f).coerceIn(MIN_DP, ABSOLUTE_MAX_DP)
    fun clamp(heightDp: Float, workspaceHeightDp: Float) = heightDp.coerceIn(MIN_DP, maximum(workspaceHeightDp))
    fun dragged(heightDp: Float, deltaYDp: Float, workspaceHeightDp: Float) =
        clamp(heightDp - deltaYDp, workspaceHeightDp)
    fun collapsed(heightDp: Float) = heightDp <= MIN_DP
}

/** Shell-owned, UI-lifetime-only preference; deliberately independent of session and draft content. */
internal class DesktopComposerLayoutState {
    var heightDp by mutableFloatStateOf(DesktopComposerHeightPolicy.DEFAULT_DP)
        private set
    fun clamp(workspaceHeightDp: Float) { heightDp = DesktopComposerHeightPolicy.clamp(heightDp, workspaceHeightDp) }
    fun drag(deltaYDp: Float, workspaceHeightDp: Float) {
        heightDp = DesktopComposerHeightPolicy.dragged(heightDp, deltaYDp, workspaceHeightDp)
    }
}
