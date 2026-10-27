package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.*

@Composable
internal fun DesktopMemoryPanel(controller: DesktopMemoryController) {
    val state by controller.state.collectAsState()
    val backfills by controller.backfill.collectAsState()
    val repairs by controller.repair.collectAsState()
    if (!state.open) return
    var editing by remember(state.sessionId) { mutableStateOf<MemoryNode?>(null) }
    var draft by remember(state.sessionId) { mutableStateOf("") }
    var headDraft by remember(state.sessionId) { mutableStateOf<MemoryHead?>(null) }
    var closePending by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val dirty = (editing != null && draft != editing?.body) || (headDraft != null && headDraft != state.memory?.head)
    fun requestClose() { if (dirty) closePending = true else controller.closePanel() }
    DesktopScreenshotWindow(onClose = ::requestClose, title = "长期记忆", content = {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionRow {
                BootstrapButton(if (state.enabled) "长期记忆已开启" else "长期记忆已关闭", secondary = !state.enabled,
                    enabled = !state.operationBusy && !state.busy) { controller.setEnabled(!state.enabled) }
                BootstrapButton("刷新", secondary = true, enabled = !state.operationBusy) { controller.refresh() }
                BootstrapButton("维护与历史", secondary = true) { confirmation = if (confirmation == "tools") null else "tools" }
            }
            state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
            if (state.message.isNotBlank()) StatusText(state.message, DesktopBootstrapColors.warning)
            if (state.busy) ActionRow {
                StatusText("记忆任务进行中；关闭此窗口后任务继续。")
                BootstrapButton("停止任务", secondary = true) { controller.stop() }
            }
            if (closePending) ActionRow {
                StatusText("有尚未保存的编辑。")
                BootstrapButton("继续编辑") { closePending = false }
                BootstrapButton("放弃编辑并关闭", secondary = true) { closePending = false; controller.closePanel() }
            }
            ActionRow {
                listOf(MemoryTier.EPISODE to "近期", MemoryTier.ARC to "阶段", MemoryTier.ERA to "长期", MemoryTier.LEGACY_REFERENCE to "旧版参考").forEach { (tier, label) ->
                    BootstrapButton(label, secondary = state.tier != tier, enabled = !dirty) { controller.tier(tier) }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                backfills[state.sessionId]?.let { progress ->
                    StatusText("补录 ${progress.completedSourceTurns}/${progress.totalSourceTurns} 轮 · 已完成 ${progress.completedEpisodes} 条记忆 · ${progress.currentRangeLabel}")
                    if (progress.streamingSummary.isNotBlank()) StatusText(progress.streamingSummary)
                }
                repairs[state.sessionId]?.let { progress ->
                    StatusText("来源修复 ${progress.completedRoots}/${progress.totalRoots} 项 · ${progress.currentRangeLabel}")
                    if (progress.streamingSummary.isNotBlank()) StatusText(progress.streamingSummary)
                }
                if (confirmation == "tools") {
                    StatusText("维护会使用当前会话模型，可能产生费用。缺口与来源变更会保留到明确处理。")
                    ActionRow {
                        BootstrapButton("重试维护", enabled = !state.busy) { controller.retry() }
                        BootstrapButton("估算补录", secondary = true, enabled = !state.busy) { controller.estimateBackfill() }
                        BootstrapButton("修复变更来源", secondary = true, enabled = !state.busy) { controller.repairSources() }
                        BootstrapButton("暂停补录", secondary = true) { controller.pauseBackfill() }
                        BootstrapButton("暂停来源修复", secondary = true) { controller.pauseRepair() }
                    }
                    state.estimate?.let { estimate ->
                        StatusText("缺少 ${estimate.missingSourceTurns} 个来源轮次；预计 Episode 请求 ${estimate.episodeCallsMin}～${estimate.episodeCallsMax} 次，另可能需要压缩与 HEAD 请求。")
                        BootstrapButton("开始补录", enabled = !state.busy) { controller.startBackfill() }
                    }
                    ActionRow {
                        BootstrapButton("重建全部记忆…", secondary = true, enabled = !state.busy) { confirmation = "rebuild" }
                        BootstrapButton("重建当前状态 HEAD", secondary = true, enabled = !state.busy) { controller.regenerateHead() }
                        BootstrapButton("扩大记忆预算", secondary = true, enabled = !state.busy) { controller.increaseLimit() }
                    }
                    state.history.forEach { revision ->
                        ActionRow {
                            StatusText("${revision.operation} · ${revision.createdAt}")
                            BootstrapButton("查看", secondary = true) { controller.previewRevision(revision.id) }
                            BootstrapButton("恢复此版本…", secondary = true, enabled = !state.busy && !dirty) { confirmation = "restore:${revision.id}" }
                            revision.parentRevisionId?.let { previous ->
                                BootstrapButton("查看变更前", secondary = true) { controller.previewRevision(previous) }
                                BootstrapButton("恢复变更前…", secondary = true, enabled = !state.busy && !dirty) { confirmation = "restore:$previous" }
                            }
                        }
                    }
                    state.historyNodes?.let { nodes ->
                        StatusText("历史版本预览")
                        SelectionContainer { Column { nodes.forEach { StatusText(it.body) } } }
                    }
                }
                if (confirmation == "rebuild" || confirmation?.startsWith("restore:") == true) {
                    StatusText(if (confirmation == "rebuild") "将清除旧派生记忆及其历史，按聊天原文重新生成，可能产生多次模型请求。聊天原文保持原状。" else "将恢复所选分页版本；真实聊天消息保持原状。")
                    ActionRow {
                        BootstrapButton("取消", secondary = true) { confirmation = "tools" }
                        BootstrapButton("确认", enabled = !state.busy) {
                            if (confirmation == "rebuild") controller.regenerateAll() else controller.restoreRevision(confirmation!!.removePrefix("restore:"))
                            confirmation = "tools"
                        }
                    }
                }
                state.memory?.let { memory ->
                    if (memory.pendingDecision != null) {
                        StatusText("记忆达到压缩决策点。请选择扩大容量或继续压缩。")
                        ActionRow {
                            BootstrapButton("扩大容量") { controller.expandOrCompress(true) }
                            BootstrapButton("继续压缩", secondary = true) { controller.expandOrCompress(false) }
                        }
                    }
                    StatusText("缺口 ${memory.gaps.sumOf { it.sourceTurnIds.size }} 轮 · 待归档 ${memory.pendingSourceTurnIds.size} 轮 · 来源待修复 ${memory.staleSourcesByNodeId.size} 项")
                    StatusText("当前状态 HEAD${if (memory.head.stale) "（来源已变更，暂停注入）" else ""}")
                    if (headDraft == null) {
                        SelectionContainer { StatusText(memory.head.render().ifBlank { "尚未建立当前状态" }) }
                        BootstrapButton("编辑当前状态", secondary = true, enabled = !state.busy && editing == null) { headDraft = memory.head }
                    } else {
                        val h = headDraft!!
                        MemoryField("位置", h.location) { headDraft = h.copy(location = it) }
                        MemoryField("人物", h.participants) { headDraft = h.copy(participants = it) }
                        MemoryField("关系", h.relationships) { headDraft = h.copy(relationships = it) }
                        MemoryField("目标", h.goals) { headDraft = h.copy(goals = it) }
                        MemoryField("待解决", h.unresolved) { headDraft = h.copy(unresolved = it) }
                        MemoryField("世界状态", h.worldState) { headDraft = h.copy(worldState = it) }
                        ActionRow {
                            BootstrapButton("保存当前状态", enabled = !state.operationBusy) { controller.editHead(h) { headDraft = null } }
                            BootstrapButton("取消编辑", secondary = true) { headDraft = null }
                        }
                    }
                }
                val nodes = state.nodes.filter { it.tier == state.tier }
                if (nodes.isEmpty()) StatusText("当前分页还没有记忆内容。")
                nodes.forEachIndexed { index, node ->
                    Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusText("第 ${index + 1} 条 · ${node.sourceTurnIds.size} 个来源轮次${if (node.staleSourceTurnIds.isNotEmpty()) " · 来源已变更" else ""}")
                        if (editing?.id == node.id) {
                            MemoryField("正文", draft) { draft = it }
                            ActionRow {
                                BootstrapButton("保存", enabled = !state.operationBusy) { controller.editNode(node.id, draft) { editing = null } }
                                BootstrapButton("取消", secondary = true) { editing = null }
                            }
                        } else {
                            SelectionContainer { StatusText(node.body) }
                            if (node.tier != MemoryTier.LEGACY_REFERENCE) ActionRow {
                                BootstrapButton("编辑", secondary = true,
                                    enabled = !state.busy && !dirty) { editing = node; draft = node.body }
                                BootstrapButton("重新生成候选…", secondary = true,
                                    enabled = !state.busy && !dirty) { confirmation = "node:${node.id}" }
                            }
                            if (confirmation == "node:${node.id}") {
                                StatusText("使用当前会话模型生成候选，可能产生费用；审阅并保存后才替换原记忆。")
                                ActionRow {
                                    BootstrapButton("取消", secondary = true) { confirmation = null }
                                    BootstrapButton("生成候选", enabled = !state.busy && !state.operationBusy) {
                                        confirmation = null
                                        controller.regenerateNode(node.id) { candidate -> editing = node; draft = candidate }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ActionRow { BootstrapButton("返回聊天", secondary = true, onClick = ::requestClose) }
        }
    })
}

@Composable
private fun MemoryField(label: String, value: String, change: (String) -> Unit) {
    StatusText(label)
    BasicTextField(value, change, modifier = Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(8.dp),
        textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
}
