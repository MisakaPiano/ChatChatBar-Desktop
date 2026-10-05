package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage

/** One immutable source per editing transaction; preview and committed crop share geometry. */
@Composable
internal fun DesktopImageWorkspace(
    sourceBytes: ByteArray,
    title: String,
    onCancel: () -> Unit,
    onApply: (ByteArray) -> Unit,
) {
    var transform by remember(sourceBytes) { mutableStateOf(DesktopImageTransform()) }
    var square by remember(sourceBytes) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val source by produceState<java.awt.image.BufferedImage?>(null, sourceBytes) {
        try { value = withContext(Dispatchers.Default) {
            DesktopImageEditing.requireStatic(sourceBytes)
            DesktopImageEditing.decode(sourceBytes)
        } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    val height = source?.let { if (square) 512 else (512.0 * it.height / it.width).toInt().coerceIn(64, 2048) } ?: 512
    val preview by produceState<ImageBitmap?>(null, source, transform, height) {
        val original = source ?: return@produceState
        value = withContext(Dispatchers.Default) {
            SkiaImage.makeFromEncoded(DesktopImageEditing.png(DesktopImageEditing.crop(original, transform, 512, height)))
                .toComposeImageBitmap()
        }
    }
    DialogWindow(onCloseRequest = { if (!busy) onCancel() }, title = title,
        state = rememberDialogState(width = 760.dp, height = 780.dp)) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusText("拖动定位 · 滚轮缩放 · 应用后创建新图片")
            DesktopImageViewport(preview, title, source?.width ?: 1, source?.height ?: 1,
                512f / height, transform, { transform = it }, Modifier.weight(1f).fillMaxWidth())
            DesktopImageSlider("缩放 ${"%.2f".format(transform.zoom)}×", (transform.zoom - 1f) / 7f) {
                transform = transform.copy(zoom = 1f + it * 7f)
            }
            error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton(if (square) "比例：正方形" else "比例：原图", secondary = true) { square = !square }
                BootstrapButton("重置", secondary = true) { transform = DesktopImageTransform() }
                BootstrapButton("取消", enabled = !busy, secondary = true, onClick = onCancel)
                BootstrapButton(if (busy) "正在应用…" else "应用", enabled = source != null && !busy) {
                    busy = true
                    scope.launch {
                        try {
                            val bytes = withContext(Dispatchers.Default) {
                                DesktopImageEditing.png(DesktopImageEditing.crop(requireNotNull(source), transform, 1024, height * 2))
                            }
                            onApply(bytes)
                        } catch (failure: Exception) { error = failure.message }
                        finally { busy = false }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesktopImageViewport(preview: ImageBitmap?, title: String, sourceWidth: Int, sourceHeight: Int,
    ratio: Float, transform: DesktopImageTransform, onChange: (DesktopImageTransform) -> Unit,
    modifier: Modifier = Modifier) {
    val currentTransform by rememberUpdatedState(transform)
    val changeTransform by rememberUpdatedState(onChange)
    Box(modifier.background(DesktopBootstrapColors.muted)
        .pointerInput(sourceWidth, sourceHeight, ratio) {
            detectDragGestures { change, amount ->
                change.consume()
                val viewport = fittedViewport(size.width.toFloat(), size.height.toFloat(), ratio)
                changeTransform(currentTransform.pan(amount.x, amount.y, sourceWidth, sourceHeight, viewport.x, viewport.y))
            }
        }.pointerInput(sourceWidth, sourceHeight) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll) {
                        changeTransform(currentTransform.copy(zoom = currentTransform.zoom *
                            (1f - event.changes.first().scrollDelta.y * 0.08f)).normalized())
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }) {
        preview?.let { Image(it, title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

private fun fittedViewport(width: Float, height: Float, ratio: Float): Offset =
    if (width / height > ratio) Offset(height * ratio, height) else Offset(width, width / ratio)

@Composable
internal fun DesktopImageSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    val currentChange by rememberUpdatedState(onChange)
    val currentValue by rememberUpdatedState(value)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        Box(Modifier.fillMaxWidth().height(28.dp).background(DesktopBootstrapColors.muted)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { currentChange(it.coerceIn(0f, 1f)); true }
            }.onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft, Key.DirectionDown -> { currentChange((currentValue - 0.01f).coerceAtLeast(0f)); true }
                    Key.DirectionRight, Key.DirectionUp -> { currentChange((currentValue + 0.01f).coerceAtMost(1f)); true }
                    Key.MoveHome -> { currentChange(0f); true }
                    Key.MoveEnd -> { currentChange(1f); true }
                    else -> false
                }
            }.focusable()
            .pointerInput(Unit) { detectTapGestures { currentChange((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectDragGestures { change, _ ->
                change.consume(); currentChange((change.position.x / size.width).coerceIn(0f, 1f))
            } }) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxHeight().background(DesktopBootstrapColors.primary))
        }
    }
}
