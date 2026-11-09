package com.example.chatbar.desktop

internal fun desktopMainWindowClose(root: DesktopDataRootSwitchState, hide: () -> Boolean, requestExit: () -> Unit) {
    if (root !is DesktopDataRootSwitchState.Idle || !hide()) requestExit()
}

/** Reuses each editor's existing save/discard/cancel owner before the original app exit. */
internal class DesktopApplicationExitGuard(
    private val chat: (() -> Unit) -> Unit,
    private val worldBooks: (() -> Unit) -> Unit,
    private val formats: (() -> Unit) -> Unit,
    private val characters: (() -> Unit) -> Unit,
    private val models: (() -> Unit) -> Unit,
    private val finish: () -> Unit,
) {
    fun requestExit() = chat { worldBooks { formats { characters { models(finish) } } } }
}
