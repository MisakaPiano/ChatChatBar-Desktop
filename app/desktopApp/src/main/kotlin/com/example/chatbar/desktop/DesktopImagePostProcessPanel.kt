package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*

@Composable
internal fun DesktopImagePostProcessPanel(path: Path, controller: DesktopNovelAiStudioController,
    inputDispatcher: CoroutineDispatcher = Dispatchers.IO, onUse: (ByteArray) -> Unit) {
    key(path) { DesktopImagePostProcessSourcePanel(path, controller, inputDispatcher, onUse) }
}

@Composable
private fun DesktopImagePostProcessSourcePanel(path: Path, controller: DesktopNovelAiStudioController,
    inputDispatcher: CoroutineDispatcher, onUse: (ByteArray) -> Unit) {
    val state by controller.state.collectAsState()
    val tasks by controller.taskEntries.collectAsState()
    val busy = tasks.any { it.kind == DesktopTaskKind.NOVELAI && it.status == DesktopTaskStatus.RUNNING }
    val records by controller.postProcessResults.records.collectAsState()
    var tab by remember(path) { mutableStateOf(NovelAiPostProcessTab.UPSCALE) }
    var options by remember(path) { mutableStateOf(NovelAiEnhanceOptions()) }
    var selectedRecordId by remember(path) { mutableStateOf<String?>(null) }
    var status by remember(path) { mutableStateOf("") }
    var sourceError by remember(path) { mutableStateOf("") }
    var source by remember(path) { mutableStateOf<NovelAiEnhanceSource?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller, path) {
        try { controller.reloadPostProcessResults() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "后处理记录读取失败；原记录保留，请重开后重试" }
    }
    val input by produceState<Pair<ByteArray, Pair<Int, Int>>?>(null, path) {
        value = withContext(inputDispatcher) { try {
            if (Files.size(path) > DesktopImageEditing.MAX_BYTES) {
                sourceError = "图片超过后处理输入大小上限；原图和已保存结果保留，不能继续增强或放大"
                return@withContext null
            }
            val bytes = Files.readAllBytes(path); DesktopImageEditing.requireStatic(bytes)
            val raster = DesktopImageEditing.decode(bytes)
            source = runCatching { DesktopImageMetadata.readEnhance(path.toString()) }.getOrNull()
            bytes to (raster.width to raster.height)
        } catch (_: Exception) { sourceError = "仅支持可读取的静态图片；动画保持原样，请先还原规范伪装再处理"; null } }
    }
    val sourceHash = remember(input?.first) { input?.first?.let(DesktopPostProcessResults::digest) }
    val ownBusy = sourceHash != null && sourceHash == state.postProcessSourceSha256 &&
        tasks.any { it.taskId == state.postProcessTaskId && it.status == DesktopTaskStatus.RUNNING }
    val ownTaskId = state.postProcessTaskId.takeIf { ownBusy }
    StudioChips("图片处理", NovelAiPostProcessTab.entries, tab, { it.label }) { if (!busy) tab = it }
    val dimensions = input?.second
    val recipe = source
    val cost = dimensions?.let { (w, h) -> if (tab == NovelAiPostProcessTab.UPSCALE)
        NovelAiPostProcessPolicy.upscaleCost(w, h)?.let { NovelAiGenerationCost(NovelAiGenerationChargeKind.ANLAS, it) }
        else recipe?.let { runCatching { NovelAiPostProcessPolicy.enhanceCost(it, w, h, options, state.account) }.getOrNull() } }
    val enhanceIssue = if (tab == NovelAiPostProcessTab.ENHANCE)
        recipe?.settings?.requestValidationError(recipe.prompt.characterCaptions.size) else null
    val expectedSize = dimensions?.let { (w, h) -> runCatching {
        if (tab == NovelAiPostProcessTab.UPSCALE) IntSize(w * 2, h * 2)
        else NovelAiPostProcessPolicy.outputSize(w, h, options.scale).let { IntSize(it.width, it.height) }
    }.getOrNull() }
    dimensions?.let { StatusText("原图 ${it.first} × ${it.second}" + (expectedSize?.let { size -> " · 预计输出 ${size.width} × ${size.height}" } ?: "")) }
    if (tab == NovelAiPostProcessTab.ENHANCE) {
        if (recipe == null) StatusText("缺少受支持的完整生成元数据；Enhance 不可用，可选择 Upscale", DesktopBootstrapColors.warning)
        else if (dimensions != null) {
            StatusText("${recipe.settings.model.displayName} · Steps ${recipe.settings.effectiveSteps} · Guidance ${recipe.settings.guidance}")
            StatusText("${recipe.settings.effectiveSampler.displayName} · CFG Rescale ${recipe.settings.effectiveCfgRescale} · 使用图片元数据")
            val scales = NovelAiPostProcessPolicy.scales(dimensions.first, dimensions.second, recipe.settings.model)
            StudioChips("倍率", scales, options.scale, { it.label }) { if (!busy) options = options.copy(scale = it) }
            StudioChips("增强幅度", (1..5).toList(), options.magnitude, { it.toString() }) { if (!busy) options = options.withMagnitude(it) }
            CompositionLocalProvider(LocalDesktopStudioFieldScope provides "postprocess:$path") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusText("Strength 0–1")
                    StudioNumericInput("Strength 0–1", options.strength.toString(), Modifier.fillMaxWidth()) { text -> if (!busy) text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { options = options.copy(strength = it) } }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusText("Noise 0–1")
                    StudioNumericInput("Noise 0–1", options.noise.toString(), Modifier.fillMaxWidth()) { text -> if (!busy) text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let { options = options.copy(noise = it) } }
                }
            }
        }
    } else StatusText("2× Upscale · 不改变 Studio 模型 · 超过 3,145,728 输入像素时不可用")
    if (sourceError.isNotEmpty()) StatusText(sourceError, DesktopBootstrapColors.warning)
    enhanceIssue?.let { StatusText("Enhance 不可用：$it；可切换 Upscale", DesktopBootstrapColors.warning) }
    StatusText(cost?.let { when (it.kind) {
        NovelAiGenerationChargeKind.FREE -> "预计免费"
        NovelAiGenerationChargeKind.V5_ALLOWANCE -> "预计使用 V5 配额"
        NovelAiGenerationChargeKind.ANLAS -> "预计 ${it.anlas} Anlas"
    } } ?: "费用 / 输入资格不可用")
    StatusText(state.account?.let { "账户余额 ${it.anlas} Anlas" } ?: "账户信息暂不可用，费用以服务端结算为准")
    val validScale = tab == NovelAiPostProcessTab.UPSCALE || recipe != null && dimensions != null && options.scale in NovelAiPostProcessPolicy.scales(dimensions.first, dimensions.second, recipe.settings.model)
    StudioAction(if (ownBusy) "停止" else if (busy) "其他图像任务运行中" else tab.label,
        enabled = ownBusy || (!busy && state.postProcessError == null && state.credentialConfigured && input != null && cost != null && cost.anlas <= 140 && validScale && enhanceIssue == null)) {
        if (ownBusy) ownTaskId?.let(controller::stopPostProcess) else scope.launch {
            val selected = tab; val selectedOptions = options
            controller.postProcess(requireNotNull(input).first, selected, recipe, selectedOptions,
                onComplete = { selectedRecordId = null },
                onStatus = { status = it })
        }
    }
    if (status.isNotBlank()) StatusText(status)
    DesktopPostProcessHistoryNotice(controller, state.postProcessError)
    val record = records.firstOrNull { it.id == selectedRecordId }
        ?: records.firstOrNull { it.sourceSha256 == sourceHash && it.operation == tab.name }
    if (records.isNotEmpty()) StudioDisclosure("后处理记录（${records.size}）") {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            items(records, key = { it.id }) { entry ->
                StudioAction("${if (entry.operation == NovelAiPostProcessTab.UPSCALE.name) "Upscale" else "Enhance"} · ${java.time.Instant.ofEpochMilli(entry.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}",
                    selected = entry.id == record?.id) { selectedRecordId = entry.id }
            }
        }
    }
    val result by produceState<Pair<DesktopPostProcessResultRecord, ByteArray>?>(null, record) {
        value = null
        record?.let { selected -> try { value = selected to controller.postProcessResults.read(selected) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "此结果图片不可读或备份未包含图片；记录和来源保留" } }
    }
    var resultDimensions by remember(record) { mutableStateOf<IntSize?>(null) }
    val resultZoom = remember(record) { DesktopImageZoomState() }
    result?.takeIf { it.first == record }?.second?.let { output ->
        if (record?.sourceSha256 != sourceHash) StatusText("所选为其他来源的已保存结果；不会覆盖当前来源")
        if (record != null && !record.matches(tab, options)) StatusText("参数已更改；预览仍是上次结果")
        val original = input?.takeIf { record?.sourceSha256 == sourceHash }
        if (original != null) DesktopPostProcessComparisonWorkspace("${path}-${record}", original.first, output,
            IntSize(original.second.first, original.second.second)) { resultDimensions = it }
        else DesktopImageZoomSurface("after-${record}", { output }, Modifier.fillMaxWidth().height(330.dp), postProcessOutput = true, zoomState = resultZoom)
        (if (original != null) resultDimensions else resultZoom.sourceSize.takeUnless { it == IntSize.Zero })?.let { StatusText("已保存结果 ${it.width} × ${it.height}") }
        StatusText(if (original != null) "左：原图 · 右：处理结果" else "已保存的处理结果")
        StudioActions {
            StudioAction("用作当前导入图片", enabled = !busy) { onUse(output) }
            StudioAction("保存 PNG") { scope.launch { try {
                controller.picker.pickSaveFile(DesktopFileType("PNG", listOf("png")), "processed.png")?.let { target -> withContext(Dispatchers.IO) { DesktopExternalFileWriter.writeBytes(target, output) } }
            } catch (_: Exception) { status = "保存失败；结果保留" } } }
            StudioAction("复制图片") { scope.launch { try { withContext(Dispatchers.IO) { copyDesktopImage(output, null, postProcessOutput = true) } } catch (_: Exception) { status = "复制失败；结果保留" } } }
        }
    }
}

@Composable
internal fun DesktopPostProcessHistoryNotice(controller: DesktopNovelAiStudioController, error: String?) {
    if (error == null) return
    val scope = rememberCoroutineScope()
    StatusText(error, DesktopBootstrapColors.warning)
    StudioAction("重试后处理记录") { scope.launch { controller.reloadPostProcessResults() } }
}
