package com.example.chatbar.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged

internal data class DesktopViewerTransform(val zoom: Float = 1f, val pan: Offset = Offset.Zero) {
    fun zoomAt(next: Float, pointerFromCenter: Offset): DesktopViewerTransform {
        val bounded = next.coerceIn(1f, 16f)
        return if (bounded == 1f) DesktopViewerTransform() else DesktopViewerTransform(bounded,
            pointerFromCenter - (pointerFromCenter - pan) * (bounded / zoom))
    }
}

/** Same animation-preserving interaction surface for owned and transient image collections. */
@Composable
internal fun DesktopImageZoomSurface(reference: String, read: (String) -> ByteArray, modifier: Modifier = Modifier,
    resetRevision: Int = 0) {
    var transform by remember(reference, resetRevision) { mutableStateOf(DesktopViewerTransform()) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val dimensions by produceState<Pair<Int, Int>?>(null, reference) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching {
            org.jetbrains.skia.Data.makeFromBytes(read(reference)).use { data -> org.jetbrains.skia.Codec.makeFromData(data).use { it.width to it.height } }
        }.getOrNull() }
    }
    val latest by rememberUpdatedState(transform)
    Box(modifier.clipToBounds().onSizeChanged { size = it }
        .pointerInput(reference, dimensions) { detectTapGestures(onDoubleTap = { position ->
            val native = dimensions?.let { minOf(size.width.toFloat() / it.first, size.height.toFloat() / it.second) }
            transform = if (latest.zoom > 1f) DesktopViewerTransform() else latest.zoomAt(
                native?.takeIf { it > 0f && it < 1f }?.let { 1f / it } ?: 2f, position - Offset(size.width / 2f, size.height / 2f))
        }) }
        .pointerInput(reference) { detectDragGestures { change, delta ->
            if (latest.zoom > 1f) { change.consume(); transform = latest.copy(pan = latest.pan + delta) }
        } }
        .pointerInput(reference) { awaitPointerEventScope { while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Scroll) event.changes.firstOrNull()?.let { change ->
                transform = latest.zoomAt(latest.zoom * kotlin.math.exp(-change.scrollDelta.y * .1f),
                    change.position - Offset(size.width / 2f, size.height / 2f))
                event.changes.forEach { it.consume() }
            }
        } } }) {
        DesktopOwnedImage(reference, read, Modifier.fillMaxSize().graphicsLayer {
            scaleX = transform.zoom; scaleY = transform.zoom; translationX = transform.pan.x; translationY = transform.pan.y
        })
    }
}
