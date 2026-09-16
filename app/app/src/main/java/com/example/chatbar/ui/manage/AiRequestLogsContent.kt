package com.example.chatbar.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.chat.DebugLogCard
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.utils.DebugLogEntry
import com.example.chatbar.utils.DebugLogManager

@Composable
internal fun AiRequestLogsContent() {
    val logs by DebugLogManager.logs.collectAsState()
    var scene by remember { mutableStateOf("全部场景") }
    var fingerprint by remember { mutableStateOf("全部版本") }
    var result by remember { mutableStateOf("全部结果") }
    val clipboard = LocalClipboardManager.current
    val filtered = logs.filter {
        (scene == "全部场景" || it.sceneLabel() == scene) &&
            (fingerprint == "全部版本" || it.templateFingerprint == fingerprint) &&
            (result == "全部结果" || it.resultFilterLabel() == result)
    }.sortedByDescending { it.timestamp }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CbText("当前进程最多保留 100 次请求，达到内存上限会提前移除旧记录；较长日志会标注截断。请求完成仅表示传输完成，候选仍须通过业务校验。", style = ChatBarTheme.typography.caption)
        CbSelect(scene, listOf("全部场景") + logs.map { it.sceneLabel() }.distinct().sorted(), { it }, { scene = it }, placeholder = "场景")
        CbSelect(fingerprint, listOf("全部版本") + logs.mapNotNull { it.templateFingerprint }.distinct(), { it }, { fingerprint = it }, placeholder = "模板指纹")
        CbSelect(result, listOf("全部结果") + logs.map { it.resultFilterLabel() }.distinct(), { it }, { result = it }, placeholder = "结果")
        CbText("筛选结果：${filtered.size} 次请求 · ${filtered.map { it.taskId ?: it.id }.distinct().size} 个任务")
        CbButton("复制筛选结果", {
            clipboard.setText(AnnotatedString(filtered.joinToString("\n\n") { it.exportText() }))
        }, enabled = filtered.isNotEmpty(), variant = ButtonVariant.Outline)
        CbButton("清空已完成日志", DebugLogManager::clearCompletedLogs, variant = ButtonVariant.Ghost)
        if (filtered.isEmpty()) CbText("暂无匹配的请求。执行任一 AI 功能后，可在这里查看。")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.id }) { entry ->
                val taskRequests = logs.filter { (it.taskId ?: it.id) == (entry.taskId ?: entry.id) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CbText(taskRequests.taskUsageSummary(), style = ChatBarTheme.typography.caption, color = ChatBarTheme.colors.mutedForeground)
                    DebugLogCard(entry)
                }
            }
        }
    }
}

private fun DebugLogEntry.sceneLabel(): String = taskName ?: "其他请求"
private fun DebugLogEntry.resultFilterLabel(): String = failureKind?.name ?: resultLabel

internal fun List<DebugLogEntry>.taskUsageSummary(): String {
    val knownInput = mapNotNull { it.apiPromptTokens }
    val knownOutput = mapNotNull { it.apiCompletionTokens }
    fun usage(values: List<Int>): String = if (values.size == size) values.sum().toString()
        else "已知 ${values.sum()}，${size - values.size} 次未知"
    return "本任务保留记录：${size} 次调用（含失败）· 输入 ${usage(knownInput)} / 输出 ${usage(knownOutput)} Token"
}

private fun DebugLogEntry.exportText(): String = buildString {
    appendLine("${sceneLabel()} · ${taskStage.orEmpty()} · ${resultFilterLabel()}")
    appendLine("任务：${taskId ?: id} / 请求：$id")
    appendLine("模型：$modelName · 模板：${templateFingerprint ?: "不适用"}")
    appendLine("模板符号：${templateSymbols.joinToString()}")
    appendLine("耗时：${elapsedMillis}ms · 结束原因：${finishReason ?: "未知"}")
    appendLine("服务端 Token：输入 ${apiPromptTokens ?: "未知"} / 输出 ${apiCompletionTokens ?: "未知"} / 缓存 ${cachedPromptTokens ?: "未知"}")
    appendLine("估算 Token：输入 $estimatedPromptTokens / 输出 $estimatedCompletionTokens / 确认新增 $confirmationEstimatedTokens")
    error?.let { appendLine("错误：$it") }
    if (logTruncated) appendLine("日志已截断")
    appendLine("Request JSON\n$requestBodyJson")
    appendLine("原始输出\n$rawAiOutputText")
}
