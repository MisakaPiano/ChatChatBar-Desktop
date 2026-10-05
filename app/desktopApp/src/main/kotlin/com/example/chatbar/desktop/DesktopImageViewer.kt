package com.example.chatbar.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopOwnedImage(reference: String, read: (String) -> ByteArray,
    modifier: Modifier = Modifier, crop: Boolean = false) {
    var error by remember(reference) { mutableStateOf<String?>(null) }
    val bitmap by produceState<ImageBitmap?>(null, reference) {
        try { value = withContext(Dispatchers.IO) { desktopDisplayBitmap(read(reference)) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "图片无法读取" }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, "图片", Modifier.fillMaxSize(), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit) }
            ?: StatusText(error ?: "正在读取图片…")
    }
}

internal fun desktopDisplayBitmap(bytes: ByteArray): ImageBitmap {
    val original = DesktopImageEditing.decode(bytes, longestSide = 1600)
    val ratio = minOf(1.0, 1600.0 / maxOf(original.width, original.height))
    val small = DesktopImageEditing.crop(original, DesktopImageTransform(),
        (original.width * ratio).toInt().coerceAtLeast(1), (original.height * ratio).toInt().coerceAtLeast(1))
    return org.jetbrains.skia.Image.makeFromEncoded(DesktopImageEditing.png(small)).toComposeImageBitmap()
}

@Composable
internal fun DesktopImageViewer(references: List<String>, initialIndex: Int,
    resources: DesktopCharacterResourceStore, picker: DesktopFilePicker, onClose: () -> Unit) {
    var index by remember(references) { mutableStateOf(initialIndex.coerceIn(0, references.lastIndex)) }
    val reference = references[index]
    var zoom by remember(reference) { mutableStateOf(1f) }
    var pan by remember(reference) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun act(action: suspend () -> Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; status = "完成" }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { status = error.message ?: "操作失败" }
        }
    }
    DialogWindow(onCloseRequest = onClose, title = "图片 ${index + 1} / ${references.size}",
        state = rememberDialogState(width = 960.dp, height = 800.dp),
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> { index = (index - 1).coerceAtLeast(0); true }
                Key.DirectionRight -> { index = (index + 1).coerceAtMost(references.lastIndex); true }
                Key.Escape -> { onClose(); true }
                else -> false
            }
        }) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val currentZoom by rememberUpdatedState(zoom)
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .pointerInput(reference) { detectDragGestures { change, amount -> change.consume(); pan += amount } }
                .pointerInput(reference) { awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            zoom = (currentZoom * (1f - event.changes.first().scrollDelta.y * 0.08f)).coerceIn(1f, 8f)
                            event.changes.forEach { it.consume() }
                        }
                    }
                } }) {
                DesktopOwnedImage(reference, resources::readBytes, Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                })
            }
            status?.let { StatusText(it) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton("上一张", enabled = index > 0) { index-- }
                BootstrapButton("下一张", enabled = index < references.lastIndex) { index++ }
                BootstrapButton("重置") { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero }
                BootstrapButton("保存 PNG") { act {
                    val target = picker.pickSaveFile(DesktopFileType("PNG", listOf("png")), "image.png") ?: return@act
                    DesktopExternalFileWriter.writeBytes(target, DesktopImageEditing.png(DesktopImageEditing.decode(resources.readBytes(reference))))
                } }
                BootstrapButton("复制图片") { act {
                    val bitmap = DesktopImageEditing.decode(resources.readBytes(reference))
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(object : java.awt.datatransfer.Transferable {
                        override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
                        override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor == java.awt.datatransfer.DataFlavor.imageFlavor
                        override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any {
                            if (!isDataFlavorSupported(flavor)) throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
                            return bitmap
                        }
                    }, null)
                } }
                BootstrapButton("在文件夹中显示", enabled = !reference.startsWith("asset:")) { act {
                    resources.readBytes(reference) // Validate regular owned file before handing it to Explorer.
                    val path = resources.resolveOwnedReference(reference)
                    ProcessBuilder("explorer.exe", "/select,", path.toString()).start()
                } }
                BootstrapButton("关闭", onClick = onClose)
            }
        }
    }
}

@Composable
internal fun DesktopMessageImages(message: com.example.chatbar.data.local.entity.ChatMessage,
    state: DesktopPrimaryChatState, controller: DesktopPrimaryChatController) {
    var preview by remember(message.id) { mutableStateOf<String?>(null) }
    val references = state.messages.flatMap { it.images }.filterNot { it.startsWith(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX) }
    preview?.let { selected ->
        val index = references.indexOf(selected)
        if (index >= 0) DesktopImageViewer(references, index, controller.characterResources, controller.imagePicker) { preview = null }
    }
    message.images.filterNot { it.startsWith(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX) }.forEach { reference ->
        DesktopOwnedImage(reference, controller.characterResources::readBytes,
            Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 280.dp).height(240.dp).clickable { preview = reference })
    }
}
