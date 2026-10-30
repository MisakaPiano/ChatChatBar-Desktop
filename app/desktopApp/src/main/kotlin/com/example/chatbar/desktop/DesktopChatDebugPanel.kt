@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun desktopChatDiagnosticEntries(entries: List<DesktopTransportDiagnosticEntry>, sessionId: String) =
    entries.filter { it.sessionId == sessionId }.sortedByDescending { it.startedAt }

internal fun desktopDiagnosticSystemMessages(entry: DesktopTransportDiagnosticEntry): String? = runCatching {
    require(!entry.requestTruncated)
    Json.parseToJsonElement(requireNotNull(entry.serializedRequestBody)).jsonObject["messages"]?.jsonArray
        ?.filter { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "system" }
        ?.joinToString("\n\n") { message ->
            val content = message.jsonObject["content"]
            (content as? JsonPrimitive)?.content ?: content.toString()
        }
}.getOrNull()

/** Uses recorded requests only. Opening, selecting and clearing logs never builds or sends a request. */
@Composable
internal fun DesktopChatDebugOverlay(owner: DesktopTransportDiagnosticsOwner, sessionId: String,
    onClose: () -> Unit, onMemory: (() -> Unit)? = null, maintenance: DesktopChatMaintenanceController? = null) {
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            DesktopChatDebugPanel(owner, sessionId, onClose, onMemory, Modifier.widthIn(max = 1100.dp)
                .heightIn(max = 820.dp).fillMaxSize(), maintenance)
        }
    }
}

