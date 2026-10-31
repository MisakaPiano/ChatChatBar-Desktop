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
    fun zoomAt(next: Float, pointerFromCenter: Offset, minimum: Float = .1f, maximum: Float = 16f): DesktopViewerTransform {
        val bounded = next.coerceIn(minimum, maximum)
        return if (bounded == 1f) DesktopViewerTransform() else DesktopViewerTransform(bounded,
            pointerFromCenter - (pointerFromCenter - pan) * (bounded / zoom))
    }
}

internal class DesktopImageZoomState {
    var transform by mutableStateOf(DesktopViewerTransform())
    var fitScale by mutableFloatStateOf(1f)
    var sourceSize by mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
    val percent: Int get() = kotlin.math.round(transform.zoom * fitScale * 100).toInt().coerceAtLeast(1)
    fun fit() { transform = DesktopViewerTransform() }
    fun zoomAt(value: Float, anchor: Offset = Offset.Zero) {
        val native = 1f / fitScale.coerceAtLeast(.000001f)
        transform = transform.zoomAt(value, anchor, minOf(.1f, native), maxOf(16f, native))
    }
    fun zoomBy(factor: Float) { zoomAt(transform.zoom * factor) }
    fun actualSize() { zoomAt(1f / fitScale.coerceAtLeast(.000001f)) }
}

/** Same animation-preserving interaction surface for owned and transient image collections. */
@Composable
internal fun DesktopImageZoomSurface(reference: String, read: (String) -> ByteArray, modifier: Modifier = Modifier,
    resetRevision: Int = 0, onOpenViewer: (() -> Unit)? = null, zoomState: DesktopImageZoomState? = null) {
    val view = zoomState ?: remember(reference, resetRevision) { DesktopImageZoomState() }
    var transform by view::transform
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var dimensions by remember(reference) { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }
    val latest by rememberUpdatedState(transform)
    // Read these states in composition. Reads only inside SideEffect are not observed and
    // would leave Fit/100% at the initial scale after asynchronous decode or window resize.
    val observedDimensions = dimensions
    val observedViewport = size
    SideEffect {
        observedDimensions?.let { source ->
            view.sourceSize = source
            if (observedViewport.width > 0 && observedViewport.height > 0) view.fitScale = minOf(observedViewport.width.toFloat() / source.width, observedViewport.height.toFloat() / source.height)
        }
    }
    LaunchedEffect(reference, resetRevision) { view.fit() }
    val openViewer by rememberUpdatedState(onOpenViewer)
    Box(modifier.clipToBounds().onSizeChanged { size = it }
        .pointerInput(reference) { detectTapGestures(onDoubleTap = { position ->
            val open = openViewer
            if (open != null) open() else {
                if (latest.zoom != 1f) view.fit() else view.zoomAt(1f / view.fitScale,
                    position - Offset(size.width / 2f, size.height / 2f))
            }
        }) }
        .pointerInput(reference) { detectDragGestures { change, delta ->
            if (latest.zoom > 1f) { change.consume(); transform = latest.copy(pan = latest.pan + delta) }
        } }
        .pointerInput(reference) { awaitPointerEventScope { while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Scroll) event.changes.firstOrNull()?.let { change ->
                view.zoomAt(latest.zoom * kotlin.math.exp(-change.scrollDelta.y * .1f),
                    change.position - Offset(size.width / 2f, size.height / 2f))
                event.changes.forEach { it.consume() }
            }
        } } }) {
        DesktopOwnedImage(reference, read, Modifier.fillMaxSize().graphicsLayer {
            scaleX = transform.zoom; scaleY = transform.zoom; translationX = transform.pan.x; translationY = transform.pan.y
        }, onDimensions = { dimensions = it })
    }
}
