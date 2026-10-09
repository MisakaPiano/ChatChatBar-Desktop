package com.example.chatbar.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopOwnedImage(reference: String, read: (String) -> ByteArray,
    modifier: Modifier = Modifier, crop: Boolean = false, onDimensions: ((IntSize) -> Unit)? = null,
    minimumDisplayWidthPx: Int? = null,
    displayTargetForSource: ((IntSize) -> IntSize)? = null) {
    var error by remember(reference) { mutableStateOf<String?>(null) }
    val displayed by produceState<DesktopDisplayedImage?>(null, reference, minimumDisplayWidthPx, displayTargetForSource) {
        try {
            withContext(Dispatchers.IO) {
                val bytes = read(reference)
                require(bytes.size <= com.example.chatbar.domain.image.ApngDisguiseCodec.MAX_OUTPUT_BYTES)
                if (playDesktopApng(bytes) { frame, duration ->
                    value = DesktopDisplayedImage(desktopDisplayBitmap(frame, minimumDisplayWidthPx),
                        IntSize(frame.width, frame.height)); delay(duration)
                }) return@withContext
                org.jetbrains.skia.Data.makeFromBytes(bytes).use { data ->
                    org.jetbrains.skia.Codec.makeFromData(data).use { codec ->
                        require(codec.width.toLong() * codec.height <= DesktopImageEditing.MAX_PIXELS)
                        if (codec.frameCount <= 1) value = desktopDisplayBitmap(bytes, minimumDisplayWidthPx, displayTargetForSource)
                        else org.jetbrains.skia.Bitmap().use { frame ->
                            check(frame.allocPixels(codec.imageInfo))
                            var loops = 0
                            do {
                                for (index in 0 until codec.frameCount) {
                                    currentCoroutineContext().ensureActive()
                                    codec.readPixels(frame, index)
                                    value = DesktopDisplayedImage(org.jetbrains.skia.Image.makeFromBitmap(frame).toComposeImageBitmap(),
                                        IntSize(codec.width, codec.height))
                                    delay(codec.getFrameInfo(index).duration.coerceAtLeast(10).toLong())
                                }
                                loops++
                            } while (codec.repetitionCount < 0 || loops <= codec.repetitionCount)
                        }
                    }
                }
            }
        }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "图片无法读取" }
    }
    val dimensionsChanged by rememberUpdatedState(onDimensions)
    LaunchedEffect(reference, displayed?.sourceDimensions) {
        displayed?.let { dimensionsChanged?.invoke(it.sourceDimensions) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        displayed?.let { Image(it.bitmap, "图片", Modifier.fillMaxSize(), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit) }
            ?: StatusText(error ?: "正在读取图片…")
    }
}

private data class DesktopDisplayedImage(val bitmap: ImageBitmap, val sourceDimensions: IntSize)

private fun desktopDisplayBitmap(bytes: ByteArray, minimumDisplayWidthPx: Int?,
    displayTargetForSource: ((IntSize) -> IntSize)?): DesktopDisplayedImage {
    var source: IntSize? = null
    val decoded = DesktopImageEditing.decode(bytes, longestSide = 1600,
        minimumDisplayWidth = minimumDisplayWidthPx,
        onSourceDimensions = { width, height -> source = IntSize(width, height) },
        displayTargetForSource = displayTargetForSource?.let { target ->
            { width, height -> target(IntSize(width, height)).let { it.width to it.height } }
        })
    val orientedSource = requireNotNull(source)
    val target = displayTargetForSource?.invoke(orientedSource)
    return DesktopDisplayedImage(if (target == null) desktopDisplayBitmap(decoded, minimumDisplayWidthPx)
        else desktopDisplayBitmap(decoded, target), orientedSource)
}

private fun desktopDisplayBitmap(original: java.awt.image.BufferedImage, target: IntSize): ImageBitmap {
    val ratio = minOf(1.0, target.width.toDouble() / original.width,
        target.height.toDouble() / original.height)
    val width = (original.width * ratio).roundToInt().coerceAtLeast(1)
    val height = (original.height * ratio).roundToInt().coerceAtLeast(1)
    val small = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    small.createGraphics().let { graphics -> try {
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(original, 0, 0, width, height, null)
    } finally { graphics.dispose() } }
    return org.jetbrains.skia.Image.makeFromEncoded(DesktopImageEditing.png(small)).toComposeImageBitmap()
}