@Composable
internal fun DesktopChatDebugPanel(owner: DesktopTransportDiagnosticsOwner, sessionId: String,
    onClose: () -> Unit, onMemory: (() -> Unit)? = null, modifier: Modifier = Modifier,
    maintenance: DesktopChatMaintenanceController? = null) {
    val entries by owner.entries.collectAsState()
    val logs = desktopChatDiagnosticEntries(entries, sessionId)
    var selectedId by remember(sessionId) { mutableStateOf<String?>(null) }
    var clearConfirmation by remember(sessionId) { mutableStateOf(false) }
    val selected = logs.firstOrNull { it.taskId == selectedId } ?: logs.firstOrNull()
    val clipboardDelegate = LocalClipboard.current
    val clipboard = remember(clipboardDelegate) { DesktopSafeClipboard(clipboardDelegate) }
    val scope = rememberCoroutineScope()
    Column(modifier.background(DesktopBootstrapColors.card).padding(16.dp).onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true } else false
    }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ManageHeading("聊天调试日志") }
            DesktopChatIconAction("关闭调试日志", DesktopAppIcons.Close, onClick = onClose)
        }
        StatusText("仅显示本会话、本次运行记录；请求和响应已脱敏，超出记录上限会截断。")
        ActionRow {
            BootstrapButton("清空本会话日志", enabled = logs.isNotEmpty(), secondary = true) { clearConfirmation = true }
            onMemory?.let { BootstrapButton("长期记忆", secondary = true, onClick = it) }
        }
        maintenance?.let { DesktopChatMaintenancePanel(it, sessionId) }
        if (clearConfirmation) {
            StatusText("清空调试记录？聊天消息和正在进行的生成保持。")
            ActionRow {
                BootstrapButton("确认清空日志") { owner.clear(sessionId); selectedId = null; clearConfirmation = false }
                BootstrapButton("取消清空", secondary = true) { clearConfirmation = false }
            }
        }
        if (clipboard.unavailable) StatusText("剪贴板暂不可用，请重试复制。", DesktopBootstrapColors.warning)
        if (logs.isEmpty()) StatusText("本会话暂无调试日志。新的对话请求会在这里出现。")
        else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            @Composable fun Requests(modifier: Modifier) {
                Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    logs.forEach { entry ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(entry.startedAt))
                        BootstrapButton("$time · ${entry.modelDisplayName} · ${entry.status}",
                            secondary = entry.taskId != selected?.taskId) { selectedId = entry.taskId }
                    }
                }
            }
            @Composable fun Detail(modifier: Modifier) {
                selected?.let { entry -> key(entry.taskId) {
                    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusText("模型：${entry.modelDisplayName} · ${entry.modelName}")
                        SelectionContainer { StatusText("请求地址：${entry.requestUrl ?: "尚未发送"}") }
                        StatusText("状态：${entry.status} · 结束原因：${entry.finishReason ?: "尚未报告"}")
                        entry.completedAt?.let { StatusText("耗时：${(it - entry.startedAt).coerceAtLeast(0)} ms") }
                        StatusText("服务端 Token：输入 ${entry.usage?.promptTokens ?: "未报告"} / 输出 ${entry.usage?.completionTokens ?: "未报告"}")
                        entry.usage?.cachedTokens?.let { StatusText("命中缓存 Token：$it") }
                        StatusText("本轮记忆组装：Archive ${if (entry.archiveIncluded) "包含" else "未包含"} · HEAD ${if (entry.headIncluded) "包含" else "未包含"}")
                        entry.planningNotice?.let { StatusText(it, DesktopBootstrapColors.warning) }
                        entry.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                        if (entry.cancellationObserved) StatusText("已观察到取消请求。")
                        if (entry.requestTruncated || entry.outputTruncated || entry.reasoningTruncated)
                            StatusText("部分日志已达到记录上限；实际请求和生成内容不受影响。", DesktopBootstrapColors.warning)
                        fun copy(body: String) { scope.launch { clipboard.copyText(body) } }
                        DebugSection("最终请求 JSON", entry.serializedRequestBody ?: "尚未记录请求正文", ::copy)
                        DebugSection("System 消息", desktopDiagnosticSystemMessages(entry)
                            ?: "请求尚未记录或日志已截断，请查看最终请求 JSON。", ::copy)
                        DebugSection("RAG 召回内容", entry.ragText.ifBlank { "本轮未记录 RAG 注入。" }, ::copy)
                        DebugSection("世界书触发记录", entry.worldBookEvidence.joinToString("\n").ifBlank { "无世界书触发记录。" }, ::copy)
                        DebugSection("AI 原始文本", entry.outputText.ifBlank { "暂无文本。" }, ::copy)
                        DebugSection("模型返回的推理文本", entry.reasoningText.ifBlank { "未返回推理文本。" }, ::copy)
                        DebugSection("原始 SSE 片段（已省略 ${entry.droppedChunkCount} 个较早片段）",
                            entry.chunks.joinToString("\n\n") { it.rawPreview }.ifBlank { "暂无流数据。" }, ::copy)
                        DebugSection("重试记录", entry.retryEvents.joinToString("\n").ifBlank { "无重试。" }, ::copy)
                    }
                } }
            }
            if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Requests(Modifier.width(230.dp).fillMaxHeight()); Detail(Modifier.weight(1f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Requests(Modifier.fillMaxWidth().heightIn(max = 128.dp)); Detail(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DesktopChatMaintenancePanel(controller: DesktopChatMaintenanceController, sessionId: String) {
    val current by controller.state.collectAsState()
    val state = current.takeIf { it.sessionId == sessionId }
    var expanded by remember(sessionId) { mutableStateOf(false) }
    var confirm by remember(sessionId) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BootstrapButton("${if (expanded) "▾" else "▸"} 维护工具", secondary = true) { expanded = !expanded }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = 270.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionRow {
                BootstrapButton("检查消息顺序", secondary = true, enabled = !current.busy) { confirm = null; controller.inspectOrder(sessionId) }
                BootstrapButton("重建 RAG 索引", secondary = true, enabled = !current.busy) { confirm = "rag" }
                if (state?.backupAvailable == true) BootstrapButton("撤销顺序修复", secondary = true, enabled = !current.busy) { confirm = "undo" }
                if (state?.busy == true) BootstrapButton("停止维护", secondary = true) { controller.stop() }
            }
            StatusText("RAG 重建与安卓版一致：重建本角色卡参考文档的向量索引，会调用向量模型。")
            state?.preview?.let { plan ->
                StatusText("${plan.baseline.size} 条消息 · ${plan.moves.size} 条移动 · ${plan.anchoredMessageCount} 条有来源锚点")
                if (plan.orphanedAnchorMessageIds.isNotEmpty() || plan.cyclicAnchorMessageIds.isNotEmpty())
                    StatusText("失联来源 ${plan.orphanedAnchorMessageIds.size} 条 · 循环来源 ${plan.cyclicAnchorMessageIds.size} 条", DesktopBootstrapColors.warning)
                plan.moves.take(20).forEach { StatusText("消息 ${it.fromIndex + 1} → ${it.toIndex + 1}") }
                if (plan.moves.size > 20) StatusText("另有 ${plan.moves.size - 20} 条移动")
                if (plan.requiresRepair) BootstrapButton("应用顺序修复", enabled = !current.busy) { confirm = "apply" }
            }
            if (confirm != null) {
                StatusText(when (confirm) {
                    "rag" -> "重建本角色卡所有参考文档？每份文档验证成功后替换索引；失败时保留该文档旧索引。"
                    "apply" -> "按预览修复顺序？会先保存原顺序；消息正文、图片和时间保持，记忆仅检查来源，不自动调用 AI 修复。"
                    else -> "恢复修复前的消息顺序？修复后消息有增删或修改时会拒绝撤销。"
                })
                ActionRow {
                    BootstrapButton("确认执行维护", enabled = !current.busy) {
                        when (confirm) { "rag" -> controller.rebuildRag(sessionId); "apply" -> controller.applyOrder(sessionId); "undo" -> controller.undoOrder(sessionId) }
                        confirm = null
                    }
                    BootstrapButton("取消维护操作", secondary = true) { confirm = null }
                }
            }
            if (current.busy && state == null) StatusText("另一会话的维护正在进行，请稍候。")
            state?.let {
                if (it.message.isNotBlank()) StatusText(it.message)
                it.error?.let { message -> StatusText(message, DesktopBootstrapColors.destructive) }
            }
        }
    }
}

@Composable
private fun DebugSection(title: String, body: String, onCopy: (String) -> Unit) {
    var expanded by remember(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { BootstrapButton("${if (expanded) "▾" else "▸"} $title", secondary = true) { expanded = !expanded } }
            DesktopChatIconAction("复制$title", DesktopAppIcons.Copy) { onCopy(body) }
        }
        if (expanded) SelectionContainer {
            Box(Modifier.fillMaxWidth().heightIn(max = 300.dp).background(DesktopBootstrapColors.muted)
                .verticalScroll(rememberScrollState()).padding(10.dp)) {
                BasicText(body, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
            }
        }
    }
}
