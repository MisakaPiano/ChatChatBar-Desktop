package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun desktopDiagnosticScenario(entry: DesktopTransportDiagnosticEntry): String = when {
    entry.image != null -> "生图网络请求"
    entry.media != null -> "语音网络请求"
    else -> when {
        entry.promptStages.lastOrNull()?.kind?.startsWith("MEMORY_") == true -> "记忆"
        entry.promptStages.lastOrNull()?.kind == "RETRIEVAL_PLAN" -> "检索"
        entry.promptStages.lastOrNull()?.kind?.startsWith("VOICE_") == true -> "语音处理"
        entry.promptStages.lastOrNull()?.kind == "IMAGE_DESCRIPTION" -> "图像识别"
        entry.promptStages.lastOrNull()?.kind?.startsWith("IMAGE_") == true -> "生图设计"
        else -> when (entry.scenario) {
            "CHAT" -> "聊天"; "CHARACTER_CARD" -> "角色卡"; "FORMAT_CARD" -> "格式卡"
            "WORLD_BOOK" -> "世界书"; "MOMENTS" -> "朋友圈"; "VOICE_PREVIEW" -> "试听"
            else -> "其他／未记录"
        }
    }
}

internal fun desktopFilteredDiagnostics(entries: List<DesktopTransportDiagnosticEntry>, scenario: String,
    version: String, result: String) = entries.filter {
        (scenario == "全部场景" || desktopDiagnosticScenario(it) == scenario) &&
            (version == "全部版本" || (it.templateVersion ?: "未记录版本") == version ||
                it.promptStages.any { stage -> stage.templateVersion == version }) &&
            (result == "全部结果" || it.status.name == result)
    }.sortedByDescending { it.startedAt }

/** Projects the existing scrubbed physical-request owner; opening never sends a request. */
@Composable internal fun DesktopGlobalDiagnostics(owner: DesktopTransportDiagnosticsOwner,
    inspector: DesktopPromptInspectorController) {
    var showInspector by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp)
        .onKeyEvent { if (showInspector && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            showInspector = false; true
        } else false }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showInspector) {
            StudioAction("返回 AI 请求日志") { showInspector = false }
            Box(Modifier.weight(1f).fillMaxWidth()) { DesktopPromptInspectorPanel(inspector, onClose = { showInspector = false }) }
        } else {
            ManageHeading("AI 请求日志")
            StudioAction("高级：逻辑 Prompt 检查器") { showInspector = true }
            StatusText("本次运行的真实传输记录；已脱敏且有界。传输完成不等于业务校验通过。")
            DesktopGlobalRequestLogs(owner, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable internal fun DesktopGlobalRequestLogs(owner: DesktopTransportDiagnosticsOwner, modifier: Modifier = Modifier) {
    val entries by owner.entries.collectAsState()
    var scenario by remember { mutableStateOf("全部场景") }
    var version by remember { mutableStateOf("全部版本") }
    var result by remember { mutableStateOf("全部结果") }
    var clearIds by remember { mutableStateOf<Set<String>?>(null) }
    val clipboardDelegate = LocalClipboard.current
    val clipboard = remember(clipboardDelegate) { DesktopSafeClipboard(clipboardDelegate) }
    val scope = rememberCoroutineScope()
    val logs = desktopFilteredDiagnostics(entries, scenario, version, result)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioActions {
            CompactChoice("场景", listOf("全部场景") + entries.map(::desktopDiagnosticScenario).distinct(), scenario, { it }) { scenario = it }
            CompactChoice("版本", listOf("全部版本") + entries.flatMap { entry ->
                (listOf(entry.templateVersion ?: "未记录版本") + entry.promptStages.map { it.templateVersion ?: "未记录版本" }).distinct()
            }.distinct(), version, { it }) { version = it }
            CompactChoice("结果", listOf("全部结果") + entries.map { it.status.name }.distinct(), result, { it }) { result = it }
        }
        StatusText("筛选结果：${logs.size} 次请求 · 已记录任务 ${logs.mapNotNull { it.logicalTaskId }.distinct().size} 个 · 任务归属未记录 ${logs.count { it.logicalTaskId == null }} 次")
        StudioActions {
            StudioAction("复制筛选结果", enabled = logs.isNotEmpty()) { scope.launch {
                clipboard.copyText(logs.joinToString("\n\n") { "${desktopDiagnosticScenario(it)} · ${it.modelDisplayName} · ${it.status}\n${it.serializedRequestBody.orEmpty()}\n${it.outputText}" })
            } }
            StudioAction("清空已完成日志", enabled = logs.any { it.status != DesktopTaskStatus.RUNNING }) {
                clearIds = logs.filter { it.status != DesktopTaskStatus.RUNNING }.map { it.taskId }.toSet()
            }
        }
        clearIds?.let { ids -> StudioActions {
            StatusText("仅清空本次选中的已结束记录，运行中请求保留。")
            StudioAction("确认清空") { owner.clearCompleted(ids); clearIds = null }
            StudioAction("取消") { clearIds = null }
        } }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            logs.forEach { entry -> key(entry.taskId) {
                val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(entry.startedAt))
                StudioDisclosure("${desktopDiagnosticScenario(entry)} · ${entry.status}", "${entry.modelDisplayName} · $time") {
                    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        StatusText("模型：${entry.modelName} · 模板版本：${entry.templateVersion ?: "未记录"}")
                        entry.promptStages.forEach { stage -> StatusText("阶段：${stage.kind} / ${stage.stage} · 模板源码版本：${stage.templateVersion ?: "未记录"}") }
                        val usage = entry.usage
                        StatusText("服务端 Token：输入 ${usage?.promptTokens ?: "未知"} / 输出 ${usage?.completionTokens ?: "未知"}")
                        StatusText("缓存：命中 ${usage?.cachedTokens ?: "未知"} / 写入 ${usage?.cacheWriteTokens ?: "未知"} / 未命中 ${usage?.cacheMissTokens ?: "未知"}")
                        StatusText("文本占用估算：${entry.requestTextBudget?.estimatedTextTokens ?: "未记录"}；不是账单或完整多模态占用")
                        if (entry.requestTruncated || entry.outputTruncated || entry.reasoningTruncated || entry.droppedChunkCount > 0)
                            StatusText("记录被截断：请求 ${entry.requestTruncated} / 输出 ${entry.outputTruncated} / 思考 ${entry.reasoningTruncated} / 已丢弃片段 ${entry.droppedChunkCount}")
                        entry.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                        entry.media?.let { StatusText("语音：尝试 ${it.attempt} · HTTP ${it.httpStatus ?: "未知"} · 字符 ${it.sentTextCodePoints} · 时长 ${it.audioDurationMs ?: "未知"} ms") }
                        entry.image?.let { StatusText("${it.operation}：尝试 ${it.attempt} · HTTP ${it.httpStatus ?: "未知"} · 生成 ${it.outputImages ?: "未知"} 张") }
                        StatusText("Request JSON")
                        StatusText(entry.serializedRequestBody ?: "未保留请求正文（媒体仅记录元数据）")
                        StatusText("输出")
                        StatusText(entry.outputText.ifEmpty { "未记录文本输出" })
                        if (entry.reasoningText.isNotEmpty()) { StatusText("思考输出"); StatusText(entry.reasoningText) }
                    } }
                }
            } }
        }
    }
}
