package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.NovelAiFullPromptContent
import kotlinx.coroutines.*

@Composable
internal fun DesktopStudioFullPromptExchange(controller: DesktopNovelAiStudioController, businessBusy: Boolean, onClose: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var operation by remember { mutableStateOf<Job?>(null) }
    var working by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    fun run(work: suspend () -> Unit) {
        if (working || closing) return
        working = true; notice = ""
        operation = scope.launch {
            try { work() }
            catch (_: CancellationException) { notice = "操作已停止，请核对下方应用结果。" }
            catch (_: Exception) { notice = "剪贴板操作未完成，请检查剪贴板文本和应用结果。" }
            finally { working = false }
        }
    }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { operation?.cancelAndJoin(); controller.discardFullPrompt(); onClose() }
    }
    DesktopImageToolWindow("完整 Prompt 文本", ::close, width = 680.dp) {
        DesktopStudioFullPromptContent(state.fullPromptPreview, working || closing || businessBusy, notice, state.status,
            onCopy = { run { controller.copyFullPrompt()?.let { desktopCopyText(it); notice = "已复制完整 Prompt 文本" } } },
            onRead = { run {
                val text = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                if (text == null) notice = "剪贴板没有可读取的文本" else controller.inspectFullPrompt(text)
            } }, onApply = { run { controller.applyFullPrompt() } },
            onCancel = { run { controller.discardFullPrompt() } }, onClose = ::close)
    }
}

@Composable
internal fun ColumnScope.DesktopStudioFullPromptContent(preview: NovelAiFullPromptContent?, busy: Boolean,
    notice: String, status: String, onCopy: () -> Unit, onRead: () -> Unit, onApply: () -> Unit,
    onCancel: () -> Unit, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(notice, status) { scroll.scrollTo(0) }
    ManageHeading("完整 Prompt 文本")
    StudioActions {
        StudioAction("复制完整文本", enabled = !busy, onClick = onCopy)
        StudioAction("读取剪贴板并预览", enabled = !busy, onClick = onRead)
        StudioAction("关闭", onClick = onClose)
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (notice.isNotBlank()) StatusText(notice)
        if (status.isNotBlank()) StatusText(status)
        StatusText("包含画风、全部正负词、角色位置／开关、位置应用开关、模型和自然语言模式。其他生成参数、连续生成及参考资源保留当前值；完整资源交换请使用生成预设文件。")
        StatusText("使用独立版本标识，保留原正向复制入口；Android／Studio Suite 尚未验证能直接粘贴此格式。")
        if (preview == null) StatusText("读取后先预览，确认才替换；可使用原撤销操作恢复，不会自动开始生成。")
        else {
            StatusText("${preview.selectedModel.displayName} · ${if (preview.naturalLanguageMode) "自然语言" else "标签"}模式 · 启用角色 ${preview.characters.count { it.enabled }}/${preview.characters.size}")
            StatusText("应用角色位置：${if (preview.useCharacterPositions) "开启" else "关闭"}")
            DesignTextDisclosure("画风", preview.stylePrompt)
            DesignTextDisclosure("基础正向", preview.basePrompt)
            DesignTextDisclosure("补充正向", preview.extraPrompt)
            DesignTextDisclosure("全局负向", preview.negativePrompt)
            preview.characters.forEachIndexed { index, role ->
                StatusText("角色 ${index + 1} · ${if (role.enabled) "启用" else "关闭"} · ${role.center?.let { "位置 ${it.x}, ${it.y}" } ?: "自动位置"}")
                DesignTextDisclosure("角色 ${index + 1} 正向", role.prompt)
                DesignTextDisclosure("角色 ${index + 1} 负向", role.negativePrompt)
            }
        }
    }
    if (preview != null) StudioActions {
        StudioAction("确认替换完整 Prompt", enabled = !busy, style = StudioActionStyle.PRIMARY, onClick = onApply)
        StudioAction("取消文本导入", enabled = !busy, onClick = onCancel)
    }
}
