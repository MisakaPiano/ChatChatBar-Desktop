package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*

@Composable
internal fun DesktopImagePostProcessPanel(path: Path, controller: DesktopNovelAiStudioController, onUse: (ByteArray) -> Unit) {
    val state by controller.state.collectAsState()
    val tasks by controller.taskEntries.collectAsState()
    val busy = tasks.any { it.kind == DesktopTaskKind.NOVELAI && it.status == DesktopTaskStatus.RUNNING }
    var tab by remember(path) { mutableStateOf(NovelAiPostProcessTab.UPSCALE) }
    var options by remember(path) { mutableStateOf(NovelAiEnhanceOptions()) }
    var enhanceResult by remember(path) { mutableStateOf<Pair<ByteArray, NovelAiEnhanceOptions>?>(null) }
    var upscaleResult by remember(path) { mutableStateOf<ByteArray?>(null) }
    var status by remember(path) { mutableStateOf("") }
    var sourceError by remember(path) { mutableStateOf("") }
    var source by remember(path) { mutableStateOf<NovelAiEnhanceSource?>(null) }
    val scope = rememberCoroutineScope()
    val input by produceState<Pair<ByteArray, Pair<Int, Int>>?>(null, path) {
        value = withContext(Dispatchers.IO) { try {
            require(Files.size(path) <= DesktopImageEditing.MAX_BYTES)
            val bytes = Files.readAllBytes(path); DesktopImageEditing.requireStatic(bytes)
            val raster = DesktopImageEditing.decode(bytes)
            source = runCatching { DesktopImageMetadata.readEnhance(path.toString()) }.getOrNull()
            bytes to (raster.width to raster.height)
        } catch (_: Exception) { sourceError = "仅支持可读取的静态图片；动画保持原样，请先还原规范伪装再处理"; null } }
    }
    StudioChips("图片处理", NovelAiPostProcessTab.entries, tab, { it.label }) { if (!busy) tab = it }
    val dimensions = input?.second
    val recipe = source
    val cost = dimensions?.let { (w, h) -> if (tab == NovelAiPostProcessTab.UPSCALE) NovelAiPostProcessPolicy.upscaleCost(w, h)
        else recipe?.let { runCatching { NovelAiPostProcessPolicy.enhanceCost(it, w, h, options, state.account).anlas }.getOrNull() } }
    if (tab == NovelAiPostProcessTab.ENHANCE) {
        if (recipe == null) StatusText("缺少受支持的完整生成元数据；Enhance 不可用，可选择 Upscale", DesktopBootstrapColors.warning)
        else if (dimensions != null) {
            val scales = NovelAiPostProcessPolicy.scales(dimensions.first, dimensions.second, recipe.settings.model)
            StudioChips("倍率", scales, options.scale, { it.label }) { if (!busy) options = options.copy(scale = it) }
            StudioChips("增强幅度", (1..5).toList(), options.magnitude, { it.toString() }) { if (!busy) options = options.withMagnitude(it) }
            StudioField("Strength 0–1", options.strength.toString()) { text -> if (!busy) text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { options = options.copy(strength = it) } }
            StudioField("Noise 0–1", options.noise.toString()) { text -> if (!busy) text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { options = options.copy(noise = it) } }
        }
    } else StatusText("2× Upscale · 不改变 Studio 模型 · 超过 3,145,728 输入像素时不可用")
    if (sourceError.isNotEmpty()) StatusText(sourceError, DesktopBootstrapColors.warning)
    StatusText(cost?.let { "预计 $it Anlas" } ?: "费用 / 输入资格不可用")
    val validScale = tab == NovelAiPostProcessTab.UPSCALE || recipe != null && dimensions != null && options.scale in NovelAiPostProcessPolicy.scales(dimensions.first, dimensions.second, recipe.settings.model)
    StudioAction(if (busy) "停止" else tab.label, enabled = busy || (state.credentialConfigured && input != null && cost != null && cost <= 140 && validScale)) {
        if (busy) controller.stop() else scope.launch {
            val selected = tab; val selectedOptions = options
            controller.postProcess(requireNotNull(input).first, selected, recipe, selectedOptions,
                onComplete = { bytes -> if (selected == NovelAiPostProcessTab.ENHANCE) enhanceResult = bytes to selectedOptions else upscaleResult = bytes },
                onStatus = { status = it })
        }
    }
    if (status.isNotBlank()) StatusText(status)
    val result = if (tab == NovelAiPostProcessTab.ENHANCE) enhanceResult?.first else upscaleResult
    result?.let { output ->
        if (tab == NovelAiPostProcessTab.ENHANCE && enhanceResult?.second != options) StatusText("参数已更改；预览仍是上次结果")
        var split by remember(path, output) { mutableStateOf(.5f) }
        Box(Modifier.fillMaxWidth().height(330.dp).pointerInput(path, output) { detectDragGestures { change, _ -> change.consume(); split = (change.position.x / size.width).coerceIn(0f, 1f) } }) {
            DesktopOwnedImage("before-${path}", { requireNotNull(input).first }, Modifier.fillMaxSize())
            DesktopOwnedImage("after-${output.contentHashCode()}", { output }, Modifier.fillMaxSize().drawWithContent {
                clipRect(left = size.width * split) { this@drawWithContent.drawContent() }
            })
            Box(Modifier.fillMaxHeight().fillMaxWidth(split).border(1.dp, DesktopBootstrapColors.primary))
        }
        StatusText("左：原图 · 右：处理结果 · 拖动比较")
        StudioActions {
            StudioAction("用作当前导入图片", enabled = !busy) { onUse(output) }
            StudioAction("保存 PNG") { scope.launch { try {
                controller.picker.pickSaveFile(DesktopFileType("PNG", listOf("png")), "processed.png")?.let { target -> withContext(Dispatchers.IO) { DesktopExternalFileWriter.writeBytes(target, output) } }
            } catch (_: Exception) { status = "保存失败；结果保留" } } }
            StudioAction("复制图片") { scope.launch { try { withContext(Dispatchers.IO) { copyDesktopImage(output, null) } } catch (_: Exception) { status = "复制失败；结果保留" } } }
        }
    }
}
