package com.example.chatbar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/** Transient shared view only; image records and immutable source/result bytes are untouched. */
@Stable
internal class DesktopPostProcessComparisonState {
    var fraction by mutableFloatStateOf(.5f)
    var transform by mutableStateOf(DesktopViewerTransform())
        private set
    var viewport by mutableStateOf(IntSize.Zero)
        private set
    var source by mutableStateOf(IntSize(1, 1))
        private set
    val fitted: Offset get() {
        val scale = minOf(viewport.width.toFloat() / source.width, viewport.height.toFloat() / source.height)
        return Offset(source.width * scale, source.height * scale)
    }
    fun configure(size: IntSize, dimensions: IntSize) {
        require(dimensions.width > 0 && dimensions.height > 0)
        viewport = size; source = dimensions; transform = clamp(transform)
    }
    private fun clamp(value: DesktopViewerTransform): DesktopViewerTransform {
        val limitX = maxOf(0f, (fitted.x * value.zoom - viewport.width) / 2)
        val limitY = maxOf(0f, (fitted.y * value.zoom - viewport.height) / 2)
        return value.copy(pan = Offset(value.pan.x.coerceIn(-limitX, limitX), value.pan.y.coerceIn(-limitY, limitY)))
    }
    fun zoomAt(next: Float, position: Offset) {
        if (!next.isFinite()) return
        transform = clamp(transform.zoomAt(next, position - Offset(viewport.width / 2f, viewport.height / 2f), 1f, 5f))
    }
    fun panBy(delta: Offset) { if (transform.zoom > 1f) transform = clamp(transform.copy(pan = transform.pan + delta)) }
    fun reset() { transform = DesktopViewerTransform(); fraction = .5f }
}

/** Source-fitted bounds and transform are shared; result clipping remains in viewport coordinates. */
@Composable
internal fun DesktopPostProcessComparison(identity: String, original: ByteArray, result: ByteArray,
    sourceSize: IntSize, modifier: Modifier = Modifier, state: DesktopPostProcessComparisonState? = null) {
    val view = state ?: remember(identity) { DesktopPostProcessComparisonState() }
    val density = LocalDensity.current
    val halfHit = with(density) { 20.dp.toPx() }
    val background = DesktopBootstrapColors.background
    val primary = DesktopBootstrapColors.primary
    var measured by remember { mutableStateOf(IntSize.Zero) }
    SideEffect { if (view.viewport != measured || view.source != sourceSize) view.configure(measured, sourceSize) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(DesktopBootstrapColors.background)
            .onSizeChanged { measured = it; view.configure(it, sourceSize) }
            .semantics {
                contentDescription = "原图与处理结果对比"
                progressBarRangeInfo = ProgressBarRangeInfo(view.fraction, 0f..1f)
                setProgress { if (it.isFinite()) { view.fraction = it.coerceIn(0f, 1f); true } else false }
            }
            .pointerInput(identity) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val divider = abs(down.position.x - view.fraction * size.width) <= halfHit
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull { it.id == down.id }?.let { change ->
                            if (divider) { view.fraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f); change.consume() }
                            else if (view.transform.zoom > 1f) { view.panBy(change.position - change.previousPosition); change.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(identity) { awaitPointerEventScope { while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll) event.changes.firstOrNull()?.let { change ->
                    view.zoomAt(view.transform.zoom * exp(-change.scrollDelta.y * .1f), change.position)
                    event.changes.forEach { it.consume() }
                }
            } } }) {
            val fitted = view.fitted
            val decodeScale = ceil(view.transform.zoom).toInt()
            val target = remember(fitted, decodeScale) {
                { dimensions: IntSize -> IntSize((fitted.x * decodeScale).roundToInt().coerceIn(1, dimensions.width),
                    (fitted.y * decodeScale).roundToInt().coerceIn(1, dimensions.height)) }
            }
            @Composable fun Layer(bytes: ByteArray, suffix: String, output: Boolean) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    scaleX = view.transform.zoom; scaleY = view.transform.zoom
                    translationX = view.transform.pan.x; translationY = view.transform.pan.y
                }, contentAlignment = Alignment.Center) {
                    DesktopOwnedImage("comparison-$identity-$suffix", { bytes },
                        Modifier.size(with(density) { fitted.x.coerceAtLeast(1f).toDp() }, with(density) { fitted.y.coerceAtLeast(1f).toDp() }),
                        postProcessOutput = output, displayTargetForSource = target, contentScale = ContentScale.FillBounds)
                }
            }
            Layer(original, "source", false)
            Box(Modifier.fillMaxSize().drawWithContent {
                clipRect(left = size.width * view.fraction) {
                    drawRect(background)
                    this@drawWithContent.drawContent()
                }
            }) { Layer(result, "result", true) }
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * view.fraction
                drawLine(primary, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                drawCircle(background, 12.dp.toPx(), Offset(x, size.height / 2))
                drawLine(primary, Offset(x - 5.dp.toPx(), size.height / 2), Offset(x + 5.dp.toPx(), size.height / 2), 2.dp.toPx())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioAction("适应 / 重置对比", selected = false) { view.reset() }
            StatusText("${(view.transform.zoom * 100).roundToInt()}% · 滚轮缩放，拖动图像或分界线")
        }
    }
}
