package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.awt.SwingDialog
import androidx.compose.ui.awt.ComposeDialog
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.WindowConstants

private val LocalScreenshotOwner = staticCompositionLocalOf<java.awt.Window?> { null }

internal fun desktopScreenshotWindowBounds(parent: Rectangle, workArea: Rectangle): Rectangle {
    require(workArea.width > 0 && workArea.height > 0)
    val width = (parent.width * .85).toInt().coerceIn(minOf(320, workArea.width), workArea.width)
    val height = (parent.height * .85).toInt().coerceIn(minOf(360, workArea.height), workArea.height)
    return Rectangle((parent.x + (parent.width - width) / 2).coerceIn(workArea.x, workArea.x + workArea.width - width),
        (parent.y + (parent.height - height) / 2).coerceIn(workArea.y, workArea.y + workArea.height - height), width, height)
}

/** The native child owns only a workspace-close callback, never application exit/chrome hooks. */
@Composable
internal fun DesktopScreenshotWindow(onClose: () -> Unit, content: @Composable () -> Unit, title: String = "聊天截图工作区") {
    // Main already publishes this exact public WindowScope identity. Reuse it without
    // reflection/internal Compose LocalWindow or changing Main/chrome lifecycle.
    val owner = LocalScreenshotOwner.current ?: LocalDesktopFishParentWindow.current
    var child by remember(owner) { mutableStateOf<ComposeDialog?>(null) }
    if (owner == null || !owner.isDisplayable) {
        StatusText("截图工作区未打开：主窗口身份不可用", DesktopBootstrapColors.destructive)
        StudioAction("返回聊天", onClick = onClose)
        return
    }
    val close by rememberUpdatedState(onClose)
    SwingDialog(
        onPreviewKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { close(); true } else false
        },
        create = {
            val config = owner.graphicsConfiguration
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            val screen = config.bounds
            val area = Rectangle(screen.x + insets.left, screen.y + insets.top,
                screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom)
            ComposeDialog(owner, Dialog.ModalityType.MODELESS, config).apply {
                this.title = title
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                isResizable = true
                minimumSize = Dimension(minOf(320, area.width), minOf(360, area.height))
                bounds = desktopScreenshotWindowBounds(owner.bounds, area)
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(event: WindowEvent) { close() }
                })
                child = this
            }
        },
        dispose = { it.dispose() },
    ) { CompositionLocalProvider(LocalScreenshotOwner provides child) { content() } }
}