private fun desktopDisplayBitmap(original: java.awt.image.BufferedImage, minimumDisplayWidthPx: Int?): ImageBitmap {
    val ratio = minOf(1.0, maxOf(1600.0 / maxOf(original.width, original.height),
        (minimumDisplayWidthPx ?: 0).toDouble() / original.width))
    val small = DesktopImageEditing.crop(original, DesktopImageTransform(),
        (original.width * ratio).toInt().coerceAtLeast(1), (original.height * ratio).toInt().coerceAtLeast(1))
    return org.jetbrains.skia.Image.makeFromEncoded(DesktopImageEditing.png(small)).toComposeImageBitmap()
}

@Composable
internal fun DesktopImageViewer(references: List<String>, initialIndex: Int,
    resources: DesktopCharacterResourceStore, picker: DesktopFilePicker, onClose: () -> Unit) {
    var index by remember(references) { mutableStateOf(initialIndex.coerceIn(0, references.lastIndex)) }
    val reference = references[index]
    var resetRevision by remember(reference) { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()
    fun act(action: suspend () -> Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; status = "完成" }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { status = error.message ?: "操作失败" }
        }
    }
    tools?.let { DesktopImageToolsDialog(it, picker, { tools = null }) }
    DesktopImageToolWindow("图片 ${index + 1} / ${references.size}", onClose, width = 960.dp, height = 800.dp,
        onKey = { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> { index = (index - 1).coerceAtLeast(0); true }
                Key.DirectionRight -> { index = (index + 1).coerceAtMost(references.lastIndex); true }
                Key.Escape -> { onClose(); true }
                else -> false
            }
        }) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopImageZoomSurface(reference, resources::readBytes, Modifier.weight(1f).fillMaxWidth(), resetRevision)
            status?.let { StatusText(it) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton("上一张", enabled = index > 0) { index-- }
                BootstrapButton("下一张", enabled = index < references.lastIndex) { index++ }
                BootstrapButton("重置") { resetRevision++ }
                BootstrapButton("保存 PNG") { act {
                    val target = picker.pickSaveFile(DesktopFileType("PNG", listOf("png")), "image.png") ?: return@act
                    val bytes = resources.readBytes(reference)
                    val png = bytes.size >= 8 && bytes[0] == 0x89.toByte()
                    if (!png) DesktopImageEditing.requireStatic(bytes)
                    DesktopExternalFileWriter.writeBytes(target, if (png) bytes else DesktopImageEditing.png(DesktopImageEditing.decode(bytes)))
                } }
                BootstrapButton("复制图片") { act {
                    val bytes = resources.readBytes(reference)
                    copyDesktopImage(bytes, if (reference.startsWith("asset:")) null else resources.resolveOwnedReference(reference))
                } }
                BootstrapButton("在文件夹中显示", enabled = !reference.startsWith("asset:")) { act {
                    resources.readBytes(reference) // Validate regular owned file before handing it to Explorer.
                    val path = resources.resolveOwnedReference(reference)
                    ProcessBuilder("explorer.exe", "/select,", path.toString()).start()
                } }
                BootstrapButton("图像工具") { act { tools = resources.readBytes(reference) } }
                BootstrapButton("关闭", onClick = onClose)
            }
        }
    }
}

@Composable
internal fun DesktopPendingImageStrip(images: List<DesktopPendingImage>, enabled: Boolean,
    onRemove: (String) -> Unit, onPick: () -> Unit, showPicker: Boolean = true,
    onPreview: ((DesktopPendingImage) -> Unit)? = null, onReorder: ((String, Int) -> Unit)? = null) {
    if (images.isEmpty() && !showPicker) return
    var preview by remember { mutableStateOf<DesktopPendingImage?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).pointerInput(images.size) { awaitPointerEventScope { while (true) {
        val event = awaitPointerEvent()
        if (event.type == PointerEventType.Scroll) {
            val delta = event.changes.sumOf { (it.scrollDelta.x + it.scrollDelta.y).toDouble() }.toFloat()
            scope.launch { scroll.scrollTo((scroll.value + delta * 48).toInt()) }
            event.changes.forEach { it.consume() }
        }
    } } }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { pending -> key(pending.id) {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            var focused by remember { mutableStateOf(false) }
            Box(Modifier.size(112.dp).hoverable(interaction).border(1.dp, DesktopBootstrapColors.border)
                .pointerInput(pending.id, images.map { it.id }, enabled) {
                    var distance = 0f
                    detectDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                        if (enabled && onReorder != null) {
                            val from = images.indexOfFirst { it.id == pending.id }
                            val to = (from + kotlin.math.round(distance / 120.dp.toPx()).toInt()).coerceIn(images.indices)
                            if (from != to) onReorder(pending.id, to)
                        }
                    }) { change, amount -> if (enabled && onReorder != null) { change.consume(); distance += amount.x } }
                }) {
                DesktopOwnedImage(pending.id, { pending.bytes }, Modifier.fillMaxSize()
                    .semantics { contentDescription = "预览图片附件" }.onFocusChanged { focused = it.hasFocus }
                    .clickable { if (onPreview == null) preview = pending else onPreview(pending) })
                if (hovered || focused) Box(Modifier.align(Alignment.TopEnd).background(DesktopBootstrapColors.card)) {
                    DesktopChatIconAction("移除此图片附件", DesktopAppIcons.Close, enabled = enabled, targetDp = 28) { onRemove(pending.id) }
                }
                if (pending.restoredDisguise) Box(Modifier.align(Alignment.BottomCenter).background(DesktopBootstrapColors.card)) { StatusText("已还原伪装") }
            }
        } }
        if (showPicker) StudioAction("图片附件", icon = DesktopAppIcons.Add, enabled = enabled, onClick = onPick)
    }
    preview?.let { pending -> DesktopTransientImagePreview(pending.id, { pending.bytes }) { preview = null } }
}

