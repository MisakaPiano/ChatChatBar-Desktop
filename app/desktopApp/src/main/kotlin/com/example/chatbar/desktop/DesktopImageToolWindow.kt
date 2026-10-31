package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*

/** Independent resizable image window, sharing the main app's native caption and resize owner. */
@Composable
internal fun DesktopImageToolWindow(title: String, onClose: () -> Unit, width: Dp = 920.dp, height: Dp = 760.dp,
    onActivate: () -> Unit = {}, onKey: (KeyEvent) -> Boolean = { false }, content: @Composable ColumnScope.() -> Unit) {
    val activate by rememberUpdatedState(onActivate)
    val initialSize = rememberDesktopImageWindowSize(width, height)
    Window(onCloseRequest = onClose, title = title, resizable = true, icon = desktopImageWindowIcon(),
        undecorated = DesktopWindowChrome.isWindows,
        state = rememberWindowState(size = initialSize), onPreviewKeyEvent = {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true } else onKey(it)
        }) {
        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowAdapter() { override fun windowGainedFocus(e: java.awt.event.WindowEvent) { activate() } }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        val chrome = rememberDesktopWindowChrome(window, onClose)
        val recorder = remember(chrome) { DesktopChromeLayoutRecorder(chrome) }
        val colors = LocalDesktopPalette.current
        SideEffect { chrome.appearance(colors.background.luminance() < .5f) }
        CompositionLocalProvider(LocalDesktopFishParentWindow provides window) {
            DesktopImageToolFrame(title, chrome, recorder, content)
        }
    }
}

@Composable
internal fun DesktopImageToolFrame(title: String, chrome: DesktopWindowChrome, recorder: DesktopChromeLayoutRecorder,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).onGloballyPositioned(recorder::root)) {
        if (DesktopWindowChrome.isWindows) DesktopTitleBar(DesktopShellSize.COMPACT, DesktopPrimaryRoute.CHAT,
            false, chrome, recorder, navigationVisible = false, windowTitle = title, onNavigate = {})
        Column(Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
