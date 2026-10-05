package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopCoverExportDialog(draft: DesktopCoverExportDraft, controller: DesktopTypedTransferController) {
    var options by remember(draft) { mutableStateOf(CharacterCardPngExportOptions()) }
    var replacement by remember(draft) { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val sourceBytes = replacement ?: draft.backgroundBytes
    val source by produceState<java.awt.image.BufferedImage?>(null, sourceBytes) {
        try { value = sourceBytes?.let { withContext(Dispatchers.Default) { DesktopImageEditing.decode(it) } } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    val preview by produceState<ImageBitmap?>(null, options, sourceBytes) {
        try { value = withContext(Dispatchers.Default) {
            org.jetbrains.skia.Image.makeFromEncoded(DesktopCharacterCardPngRenderer().render(
                draft.card, options.copy(sizePx = 1024), sourceBytes)).toComposeImageBitmap()
        } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    DialogWindow(onCloseRequest = controller::cancelCoverExport, title = "CCB PNG 封面 · ${draft.card.name}",
        state = rememberDialogState(width = 900.dp, height = 820.dp)) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText("拖动 / 滚轮调整构图。替换图仅用于本次导出，不修改角色背景或 Package 图片。")
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DesktopImageViewport(preview, "CCB PNG 封面", source?.width ?: 1, source?.height ?: 1, 1f,
                    DesktopImageTransform(options.cropCenterX, options.cropCenterY, options.cropZoom), {
                        options = options.copy(cropCenterX = it.centerX, cropCenterY = it.centerY, cropZoom = it.zoom.coerceAtMost(6f))
                    }, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DesktopImageSlider("缩放", (options.cropZoom - 1f) / 5f) { options = options.copy(cropZoom = 1f + it * 5f) }
                    DesktopImageSlider("水平中心", options.cropCenterX) { options = options.copy(cropCenterX = it) }
                    DesktopImageSlider("垂直中心", options.cropCenterY) { options = options.copy(cropCenterY = it) }
                    DesktopImageSlider("渐变高度", (options.gradientHeight - 0.25f) / 0.43f) { options = options.copy(gradientHeight = 0.25f + it * 0.43f) }
                    DesktopImageSlider("渐变强度", (options.gradientStrength - 0.45f) / 0.45f) { options = options.copy(gradientStrength = 0.45f + it * 0.45f) }
                    DesktopImageSlider("Logo 大小", (options.logoScale - 0.07f) / 0.07f) { options = options.copy(logoScale = 0.07f + it * 0.07f) }
                    DesktopImageSlider("标题大小", (options.titleScale - 0.04f) / 0.04f) { options = options.copy(titleScale = 0.04f + it * 0.04f) }
                    BootstrapButton("仅替换导出封面") { scope.launch {
                        try { withContext(Dispatchers.IO) { controller.chooseCoverReplacement() }?.let {
                            replacement = it; options = options.copy(cropCenterX = 0.5f, cropCenterY = 0.5f, cropZoom = 1f)
                        } } catch (failure: Exception) { error = failure.message }
                    } }
                    BootstrapButton("恢复角色背景") { replacement = null }
                    BootstrapButton("重置调整") { options = CharacterCardPngExportOptions() }
                }
            }
            (error ?: state.error)?.let { StatusText(it, DesktopBootstrapColors.destructive) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton("取消", secondary = true, enabled = !state.busy, onClick = controller::cancelCoverExport)
                BootstrapButton("导出 PNG", enabled = !state.busy && preview != null) {
                    scope.launch { controller.finishCoverExport(draft, options, replacement) }
                }
            }
        }
    }
}
