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
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopOwnedImage(reference: String, read: (String) -> ByteArray,
    modifier: Modifier = Modifier, crop: Boolean = false) {
    var error by remember(reference) { mutableStateOf<String?>(null) }
    val bitmap by produceState<ImageBitmap?>(null, reference) {
        try {
            withContext(Dispatchers.IO) {
                val bytes = read(reference)
                require(bytes.size <= com.example.chatbar.domain.image.ApngDisguiseCodec.MAX_OUTPUT_BYTES)
                if (playDesktopApng(bytes) { frame, duration ->
                    value = desktopDisplayBitmap(frame); delay(duration)
                }) return@withContext
                org.jetbrains.skia.Data.makeFromBytes(bytes).use { data ->
                    org.jetbrains.skia.Codec.makeFromData(data).use { codec ->
                        require(codec.width.toLong() * codec.height <= DesktopImageEditing.MAX_PIXELS)
                        if (codec.frameCount <= 1) value = desktopDisplayBitmap(bytes)
                        else org.jetbrains.skia.Bitmap().use { frame ->
                            check(frame.allocPixels(codec.imageInfo))
                            var loops = 0
                            do {
                                for (index in 0 until codec.frameCount) {
                                    currentCoroutineContext().ensureActive()
                                    codec.readPixels(frame, index)
                                    value = org.jetbrains.skia.Image.makeFromBitmap(frame).toComposeImageBitmap()
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
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, "图片", Modifier.fillMaxSize(), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit) }
            ?: StatusText(error ?: "正在读取图片…")
    }
}

internal fun desktopDisplayBitmap(bytes: ByteArray): ImageBitmap = desktopDisplayBitmap(DesktopImageEditing.decode(bytes, longestSide = 1600))

private fun desktopDisplayBitmap(original: java.awt.image.BufferedImage): ImageBitmap {
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
    onRemove: (String) -> Unit, onPick: () -> Unit, showPicker: Boolean = true) {
    if (images.isEmpty() && !showPicker) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { pending -> Column {
            DesktopOwnedImage(pending.id, { pending.bytes }, Modifier.size(64.dp))
            StudioAction("移除附件", enabled = enabled) { onRemove(pending.id) }
        } }
        if (showPicker) StudioAction("图片附件", icon = DesktopAppIcons.Add, enabled = enabled, onClick = onPick)
    }
}

internal fun desktopImageTasksForMessage(tasks: List<DesktopTaskEntry>, message: com.example.chatbar.data.local.entity.ChatMessage) =
    tasks.filter { it.kind == DesktopTaskKind.NOVELAI && it.sessionId == message.sessionId && it.targetMessageId == message.id }

@Composable
internal fun DesktopMessageImages(message: com.example.chatbar.data.local.entity.ChatMessage,
    state: DesktopPrimaryChatState, controller: DesktopPrimaryChatController) {
    val scope = rememberCoroutineScope()
    var imageStatus by remember(message.id) { mutableStateOf("") }
    val tasks by controller.taskRuntime.tasks.collectAsState()
    val running = tasks.any { it.sessionId == message.sessionId && it.status == DesktopTaskStatus.RUNNING }
    desktopImageTasksForMessage(tasks, message).forEach { task ->
        StatusText(task.message)
        if (task.status == DesktopTaskStatus.RUNNING) BootstrapButton("停止此图片任务") { controller.stop(task.taskId) }
        else StudioActions {
            if (task.canRetry) BootstrapButton("重试此图片任务", enabled = !running) { scope.launch {
                try {
                    controller.taskRuntime.retryImageTask(task.taskId)
                    controller.refreshAfterTerminalTask(message.sessionId)
                } catch (_: Exception) { imageStatus = "无法重试，请检查来源消息、设置与任务状态" }
            } }
            BootstrapButton("关闭图片任务") { controller.taskRuntime.dismissImageTask(task.taskId) }
        }
    }
    var requirementsOpen by remember(message.id) { mutableStateOf(false) }
    var imageHint by remember(message.id) { mutableStateOf("") }
    var preference by remember(message.id, state.selectedSession?.imagePromptPreference) { mutableStateOf(state.selectedSession?.imagePromptPreference.orEmpty()) }
    val generate: (DesktopChatImageRequirements?) -> Unit = { requirements -> scope.launch {
        try {
            requireNotNull(controller.imageRegeneration).generateFromAssistant(message, requirements)
            imageStatus = "已启动图片任务"; requirementsOpen = false
            controller.refreshAfterTerminalTask(message.sessionId)
        } catch (_: Exception) { imageStatus = "无法启动图片任务，请检查生图配置与任务状态" }
    } }
    if (message.role == com.example.chatbar.data.local.entity.MessageRole.ASSISTANT && message.displayContent.isNotBlank() &&
        state.messages.any { it.id == message.id } && controller.imageRegeneration != null) {
        StudioActions {
            BootstrapButton("为这条回复生成图片", enabled = !running && !state.selectedCharacterMissing && state.modelUsable) { generate(null) }
            BootstrapButton("生图要求…", enabled = !running && !state.selectedCharacterMissing && state.modelUsable) {
                imageHint = ""; preference = state.selectedSession?.imagePromptPreference.orEmpty(); requirementsOpen = true
            }
        }
        if (imageStatus.isNotBlank()) StatusText(imageStatus)
    }
    if (requirementsOpen) DialogWindow(onCloseRequest = { requirementsOpen = false }, title = "生图要求") {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StudioField("本次图片内容（仅本次使用）", imageHint) { imageHint = it }
            StudioField("会话图片 Prompt 偏好（点击生成时保存）", preference) { preference = it }
            StatusText("取消不会保存；直接生成使用会话已保存的偏好。")
            if (imageStatus.isNotBlank()) StatusText(imageStatus)
            StudioActions {
                BootstrapButton("取消") { requirementsOpen = false }
                BootstrapButton("生成", enabled = !running) { generate(DesktopChatImageRequirements(imageHint, preference)) }
            }
        }
    }
    var regeneration by remember(message.id) { mutableStateOf<com.example.chatbar.data.local.entity.GeneratedImageMetadata?>(null) }
    regeneration?.let { metadata -> controller.imageRegeneration?.let { service -> DesktopChatRegenerationDialog(message, metadata, service) { regeneration = null } } }
    var preview by remember(message.id) { mutableStateOf<String?>(null) }
    var deleteImage by remember(message.id) { mutableStateOf<String?>(null) }
    deleteImage?.let { reference -> DialogWindow(onCloseRequest = { deleteImage = null }, title = "删除这张聊天图片？") {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusText("仅删除所选图片。没有正文和其他图片的消息将一并删除，来源回复保留。")
            state.error?.let { StatusText(it) }
            StudioActions {
                BootstrapButton("取消") { deleteImage = null }
                BootstrapButton("确认删除", enabled = !running, variant = DesktopActionVariant.DESTRUCTIVE) { scope.launch {
                    if (controller.deleteMessageImage(message, reference)) deleteImage = null
                } }
            }
        }
    } }
    val references = state.messages.flatMap { it.images }.filterNot { it.startsWith(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX) }
    preview?.let { selected ->
        val index = references.indexOf(selected)
        if (index >= 0) DesktopImageViewer(references, index, controller.characterResources, controller.imagePicker) { preview = null }
    }
    message.images.filterNot { it.startsWith(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX) }.forEach { reference ->
        DesktopOwnedImage(reference, controller.characterResources::readBytes,
            Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 280.dp).height(240.dp).clickable { preview = reference })
        message.generatedImageMetadata.firstOrNull { it.imagePath == reference }?.let { metadata ->
            if (controller.imageRegeneration != null) BootstrapButton("编辑并重新生成") { regeneration = metadata }
        }
        BootstrapButton("删除这张图片", enabled = !running) { deleteImage = reference }
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
