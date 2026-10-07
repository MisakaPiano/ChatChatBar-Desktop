package com.example.chatbar.desktop

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.input.key.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.nio.file.Path

internal data class DesktopImageIngress(val paths: List<Path> = emptyList(), val raster: ByteArray? = null)

/** File/image ingress never consumes an ordinary text-only paste. File lists retain their order. */
internal fun desktopImageTransfer(transfer: Transferable, paste: Boolean): DesktopImageIngress? {
    if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val files = (transfer.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<java.io.File>().orEmpty()
        val paths = files.filter { it.extension.lowercase() in setOf("png", "apng", "jpg", "jpeg", "webp", "gif") }.map { it.toPath() }
        return paths.takeIf { it.isNotEmpty() }?.let { DesktopImageIngress(paths = it) }
    }
    if (paste && transfer.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    if (transfer.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        val image = transfer.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val width = image.getWidth(null); val height = image.getHeight(null)
        require(width > 0 && height > 0 && width.toLong() * height <= DesktopImageEditing.MAX_PIXELS)
        val raster = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        raster.createGraphics().let { g -> try { g.drawImage(image, 0, 0, null) } finally { g.dispose() } }
        return DesktopImageIngress(raster = DesktopImageEditing.png(raster))
    }
    return null
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.desktopImageIngress(enabled: Boolean = true, onError: () -> Unit = {}, onReceive: (DesktopImageIngress) -> Unit): Modifier {
    val receive by rememberUpdatedState(onReceive)
    val failed by rememberUpdatedState(onError)
    val target = remember(enabled) { object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean = try {
            if (!enabled) false else desktopImageTransfer(event.awtTransferable, false)?.let { receive(it); true } ?: false
        } catch (_: Exception) { failed(); true }
    } }
    return this.dragAndDropTarget(shouldStartDragAndDrop = { enabled }, target = target).onPreviewKeyEvent { event ->
        if (!enabled || event.type != KeyEventType.KeyDown || !event.isCtrlPressed || event.key != Key.V) false
        else try { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null)?.let { desktopImageTransfer(it, true) }?.let { receive(it); true } ?: false }
        catch (_: Exception) { failed(); true }
    }
}
