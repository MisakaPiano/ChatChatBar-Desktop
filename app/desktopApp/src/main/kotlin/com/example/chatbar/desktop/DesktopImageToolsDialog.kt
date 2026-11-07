package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import kotlinx.coroutines.*

@Composable
internal fun DesktopImageToolsDialog(source: ByteArray, picker: DesktopFilePicker, onClose: () -> Unit,
    onGuidanceApply: (suspend (ByteArray, ByteArray?, NovelAiFocusedInpaintRegion) -> Boolean)? = null,
    initialMask: ByteArray? = null, initialRegion: NovelAiFocusedInpaintRegion? = null,
    onImageApply: ((ByteArray) -> Unit)? = null, onProcessedImage: ((ByteArray) -> Unit)? = null) {
    var current by remember { mutableStateOf(source) }
    val undo = remember { mutableStateListOf<Triple<ByteArray, BufferedImage?, NovelAiFocusedInpaintRegion>>() }
    val redo = remember { mutableStateListOf<Triple<ByteArray, BufferedImage?, NovelAiFocusedInpaintRegion>>() }
    var mask by remember { mutableStateOf(initialMask?.let { DesktopImageEditing.decode(it) }) }
    var region by remember { mutableStateOf(initialRegion ?: NovelAiFocusedInpaintRegion(0f, 0f, 1f, 1f)) }
    var maskMode by remember { mutableStateOf(false) }
    var paintMode by remember { mutableStateOf("框选") }
    val preferences = LocalDesktopImageToolPreferences.current
    val density = LocalDensity.current.density
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var brushSizeDp by remember { mutableStateOf(36f) }
    val brush = desktopBrushRadius(brushSizeDp, canvasSize, density)
    var preferencesLoaded by remember(preferences) { mutableStateOf(preferences == null) }
    var preferenceError by remember { mutableStateOf(false) }
    var preferenceRetry by remember { mutableStateOf(0) }
    var preferenceWrites by remember { mutableStateOf(0) }
    val stroke = remember { mutableStateListOf<Pair<Float, Float>>() }
    var savedPath by remember { mutableStateOf<java.nio.file.Path?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("拖动框选区域；可打码、旋转或处理 APNG") }
    val static = remember(current) { runCatching { DesktopImageEditing.requireStatic(current) }.isSuccess }
    val dimensions = remember(current) { runCatching { DesktopImageEditing.decode(current, 1600).let { it.width to it.height } }.getOrDefault(1 to 1) }
    val fullSize by produceState<Pair<Int, Int>?>(null, current) {
        value = withContext(Dispatchers.IO) { runCatching { DesktopImageEditing.decode(current).let { it.width to it.height } }.getOrNull() }
    }
    val scope = rememberCoroutineScope()
    fun mode(type: String) = when (type) { "Black" -> "黑色遮盖"; "White" -> "白色遮盖"; else -> "马赛克画笔" }
    LaunchedEffect(preferences, preferenceRetry) {
        if (preferences != null) try {
            val saved = preferences.load()
            brushSizeDp = saved.sizeDp
            if (saved.saved && onGuidanceApply == null && saved.type in setOf("Mosaic", "Black", "White")) paintMode = mode(saved.type)
            preferencesLoaded = true; preferenceError = false
            if (saved.type !in setOf("Mosaic", "Black", "White")) status = "已保留尚不支持的笔刷类型；可手动选择画笔"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { preferenceError = true; status = "画笔偏好读取失败；原配置保留，请重试" }
    }
    fun savePreference(sizeDp: Float? = null, type: String? = null) {
        if (preferences == null) return
        preferenceWrites++
        scope.launch {
            try { preferences.save(sizeDp, type) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "画笔偏好未保存；当前编辑保留，可重试选择" }
            finally { preferenceWrites-- }
        }
    }
    fun snapshot() = Triple(current, mask, region)
    fun restore(value: Triple<ByteArray, BufferedImage?, NovelAiFocusedInpaintRegion>) { current = value.first; mask = value.second; region = value.third }
    fun checkpoint() {
        undo += snapshot(); redo.clear()
        fun cost() = undo.sumOf { it.first.size.toLong() + (it.second?.let { image -> image.width.toLong() * image.height * 4 } ?: 0) }
        while (undo.size > 1 && (undo.size > 20 || cost() > 128L * 1024 * 1024)) undo.removeAt(0)
    }
    fun process(clearMask: Boolean = false, record: Boolean = true, work: suspend () -> ByteArray) { scope.launch {
        busy = true
        try { val bytes = withContext(Dispatchers.IO) { work() }; if (record) checkpoint(); current = bytes; if (clearMask) mask = null; status = "处理完成，源文件未改变" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "处理失败；来源和当前结果保留" }
        finally { busy = false }
    } }
    DesktopImageToolWindow(if (onGuidanceApply == null) "图像工具" else "图像引导工作区", { if (!busy && preferenceWrites == 0) onClose() }, width = 920.dp, height = 800.dp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val controlsHeight = maxHeight * .55f
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText(status)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                var start by remember { mutableStateOf(Offset.Zero) }
                Box(Modifier.aspectRatio(dimensions.first.toFloat() / dimensions.second).fillMaxSize().onSizeChanged { canvasSize = it }.pointerInput(current, maskMode, paintMode, brush, fullSize, busy, preferencesLoaded) {
                    fun point(position: Offset) = Offset((position.x / size.width).coerceIn(0f, 1f), (position.y / size.height).coerceIn(0f, 1f))
                    detectDragGestures(onDragStart = { start = point(it); stroke.clear(); if (!busy && preferencesLoaded) { checkpoint(); stroke += start.x to start.y } },
                        onDragEnd = {
                            if (!busy && preferencesLoaded && static && !maskMode && paintMode != "框选") {
                                val points = stroke.toList(); stroke.clear()
                                process(record = false) { DesktopImageEditing.png(DesktopImageTools.paint(DesktopImageEditing.decode(current), points, brush,
                                    when (paintMode) { "黑色遮盖" -> 0xff000000.toInt(); "白色遮盖" -> 0xffffffff.toInt(); else -> null })) }
                            } else stroke.clear()
                        }, onDragCancel = { stroke.clear() }, onDrag = { change, _ ->
                        change.consume()
                        if (busy || !preferencesLoaded) return@detectDragGestures
                        val end = point(change.position)
                        if (!maskMode && paintMode != "框选" && static) {
                            stroke += end.x to end.y
                        } else if (maskMode && static && fullSize != null) {
                            val dimensions = requireNotNull(fullSize)
                            val old = mask ?: BufferedImage(dimensions.first, dimensions.second, BufferedImage.TYPE_INT_ARGB).also { image ->
                                image.createGraphics().let { g -> g.color = java.awt.Color.BLACK; g.fillRect(0, 0, image.width, image.height); g.dispose() }
                            }
                            val next = raster(old.width, old.height, old.pixels())
                            val radius = (brush * minOf(next.width, next.height)).toInt().coerceAtLeast(1)
                            next.createGraphics().let { g -> g.color = java.awt.Color.WHITE; g.fillOval((end.x * next.width).toInt() - radius, (end.y * next.height).toInt() - radius, radius * 2, radius * 2); g.dispose() }
                            mask = next
                        } else {
                            val x = minOf(start.x, end.x); val y = minOf(start.y, end.y)
                            val w = kotlin.math.abs(start.x - end.x); val h = kotlin.math.abs(start.y - end.y)
                            if (w > 0 && h > 0) region = NovelAiFocusedInpaintRegion(x, y, w, h)
                        }
                    })
                }) {
                    DesktopOwnedImage(current.contentHashCode().toString(), { current }, Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        if (!maskMode && paintMode != "框选") stroke.forEach { (x, y) -> drawCircle(Color.Cyan.copy(alpha = .35f), brush * minOf(size.width, size.height), Offset(x * size.width, y * size.height)) }
                        drawRect(Color.Cyan, Offset(region.x * size.width, region.y * size.height),
                            Size(region.width * size.width, region.height * size.height), style = Stroke(2.dp.toPx()))
                    }
                    mask?.let { painted ->
                        DesktopOwnedImage("mask-${painted.hashCode()}", {
                            val pixels = painted.pixels().map { if (it and 0xffffff != 0) 0xffff4444.toInt() else 0 }.toIntArray()
                            DesktopImageEditing.png(raster(painted.width, painted.height, pixels))
                        }, Modifier.fillMaxSize().alpha(.4f))
                    }
                }
            }
            DesktopImageToolControls(controlsHeight) {
            StudioActions {
                listOf("框选", "马赛克画笔", "黑色遮盖", "白色遮盖").forEach { mode ->
                    BootstrapButton(if (paintMode == mode) "✓ $mode" else mode, enabled = static && !busy && preferencesLoaded) {
                        paintMode = mode; maskMode = false
                        if (mode != "框选") savePreference(type = when (mode) { "黑色遮盖" -> "Black"; "白色遮盖" -> "White"; else -> "Mosaic" })
                    }
                }
            }
            if (preferencesLoaded) DesktopImageSlider("画笔大小 ${brushSizeDp.toInt()} dp", (brushSizeDp - 16f) / 56f) {
                brushSizeDp = 16f + it * 56f; savePreference(sizeDp = brushSizeDp)
            }
            if (preferenceError) BootstrapButton("重试读取画笔偏好") { preferenceRetry++ }
            StudioActions {
                BootstrapButton("撤销", enabled = undo.isNotEmpty() && !busy) { undoCanvasState(snapshot(), undo, redo)?.let(::restore) }
                BootstrapButton("重做", enabled = redo.isNotEmpty() && !busy) { redoCanvasState(snapshot(), undo, redo)?.let(::restore) }
                BootstrapButton("重置", enabled = !busy) { checkpoint(); current = source; mask = initialMask?.let { DesktopImageEditing.decode(it) }; region = initialRegion ?: NovelAiFocusedInpaintRegion(0f, 0f, 1f, 1f) }
                BootstrapButton("旋转 90°", enabled = static && !busy) { process(clearMask = true) { DesktopImageEditing.png(DesktopImageTools.rotate(DesktopImageEditing.decode(current))) } }
                BootstrapButton("区域打码", enabled = static && !busy) { process { DesktopImageEditing.png(DesktopImageTools.mosaic(DesktopImageEditing.decode(current), region)) } }
                if (onGuidanceApply != null) {
                    StudioToggle("绘制蒙版", maskMode) { maskMode = !maskMode }
                    BootstrapButton("清除蒙版", enabled = !busy) { checkpoint(); mask = null }
                }
            }
            StudioActions {
                if (onGuidanceApply == null && onImageApply == null) {
                    if (onProcessedImage != null) BootstrapButton("完成 · 隐私 PNG 副本", enabled = static && !busy && preferenceWrites == 0) { scope.launch {
                        busy = true
                        try { onProcessedImage(DesktopImageTools.strip(current)); onClose() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { status = "隐私导出失败；来源和当前编辑保留" }
                        finally { busy = false }
                    } }
                    BootstrapButton("APNG 伪装", enabled = !busy) { process { DesktopImageTools.disguise(current) } }
                    BootstrapButton("APNG 还原", enabled = !busy) { process { DesktopImageTools.restore(current) } }
                    BootstrapButton("隐私导出 · 清除元数据与像素隐写", enabled = static && !busy) { process { DesktopImageTools.strip(current) } }
                    StatusText("隐私 PNG 副本会清除像素最低位；颜色/透明度可能有极轻微变化，原图保留。")
                    BootstrapButton(if (static) "复制结果" else "复制文件", enabled = !busy) { scope.launch {
                        busy = true
                        val result = current
                        val imageCopy = static
                        try { withContext(Dispatchers.IO) { copyDesktopImageResult(result) }; status = if (imageCopy) "已复制图片" else "已复制原始动画文件；请粘贴到支持文件的应用" }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { status = "复制失败，当前结果保留；可重试或另存文件" }
                        finally { busy = false }
                    } }
                    savedPath?.let { path -> BootstrapButton("显示已保存副本") { ProcessBuilder("explorer.exe", "/select,", path.toString()).start() } }
                    BootstrapButton("另存结果", enabled = !busy) { scope.launch {
                        val extension = if (current.take(3).toByteArray().toString(Charsets.US_ASCII) == "GIF") "gif" else if (current.firstOrNull() == 0xff.toByte()) "jpg" else if (current.size >= 12 && String(current, 8, 4, Charsets.US_ASCII) == "WEBP") "webp" else "png"
                        picker.pickSaveFile(DesktopFileType("图片", listOf(extension)), "image.$extension")?.let {
                            withContext(Dispatchers.IO) { DesktopExternalFileWriter.writeBytes(it, current) }; savedPath = it; status = "已保存副本"
                        }
                    } }
                } else if (onGuidanceApply != null) BootstrapButton("应用", enabled = static && !busy && preferenceWrites == 0) { scope.launch {
                    busy = true
                    try { if (onGuidanceApply(current, mask?.let(DesktopImageEditing::png), region)) onClose() else status = "无法应用；当前结果保留" }
                    catch (_: Exception) { status = "无法应用；请检查聚焦区域" }
                    finally { busy = false }
                } }
                if (onImageApply != null) BootstrapButton("应用到导出副本", enabled = static && !busy && preferenceWrites == 0) { scope.launch {
                    busy = true
                    try { onImageApply(DesktopImageTools.strip(current)); onClose() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status = "隐私导出失败；源图和当前编辑保留" }
                    finally { busy = false }
                } }
                BootstrapButton("取消", enabled = !busy && preferenceWrites == 0, onClick = onClose)
            }
            }
        }
        }
    }
}