/** Pending/imported images have no owned reference yet. Read-only viewer retains original animation. */
@Composable
internal fun DesktopTransientImagePreview(reference: String, read: (String) -> ByteArray, onClose: () -> Unit) {
    var resetRevision by remember(reference) { mutableStateOf(0) }
    DesktopImageToolWindow("图片预览", onClose, width = 960.dp, height = 800.dp) {
        DesktopImageZoomSurface(reference, read, Modifier.weight(1f).fillMaxWidth(), resetRevision)
        StudioActions {
            StudioAction("重置") { resetRevision++ }
            StudioAction("关闭预览", icon = DesktopAppIcons.Close, onClick = onClose)
        }
    }
}

internal fun desktopImageTasksForMessage(tasks: List<DesktopTaskEntry>, message: com.example.chatbar.data.local.entity.ChatMessage) =
    tasks.filter { it.kind == DesktopTaskKind.NOVELAI && it.sessionId == message.sessionId && it.targetMessageId == message.id }

/** Orientation-correct source pixels determine aspect ratio and the native-size ceiling. */
internal fun desktopMessageImageSize(pixels: IntSize?, availableWidth: Dp, density: Float): DpSize {
    val widthLimit = minOf(availableWidth.value.coerceAtLeast(1f), 280f)
    if (pixels == null || pixels.width <= 0 || pixels.height <= 0) return DpSize(minOf(widthLimit, 160f).dp, 100.dp)
    val pxPerDp = density.coerceAtLeast(0.1f)
    val nativeWidth = pixels.width / pxPerDp
    val nativeHeight = pixels.height / pxPerDp
    val scale = minOf(1f, widthLimit / nativeWidth, 280f / nativeHeight)
    return DpSize((nativeWidth * scale).dp, (nativeHeight * scale).dp)
}

internal fun desktopMessageImageMenuItems(reference: String,
    metadata: com.example.chatbar.data.local.entity.GeneratedImageMetadata?, canRegenerate: Boolean,
    running: Boolean, onRegenerate: (com.example.chatbar.data.local.entity.GeneratedImageMetadata) -> Unit,
    onDelete: (String) -> Unit): List<ContextMenuItem> = buildList {
    if (metadata != null && canRegenerate) add(ContextMenuItem("编辑并重新生成") { onRegenerate(metadata) })
    if (!running) add(ContextMenuItem("删除这张图片") { onDelete(reference) })
}

