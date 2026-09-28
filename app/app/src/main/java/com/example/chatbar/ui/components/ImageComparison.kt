package com.example.chatbar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.chatbar.domain.image.ImportedProcessImage
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@Stable
class ImageComparisonState {
    var fraction by mutableFloatStateOf(0.5f)
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    fun reset() { fraction = 0.5f; scale = 1f; offset = Offset.Zero }
}

/** Both layers share image bounds and a transform. Clipping stays in viewport coordinates. */
@Composable
fun ImageComparison(
    source: ImportedProcessImage,
    result: ImportedProcessImage?,
    state: ImageComparisonState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val colors = ChatBarTheme.colors
    BoxWithConstraints(modifier.clipToBounds().background(colors.surfaceSubtle)) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val fit = minOf(widthPx / source.width, heightPx / source.height)
        val imageWidth = source.width * fit
        val imageHeight = source.height * fit
        val halfHit = with(density) { 24.dp.toPx() }
        fun clampOffset(offset: Offset, scale: Float) = Offset(
            offset.x.coerceIn(-maxOf(0f, (imageWidth * scale - widthPx) / 2), maxOf(0f, (imageWidth * scale - widthPx) / 2)),
            offset.y.coerceIn(-maxOf(0f, (imageHeight * scale - heightPx) / 2), maxOf(0f, (imageHeight * scale - heightPx) / 2))
        )
        Box(Modifier.fillMaxSize().pointerInput(source.path, result?.path, widthPx, heightPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var divider = result != null && abs(down.position.x - state.fraction * widthPx) <= halfHit
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2) {
                        divider = false
                        val previous = state.scale
                        val next = (previous * event.calculateZoom()).coerceIn(1f, 5f)
                        val center = event.calculateCentroid(useCurrent = false) - Offset(widthPx / 2, heightPx / 2)
                        val translated = center - (center - state.offset) * (next / previous) + event.calculatePan()
                        state.scale = next
                        state.offset = clampOffset(translated, next)
                        event.changes.forEach { it.consume() }
                    } else if (divider) {
                        event.changes.firstOrNull { it.id == down.id }?.let {
                            state.fraction = (it.position.x / widthPx).coerceIn(0f, 1f)
                            it.consume()
                        }
                    } else if (state.scale > 1f) {
                        state.offset = clampOffset(state.offset + event.calculatePan(), state.scale)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }.then(if (result != null) Modifier.semantics {
            contentDescription = "原图与处理结果对比，调整原图显示比例"
            progressBarRangeInfo = ProgressBarRangeInfo(state.fraction, 0f..1f)
            setProgress { state.fraction = it.coerceIn(0f, 1f); true }
        } else Modifier)) {
            ComparisonLayer(source, state, imageWidth, imageHeight)
            if (result != null) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    clipRect(left = size.width * state.fraction) {
                        drawRect(colors.surfaceSubtle)
                        this@drawWithContent.drawContent()
                    }
                }) { ComparisonLayer(result, state, imageWidth, imageHeight) }
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width * state.fraction
                    drawLine(colors.foreground, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                    drawCircle(colors.surface, 14.dp.toPx(), Offset(x, size.height / 2))
                    drawLine(colors.foreground, Offset(x - 6.dp.toPx(), size.height / 2), Offset(x + 6.dp.toPx(), size.height / 2), 2.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun ComparisonLayer(image: ImportedProcessImage, state: ImageComparisonState, width: Float, height: Float) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Increase decode resolution in stable steps; never magnify the screen-sized thumbnail alone.
    val decodeScale = ceil(state.scale).toInt()
    val requestWidth = (width * decodeScale).roundToInt().coerceIn(1, image.width)
    val requestHeight = (height * decodeScale).roundToInt().coerceIn(1, image.height)
    val request = remember(image.path, requestWidth, requestHeight) {
        ImageRequest.Builder(context).data(File(image.path)).size(requestWidth, requestHeight)
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(false).build()
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() }),
            contentScale = ContentScale.FillBounds
        )
    }
}
