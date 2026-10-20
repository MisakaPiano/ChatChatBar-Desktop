package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.*

internal enum class DesktopScreenshotFit { WIDTH, WINDOW, MANUAL }

/** Extents are numbers, never a scaled raster or an enormous Compose child layout. */
@Stable
internal class DesktopScreenshotViewport(val imageWidth: Int, val imageHeight: Int) {
    var zoom by mutableDoubleStateOf(1.0); private set
    var x by mutableDoubleStateOf(0.0); private set
    var y by mutableDoubleStateOf(0.0); private set
    var width by mutableIntStateOf(0); private set
    var height by mutableIntStateOf(0); private set
    var fit by mutableStateOf(DesktopScreenshotFit.WIDTH); private set
    init { DesktopChatScreenshotRenderer.validatePlan(imageWidth, imageHeight) }
    val contentWidth get() = imageWidth * zoom
    val contentHeight get() = imageHeight * zoom
    fun scrollTo(newX: Double, newY: Double) {
        x = newX.coerceIn(0.0, max(0.0, contentWidth - width))
        y = newY.coerceIn(0.0, max(0.0, contentHeight - height))
    }
    fun zoomTo(value: Double, anchorX: Double = width / 2.0, anchorY: Double = height / 2.0) {
        require(value.isFinite())
        val px = (x + anchorX) / zoom; val py = (y + anchorY) / zoom
        zoom = value.coerceIn(.01, 8.0)
        scrollTo(px * zoom - anchorX, py * zoom - anchorY)
    }
    fun manualZoom(value: Double, anchorX: Double = width / 2.0, anchorY: Double = height / 2.0) {
        fit = DesktopScreenshotFit.MANUAL; zoomTo(value, anchorX, anchorY)
    }
    fun wheel(dx: Double, dy: Double, control: Boolean, shift: Boolean, anchorX: Double, anchorY: Double) {
        if (control) manualZoom(zoom * 1.15.pow(-dy), anchorX, anchorY)
        else if (shift) scrollTo(x + dy * 40, y)
        else scrollTo(x + dx * 40, y + dy * 40)
    }
    fun fitTo(mode: DesktopScreenshotFit) {
        fit = mode
        if (width > 0 && height > 0) zoomTo(when (mode) {
            DesktopScreenshotFit.WIDTH -> width.toDouble() / imageWidth
            DesktopScreenshotFit.WINDOW -> min(width.toDouble() / imageWidth, height.toDouble() / imageHeight)
            DesktopScreenshotFit.MANUAL -> zoom
        })
    }
    fun resize(w: Int, h: Int) {
        val cx = (x + width / 2.0) / zoom; val cy = (y + height / 2.0) / zoom
        val initial = width == 0 || height == 0
        width = w.coerceAtLeast(0); height = h.coerceAtLeast(0)
        fitTo(fit)
        if (initial) scrollTo(0.0, 0.0) else scrollTo(cx * zoom - width / 2.0, cy * zoom - height / 2.0)
    }
    fun adapter(horizontal: Boolean) = object : ScrollbarAdapter {
        override val scrollOffset get() = if (horizontal) x else y
        override val contentSize get() = if (horizontal) contentWidth else contentHeight
        override val viewportSize get() = (if (horizontal) width else height).toDouble()
        override suspend fun scrollTo(scrollOffset: Double) {
            if (horizontal) this@DesktopScreenshotViewport.scrollTo(scrollOffset, y)
            else this@DesktopScreenshotViewport.scrollTo(x, scrollOffset)
        }
    }
}

@Composable
internal fun DesktopScreenshotPreview(png: DesktopScreenshotPng, viewport: DesktopScreenshotViewport, modifier: Modifier = Modifier) {
    val image = remember(png) {
        require(png.bytes.size <= 40 * 1024 * 1024)
        SkiaImage.makeFromEncoded(png.bytes).also {
            if (it.width != png.width || it.height != png.height) { it.close(); error("PNG 尺寸已变化") }
        }
    }
    DisposableEffect(image) { onDispose { image.close() } }
    val bitmap = remember(image) { image.toComposeImageBitmap() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            StudioAction("缩小") { viewport.manualZoom(viewport.zoom / 1.25) }
            StatusText("${(viewport.zoom * 100).roundToInt()}%")
            StudioAction("放大") { viewport.manualZoom(viewport.zoom * 1.25) }
            StudioAction("适应宽度") { viewport.fitTo(DesktopScreenshotFit.WIDTH) }
            StudioAction("适应窗口") { viewport.fitTo(DesktopScreenshotFit.WINDOW) }
            StudioAction("100%") { viewport.manualZoom(1.0) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(DesktopBootstrapColors.muted)) {
            Canvas(Modifier.fillMaxSize().padding(end = 14.dp, bottom = 14.dp).clipToBounds()
                .onSizeChanged { viewport.resize(it.width, it.height) }
                .semantics { contentDescription = "长图预览：Ctrl加滚轮缩放，方向键滚动" }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                        Key.DirectionLeft -> { viewport.scrollTo(viewport.x - 40, viewport.y); true }
                        Key.DirectionRight -> { viewport.scrollTo(viewport.x + 40, viewport.y); true }
                        Key.DirectionUp -> { viewport.scrollTo(viewport.x, viewport.y - 40); true }
                        Key.DirectionDown -> { viewport.scrollTo(viewport.x, viewport.y + 40); true }
                        Key.PageUp -> { viewport.scrollTo(viewport.x, viewport.y - viewport.height); true }
                        Key.PageDown -> { viewport.scrollTo(viewport.x, viewport.y + viewport.height); true }
                        else -> false
                    }
                }.focusable().pointerInput(viewport) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.type == PointerEventType.Scroll) {
                                val c = e.changes.first()
                                viewport.wheel(c.scrollDelta.x.toDouble(), c.scrollDelta.y.toDouble(), e.keyboardModifiers.isCtrlPressed,
                                    e.keyboardModifiers.isShiftPressed, c.position.x.toDouble(), c.position.y.toDouble())
                                e.changes.forEach { it.consume() }
                            }
                        }
                    }
                }) {
                withTransform({ translate(-viewport.x.toFloat(), -viewport.y.toFloat()); scale(viewport.zoom.toFloat(), viewport.zoom.toFloat(), pivot = androidx.compose.ui.geometry.Offset.Zero) }) {
                    drawImage(bitmap)
                }
            }
            VerticalScrollbar(remember(viewport) { viewport.adapter(false) }, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 14.dp).background(DesktopBootstrapColors.border.copy(alpha = .4f)).semantics { contentDescription = "预览纵向滚动条" })
            HorizontalScrollbar(remember(viewport) { viewport.adapter(true) }, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 14.dp).background(DesktopBootstrapColors.border.copy(alpha = .4f)).semantics { contentDescription = "预览横向滚动条" })
        }
    }
}