@Composable
internal fun DesktopMessageImageItem(reference: String, read: (String) -> ByteArray,
    metadata: com.example.chatbar.data.local.entity.GeneratedImageMetadata?, canRegenerate: Boolean,
    running: Boolean, onPreview: (String) -> Unit,
    onRegenerate: (com.example.chatbar.data.local.entity.GeneratedImageMetadata) -> Unit,
    onDelete: (String) -> Unit) {
    var pixels by remember(reference) { mutableStateOf<IntSize?>(null) }
    val density = LocalDensity.current.density
    val menuState = remember(reference) { ContextMenuState() }
    var menuAnchor by remember(reference) { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    val menuItems = desktopMessageImageMenuItems(reference, metadata, canRegenerate, running, onRegenerate, onDelete)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val imageWidth = (maxWidth - 36.dp).coerceAtLeast(1.dp)
        val displaySize = desktopMessageImageSize(pixels, imageWidth, density)
        val decodeTarget = remember(imageWidth, density) { { source: IntSize ->
            val size = desktopMessageImageSize(source, imageWidth, density)
            IntSize((size.width.value * density).roundToInt().coerceAtLeast(1),
                (size.height.value * density).roundToInt().coerceAtLeast(1))
        } }
        ContextMenuArea(items = { menuItems }, state = menuState) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopOwnedImage(reference, read,
                    Modifier.size(displaySize).semantics { contentDescription = "打开图片预览" }
                        .clickable { onPreview(reference) }, onDimensions = { pixels = it },
                    displayTargetForSource = decodeTarget)
                if (menuItems.isNotEmpty()) Box(Modifier.onGloballyPositioned { menuAnchor = it.boundsInWindow() }) {
                    DesktopChatIconAction("图片操作", DesktopAppIcons.More, targetDp = 28) {
                        menuState.status = ContextMenuState.Status.Open(menuAnchor)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesktopMessageImageDeleteConfirmationBody(running: Boolean, error: String?,
    onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusText("仅删除所选图片。没有正文和其他图片的消息将一并删除，来源回复保留。")
        error?.let { StatusText(it) }
        StudioActions {
            BootstrapButton("取消", onClick = onCancel)
            BootstrapButton("确认删除", enabled = !running, variant = DesktopActionVariant.DESTRUCTIVE, onClick = onConfirm)
        }
    }
}

@Composable
internal fun DesktopMessageImages(message: com.example.chatbar.data.local.entity.ChatMessage,
    state: DesktopPrimaryChatState, controller: DesktopPrimaryChatController) {
    val scope = rememberCoroutineScope()
    val tasks by controller.taskRuntime.tasks.collectAsState()
    val running = tasks.any { it.sessionId == message.sessionId && it.status == DesktopTaskStatus.RUNNING }
    var regeneration by remember(message.id) { mutableStateOf<com.example.chatbar.data.local.entity.GeneratedImageMetadata?>(null) }
    regeneration?.let { metadata -> controller.imageRegeneration?.let { service -> DesktopChatRegenerationDialog(message, metadata, service) { regeneration = null } } }
    var preview by remember(message.id) { mutableStateOf<String?>(null) }
    var deleteImage by remember(message.id) { mutableStateOf<String?>(null) }
    deleteImage?.let { reference -> DialogWindow(onCloseRequest = { deleteImage = null }, title = "删除这张聊天图片？") {
        DesktopMessageImageDeleteConfirmationBody(running, state.error, { deleteImage = null }) { scope.launch {
            if (controller.deleteMessageImage(message, reference)) deleteImage = null
        } }
    } }
    val references = state.messages.flatMap { it.images }.filterNot { it.startsWith(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX) }
    preview?.let { selected ->
        val index = references.indexOf(selected)
        if (index >= 0) DesktopImageViewer(references, index, controller.characterResources, controller.imagePicker) { preview = null }
    }
    message.images.filterNot { it.startsWith(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX) }.forEach { reference ->
        DesktopMessageImageItem(reference, controller.characterResources::readBytes,
            message.generatedImageMetadata.firstOrNull { it.imagePath == reference },
            controller.imageRegeneration != null, running, { preview = it }, { regeneration = it }, { deleteImage = it })
    }
}

/** Animated data is copied as its original file; static images also expose native image flavor. */
internal fun copyDesktopImage(bytes: ByteArray, file: java.nio.file.Path? = null) {
    val bitmap = if (runCatching { DesktopImageEditing.requireStatic(bytes) }.isSuccess) DesktopImageEditing.decode(bytes) else null
    require(bitmap != null || file != null) { "动画请先保存原始文件" }
    val imageFlavor = java.awt.datatransfer.DataFlavor.imageFlavor
    val fileFlavor = java.awt.datatransfer.DataFlavor.javaFileListFlavor
    val flavors = listOfNotNull(imageFlavor.takeIf { bitmap != null }, fileFlavor.takeIf { file != null }).toTypedArray()
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(object : java.awt.datatransfer.Transferable {
        override fun getTransferDataFlavors() = flavors
        override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor in flavors
        override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = when {
            flavor == imageFlavor && bitmap != null -> bitmap
            flavor == fileFlavor && file != null -> listOf(file.toFile())
            else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        }
    }, null)
}
