package com.example.chatbar.desktop

internal data class DesktopTitleRoute(val route: DesktopPrimaryRoute, val selected: Boolean, val enabled: Boolean)
internal data class DesktopTitleBarPresentation(
    val showBrandName: Boolean,
    val showRouteLabels: Boolean,
    val routeWidthDp: Int,
    val captionWidthDp: Int,
    val routes: List<DesktopTitleRoute>,
) {
    val minimumContentWidthDp get() = (if (showBrandName) 160 else 40) + 4 * routeWidthDp + 3 * captionWidthDp
}

internal fun desktopTitleBarPresentation(size: DesktopShellSize, selected: DesktopPrimaryRoute, locked: Boolean) =
    DesktopTitleBarPresentation(
        showBrandName = size == DesktopShellSize.WIDE,
        showRouteLabels = size != DesktopShellSize.COMPACT,
        routeWidthDp = if (size == DesktopShellSize.COMPACT) 36 else 88,
        captionWidthDp = if (size == DesktopShellSize.COMPACT) 42 else 46,
        routes = DesktopPrimaryRoute.entries.map { DesktopTitleRoute(it, it == selected, !locked) },
    )
