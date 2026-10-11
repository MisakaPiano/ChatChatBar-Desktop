package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Toolkit

@Composable
internal fun desktopImageWindowIcon(): Painter = painterResource(DesktopBrandResources.LOGO_RESOURCE)

/** AWT monitor bounds/insets are logical desktop units, as are Desktop window-state dp.
 * Reserve decoration/edge space; this is an initial size only, never a resize/maximize cap. */
internal fun desktopImageWindowSize(requested: DpSize, workWidth: Int, workHeight: Int): DpSize = DpSize(
    requested.width.value.coerceAtMost((workWidth - 48).coerceAtLeast(1).toFloat()).dp,
    requested.height.value.coerceAtMost((workHeight - 64).coerceAtLeast(1).toFloat()).dp)

@Composable
internal fun rememberDesktopImageWindowSize(width: Dp, height: Dp): DpSize = remember {
    val config = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.graphicsConfiguration
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    desktopImageWindowSize(DpSize(width, height), config.bounds.width - insets.left - insets.right,
        config.bounds.height - insets.top - insets.bottom)
}

/** Long library content scrolls independently from its completion action. */
@Composable
internal fun DesktopImageDialogBody(modifier: Modifier = Modifier, footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        footer()
    }
}

/** Reserve preview space while allowing every tool action to remain reachable at short heights. */
@Composable
internal fun DesktopImageToolControls(maxHeight: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
