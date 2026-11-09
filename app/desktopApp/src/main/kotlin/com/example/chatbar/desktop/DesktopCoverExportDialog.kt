package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import com.example.chatbar.domain.card.CharacterCardExportCompatibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopCoverExportDialog(draft: DesktopCoverExportDraft, controller: DesktopTypedTransferController,
    frame: (@Composable (@Composable () -> Unit) -> Unit)? = null) {
    val initialOptions = remember(draft) { CharacterCardPngExportOptions(preserveOriginalCover = draft.originalCoverBytes != null) }
    var working by remember(draft) { mutableStateOf(initialOptions to (null as ByteArray?)) }
    val undo = remember(draft) { mutableStateListOf<Pair<CharacterCardPngExportOptions, ByteArray?>>() }
    val redo = remember(draft) { mutableStateListOf<Pair<CharacterCardPngExportOptions, ByteArray?>>() }
    val options = working.first
    val replacement = working.second
    var editingCopy by remember { mutableStateOf(false) }
    fun commit(next: Pair<CharacterCardPngExportOptions, ByteArray?>) {
        if (next == working) return
        undo += working; redo.clear()
        while (undo.size > 1 && (undo.size > 60 || undo.mapNotNull { it.second }.distinct().sumOf { it.size.toLong() } > 128L * 1024 * 1024)) undo.removeAt(0)
        working = next
    }
    fun change(next: CharacterCardPngExportOptions) = commit(next to replacement)
    var error by remember { mutableStateOf<String?>(null) }
    var compatibility by remember(draft) { mutableStateOf(CharacterCardExportCompatibility.OFFICIAL_V9) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val sourceBytes = replacement ?: draft.originalCoverBytes ?: draft.backgroundBytes
    val originalMode = options.preserveOriginalCover && sourceBytes != null
    val source by produceState<java.awt.image.BufferedImage?>(null, sourceBytes) {
        try { value = sourceBytes?.let { withContext(Dispatchers.Default) { DesktopImageEditing.decode(it) } } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    val preview by produceState<ImageBitmap?>(null, options, sourceBytes) {
        try { value = withContext(Dispatchers.Default) {
            org.jetbrains.skia.Image.makeFromEncoded(DesktopCharacterCardPngRenderer().renderExportImage(
                draft.card, options.copy(sizePx = 1024), draft.originalCoverBytes ?: draft.backgroundBytes, draft.backgroundBytes, replacement)).toComposeImageBitmap()
        } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    if (editingCopy && sourceBytes != null) DesktopImageToolsDialog(sourceBytes, UnconfiguredDesktopFilePicker,
        { editingCopy = false }, onImageApply = { commit(options to it) })
    val content: @Composable () -> Unit = {
        val parentWindow = LocalDesktopFishParentWindow.current
        val picker = remember(parentWindow, controller) { controller.coverExportFilePicker(parentWindow) }
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText(if (originalMode) "原图模式保留图片尺寸与画面，仅更新角色数据。图片里已有的文字不会自动重绘。"
                else "排版模式可调整构图。替换与裁剪只用于本次导出，不修改封面原图或角色背景。")
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (originalMode) Box(Modifier.weight(1f).fillMaxHeight()) {
                    preview?.let { Image(it, contentDescription = "封面原图预览", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                } else DesktopImageViewport(preview, "CCB PNG 封面", source?.width ?: 1, source?.height ?: 1, 1f,
                    DesktopImageTransform(options.cropCenterX, options.cropCenterY, options.cropZoom), {
                        change(options.copy(cropCenterX = it.centerX, cropCenterY = it.centerY, cropZoom = it.zoom.coerceAtMost(6f)))
                    }, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioSwitch("保留原图外观", originalMode, enabled = sourceBytes != null && !state.busy) {
                        change(options.copy(preserveOriginalCover = !originalMode))
                    }
                    StatusText("在这里选择本次导出的封面；不会修改聊天背景、头像或已保存的封面原图。")
                    if (!originalMode) {
                        StatusText("封面显示文字（不修改卡名或提示词）")
                        BasicTextField(options.displayTitle ?: draft.card.name, { change(options.copy(displayTitle = it)) },
                            Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(8.dp),
                            cursorBrush = DesktopBootstrapColors.inputCaret,
                            textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
                        DesktopImageSlider("缩放", (options.cropZoom - 1f) / 5f) { change(options.copy(cropZoom = 1f + it * 5f)) }
                        DesktopImageSlider("渐变高度", (options.gradientHeight - 0.25f) / 0.43f) { change(options.copy(gradientHeight = 0.25f + it * 0.43f)) }
                        DesktopImageSlider("渐变强度", (options.gradientStrength - 0.45f) / 0.45f) { change(options.copy(gradientStrength = 0.45f + it * 0.45f)) }
                        DesktopImageSlider("Logo 大小", (options.logoScale - 0.07f) / 0.07f) { change(options.copy(logoScale = 0.07f + it * 0.07f)) }
                        DesktopImageSlider("标题大小", (options.titleScale - 0.04f) / 0.04f) { change(options.copy(titleScale = 0.04f + it * 0.04f)) }
                    }
                    BootstrapButton("选择 / 替换导出封面", enabled = !state.busy) { scope.launch {
                        error = null
                        try { withContext(Dispatchers.IO) { controller.chooseCoverReplacement(picker) }?.let {
                            commit(options.copy(cropCenterX = 0.5f, cropCenterY = 0.5f, cropZoom = 1f) to it)
                        } } catch (failure: Exception) { error = failure.message }
                    } }
                    if (draft.assets.isNotEmpty()) {
                        SearchableChoice("卡内图片作为封面", listOf<String?>(null) + draft.assets.map { it.id }, draft.assets.firstOrNull { it.bytes === replacement }?.id,
                            { id -> draft.assets.firstOrNull { it.id == id }?.label ?: "选择当前卡图片…" }) { id ->
                            draft.assets.firstOrNull { it.id == id }?.let {
                                commit(options.copy(cropCenterX = .5f, cropCenterY = .5f, cropZoom = 1f) to it.bytes)
                            }
                        }
                    }
                    BootstrapButton("单独保存封面底图…", enabled = sourceBytes != null && !state.busy) {
                        sourceBytes?.let { bytes -> scope.launch { controller.saveCoverBase(draft, bytes, picker) } }
                    }
                    StatusText("底图文件不含本次添加的文字和阴影，下次可直接选它重新排版。Desktop 完整格式还会把所选底图保存在卡内；官方兼容格式不保留独立底图。")
                    if (replacement != null) BootstrapButton(if (draft.originalCoverBytes != null) "恢复封面原图" else "恢复角色背景") {
                        commit(options.copy(cropCenterX = 0.5f, cropCenterY = 0.5f, cropZoom = 1f) to null)
                    }
                    BootstrapButton("打码 / 纯色遮盖", enabled = sourceBytes != null) { editingCopy = true }
                    BootstrapButton("撤销", enabled = undo.isNotEmpty()) { com.example.chatbar.domain.image.undoCanvasState(working, undo, redo)?.let { working = it } }
                    BootstrapButton("重做", enabled = redo.isNotEmpty()) { com.example.chatbar.domain.image.redoCanvasState(working, undo, redo)?.let { working = it } }
                    BootstrapButton("重置全部") { commit(initialOptions to null) }
                }
            }
            if (state.busy) StatusText("正在选择保存位置或导出 PNG…")
            (error ?: state.error)?.let { StatusText(it, DesktopBootstrapColors.destructive) }
            state.status?.let { StatusText(it) }
            DesktopCharacterExportCompatibilityChoice(compatibility, !state.busy) { compatibility = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton("取消", secondary = true, enabled = !state.busy, onClick = controller::cancelCoverExport)
                BootstrapButton("导出 PNG", enabled = !state.busy && preview != null) {
                    scope.launch { controller.finishCoverExport(draft, options, replacement, compatibility, picker) }
                }
            }
        }
    }
    if (!editingCopy) {
        if (frame != null) frame(content)
        else DesktopImageToolWindow("CCB PNG 封面 · ${draft.card.name}", controller::cancelCoverExport,
            width = 900.dp, height = 820.dp) { content() }
    }
}
