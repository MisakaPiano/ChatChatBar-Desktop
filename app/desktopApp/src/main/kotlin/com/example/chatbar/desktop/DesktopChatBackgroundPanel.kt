package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
internal fun DesktopChatBackgroundPanel(controller: DesktopPrimaryChatController, onClose: () -> Unit) {
    val state by controller.state.collectAsState()
    val library = controller.backgroundLibrary
    val revision = library?.revision?.collectAsState()?.value
    val card = state.selectedCharacter
    var entries by remember(card?.id) { mutableStateOf(DesktopCharacterBackgroundLibrary()) }
    var warning by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<String?>(null) }
    var preview by remember(card?.id) { mutableStateOf<Pair<List<String>, Int>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(card?.id, revision) {
        warning = ""
        try { entries = card?.id?.let { library?.load(it) } ?: DesktopCharacterBackgroundLibrary() }
        catch (_: Exception) { entries = DesktopCharacterBackgroundLibrary(); warning = "背景库不可读取；使用官方背景，保留所有资源" }
    }
    fun action(work: suspend () -> Unit) { scope.launch {
        busy = true
        try { work() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { warning = "操作未完成；资源保留，请检查文件或背景库" }
        finally { busy = false }
    } }
    preview?.let { (paths, index) ->
        DesktopImageViewer(paths, index, controller.characterResources, controller.imagePicker) { preview = null }
    }
    androidx.compose.ui.window.DialogWindow(onCloseRequest = onClose, title = "聊天背景") {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val preferred by produceState<String?>(null, card?.id, revision) {
                value = card?.id?.let { runCatching { library?.preferred(it) }.onFailure {
                    warning = "Desktop 首选背景不可读取；使用官方背景，保留资源供修复"
                }.getOrNull() }
            }
            StatusText("当前来源：${desktopEffectiveBackground(state.selectedSession?.chatBackground, preferred, card?.chatBackground).second}")
            StudioActions {
                BootstrapButton("选择 / 替换会话背景", enabled = !busy) { action { controller.chooseSessionBackground() } }
                BootstrapButton("清除会话覆盖", enabled = !busy) { action { controller.chooseSessionBackground(clear = true) } }
            }
            DesktopImageSlider("背景透明度（全局）", state.backgroundOpacity) { value -> scope.launch { controller.setBackgroundOpacity(value) } }
            StatusText("Desktop 角色背景库 · ${card?.name ?: "未关联角色"}")
            StatusText("首选仅在此 Desktop 生效；角色卡跨平台背景和导出内容保持不变。")
            StudioActions {
                BootstrapButton("添加背景", enabled = !busy && card != null && library != null) { action {
                    controller.imagePicker.pickOpenFile(DesktopFileType("背景", listOf("png", "jpg", "jpeg", "webp")))?.let { path ->
                        val bytes = withContext(Dispatchers.IO) { require(java.nio.file.Files.size(path) <= DesktopImageEditing.MAX_BYTES); java.nio.file.Files.readAllBytes(path) }
                        library!!.import(card!!.id, bytes)
                    }
                } }
                BootstrapButton("清除 Desktop 首选", enabled = !busy && card != null && library != null) { action { library!!.prefer(card!!.id, null) } }
            }
            entries.images.forEach { path ->
                DesktopOwnedImage(path, controller.characterResources::readBytes, Modifier.fillMaxWidth().height(150.dp)
                    .desktopViewerEntry(onClick = { preview = entries.images.toList() to entries.images.indexOf(path) }))
                StudioActions {
                    BootstrapButton(if (path == entries.preferred) "✓ Desktop 首选" else "设为首选", enabled = !busy) { action { library!!.prefer(card!!.id, path) } }
                    BootstrapButton("删除", enabled = !busy) { delete = path }
                    if (delete == path) {
                        BootstrapButton("确认删除", enabled = !busy) { action { library!!.remove(card!!.id, path); delete = null } }
                        BootstrapButton("取消") { delete = null }
                    }
                }
            }
            if (warning.isNotEmpty()) StatusText(warning)
            BootstrapButton("完成", onClick = onClose)
        }
    }
}

@Composable
internal fun DesktopCharacterSummaryCard(presentation: DesktopCharacterManagementPresentation?, onStart: () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val bitmap = remember(presentation) { presentation?.avatar?.toComposeImageBitmap() }
        if (bitmap != null) Image(bitmap, null, Modifier.size(48.dp)) else StatusText(presentation?.fallbackInitial ?: "…")
        Column(Modifier.weight(1f)) {
            StatusText(presentation?.name ?: "加载角色…")
            presentation?.let { StatusText("${it.characterCount} 个角色 · ${it.documentCount} 个文档") }
        }
        BootstrapButton("开始聊天", enabled = presentation != null, onClick = onStart)
    }
}
