package com.example.chatbar.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-only route state. Root migration always takes visual and interaction priority. */
internal enum class DesktopPrimaryRoute { CHAT, MANAGE, TOOLS, DATA }

internal class DesktopPrimaryNavigationController {
    private val selected = MutableStateFlow(DesktopPrimaryRoute.CHAT)
    val selectedRoute: StateFlow<DesktopPrimaryRoute> = selected.asStateFlow()

    fun currentRoute(
        rootState: DesktopDataRootSwitchState,
        requestedRoute: DesktopPrimaryRoute = selected.value,
    ): DesktopPrimaryRoute =
        if (rootState is DesktopDataRootSwitchState.Idle) requestedRoute else DesktopPrimaryRoute.DATA

    fun navigate(route: DesktopPrimaryRoute, rootState: DesktopDataRootSwitchState): Boolean {
        if (rootState !is DesktopDataRootSwitchState.Idle) return false
        selected.value = route
        return true
    }

    fun navigateFromManageWhenIdle(
        route: DesktopPrimaryRoute,
        rootState: DesktopDataRootSwitchState,
        managementBusy: Boolean,
        transferBusy: Boolean,
    ): Boolean = if (managementBusy || transferBusy) false else navigate(route, rootState)
}

/** Shell capacity only; the eventual chat workspace layout is owned by a later slice. */
internal enum class DesktopShellSize { WIDE, MEDIUM, COMPACT }

internal object DesktopShellLayoutPolicy {
    const val MEDIUM_MIN_WIDTH_DP = 720
    const val WIDE_MIN_WIDTH_DP = 1100

    fun sizeForWidth(widthDp: Float): DesktopShellSize = when {
        widthDp >= WIDE_MIN_WIDTH_DP -> DesktopShellSize.WIDE
        widthDp >= MEDIUM_MIN_WIDTH_DP -> DesktopShellSize.MEDIUM
        else -> DesktopShellSize.COMPACT
    }
}
