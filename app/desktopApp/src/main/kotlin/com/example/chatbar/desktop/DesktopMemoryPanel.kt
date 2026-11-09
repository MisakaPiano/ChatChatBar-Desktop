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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.launch

@Composable
internal fun DesktopMemoryPanel(controller: DesktopMemoryController, embedded: Boolean = false,
    onJumpToSource: ((String, String) -> Unit)? = null) {
    val state by controller.state.collectAsState()
    val backfills by controller.backfill.collectAsState()
    val repairs by controller.repair.collectAsState()
    if (!state.open || state.inSessionSettings != embedded) return
    val editing = state.editingNode
    val draft = state.nodeText
    val headDraft = state.headDraft
    val scope = rememberCoroutineScope()
    var confirmation by remember(state.sessionId) { mutableStateOf<String?>(null) }
    val dirty = state.dirty
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(if (embedded) 0.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    StatusText("长期记忆")
                    StatusText("${state.prompt?.usedArchiveChars ?: 0}/${state.limitChars} 字 · Archive ${state.archiveStatus.memoryUiLabel()} · HEAD ${state.headStatus.memoryUiLabel()}")
                }
                DesktopIconAction("维护与兼容参考", DesktopAppIcons.Settings) { confirmation = if (confirmation == "tools") null else "tools" }
                DesktopIconAction("清空长期记忆…", DesktopAppIcons.Delete, enabled = !state.busy && !state.operationBusy && !dirty) { confirmation = "clear" }
                DesktopIconAction("重建全部记忆…", DesktopAppIcons.History, enabled = !state.busy && !state.operationBusy && !dirty) { confirmation = "rebuild" }
                DesktopIconAction("刷新长期记忆", DesktopAppIcons.Refresh, enabled = !state.operationBusy) { controller.refresh() }
            }
            state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
            if (state.message.isNotBlank()) StatusText(state.message, DesktopBootstrapColors.warning)
            if (state.busy) ActionRow {
                StatusText("记忆任务进行中；关闭此窗口后任务继续。")
                BootstrapButton("停止任务", secondary = true) { controller.stop() }
            }
            if (state.leavePrompt) ActionRow {
                StatusText("有尚未保存的编辑。")
                BootstrapButton("继续编辑") { controller.resolveLeave(discard = false) }
                BootstrapButton("放弃编辑并继续", secondary = true) { controller.resolveLeave(discard = true) }
            }
            ActionRow {
                DesktopMemoryPage.entries.forEach { page ->
                    BootstrapButton(page.label, secondary = state.page != page) { controller.page(page) }
                }
            }
            if (state.page.tier != null) ActionRow {
                DesktopMemorySection.entries.forEach { section ->
                    BootstrapButton(section.label, secondary = state.section != section) { controller.section(section) }
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
                    StudioSwitch("长期记忆", state.enabled, enabled = !state.operationBusy && !state.busy) { controller.setEnabled(!state.enabled) }
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
                    val legacy = state.nodes.filter { it.tier == MemoryTier.LEGACY_REFERENCE }
                    if (legacy.isNotEmpty()) {
                        StatusText("旧版参考 · 保留原数据，不代表当前进展")
                        SelectionContainer { Column { legacy.forEach { StatusText(it.body) } } }
                    }
                }
                if (state.page.tier != null && state.section == DesktopMemorySection.HISTORY) {
                    if (state.history.isEmpty()) StatusText("当前层级尚无可见历史版本。")
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
                    if (state.historyHasMore) BootstrapButton("加载更多历史", secondary = true, onClick = controller::loadMoreHistory)
                }
                if (confirmation in listOf("clear", "rebuild", "head") || confirmation?.startsWith("restore:") == true) {
                    StatusText(when (confirmation) {
                        "clear" -> "清空派生记忆与历史，保留聊天原文；此前剧情不会自动补回。"
                        "rebuild" -> "将清除旧派生记忆及其历史，按聊天原文重新生成，可能产生多次模型请求。聊天原文保持原状。"
                        "head" -> "使用当前会话模型重新生成 HEAD，可能产生费用；失败保留原状态。"
                        else -> "将恢复所选分页版本；真实聊天消息保持原状。"
                    })
                    ActionRow {
                        BootstrapButton("取消", secondary = true) { confirmation = "tools" }
                        BootstrapButton("确认", enabled = !state.busy) {
                            when (confirmation) {
                                "clear" -> controller.clear()
                                "rebuild" -> controller.regenerateAll()
                                "head" -> controller.regenerateHead()
                                else -> controller.restoreRevision(confirmation!!.removePrefix("restore:"))
                            }
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
                    if (state.page == DesktopMemoryPage.HEAD) {
                    val throughT = memory.timeline.firstOrNull { it.sourceTurnId == memory.head.throughSourceTurnId }?.displayT
                    StatusText("HEAD${throughT?.let { " ｜截至 T$it" }.orEmpty()}${if (memory.head.stale) "（来源已变更，暂停注入）" else ""}")
                    if (headDraft == null) {
                        SelectionContainer { StatusText(memory.head.render().ifBlank { "尚未建立当前状态" }) }
                        ActionRow {
                            BootstrapButton("编辑当前状态", secondary = true, enabled = !state.busy && editing == null) { controller.beginHeadEdit() }
                            BootstrapButton("重建当前状态 HEAD…", secondary = true, enabled = !state.busy && !state.operationBusy) { confirmation = "head" }
                        }
                    } else {
                        val h = headDraft!!
                        MemoryField("位置", h.location) { controller.headDraft(h.copy(location = it)) }
                        MemoryField("人物状态", h.participants) { controller.headDraft(h.copy(participants = it)) }
                        MemoryField("关系", h.relationships) { controller.headDraft(h.copy(relationships = it)) }
                        MemoryField("目标", h.goals) { controller.headDraft(h.copy(goals = it)) }
                        MemoryField("待解决", h.unresolved) { controller.headDraft(h.copy(unresolved = it)) }
                        MemoryField("世界状态", h.worldState) { controller.headDraft(h.copy(worldState = it)) }
                        ActionRow {
                            BootstrapButton("保存当前状态", enabled = !state.operationBusy) { controller.saveHeadDraft() }
                            BootstrapButton("取消编辑", secondary = true, enabled = !state.operationBusy) { controller.cancelEditing() }
                        }
                    }
                    }
                }
                if (state.page == DesktopMemoryPage.PREVIEW) {
                    StatusText("Archive + HEAD · 按当前来源只读校验与同一发送格式器生成；不调用模型、不写入缓存。发送前仍会重新校验。")
                    if (!state.enabled) StatusText("此会话长期记忆已关闭，实际请求不注入此块。")
                    else SelectionContainer { StatusText(state.prompt?.fullText?.ifBlank { "（空）" } ?: "记忆尚未初始化，请正常刷新后查看。") }
                    state.prompt?.warnings?.forEach { StatusText(it.message, DesktopBootstrapColors.warning) }
                }
                val nodes = if (state.page.tier != null && state.section != DesktopMemorySection.HISTORY) state.nodes.filter { it.tier == state.page.tier } else emptyList()
                if (state.page.tier != null && state.section != DesktopMemorySection.HISTORY && nodes.isEmpty()) StatusText("当前分页还没有记忆内容。")
                nodes.forEachIndexed { index, node ->
                    Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val range = com.example.chatbar.domain.memory.MemoryTimelinePolicy.verifiedRange(node, state.memory?.timeline.orEmpty())
                        StatusText("${node.tier}${range?.let { " T${it.startT}-T${it.endT}" } ?: " · 范围未能核实"} · 第 ${index + 1} 条${if (node.id in state.memory?.staleSourcesByNodeId.orEmpty()) " · 来源已变更，暂停注入" else ""}")
                        if (editing?.id == node.id) {
                            MemoryField("正文", draft, controller::nodeText)
                            ActionRow {
                                BootstrapButton("保存", enabled = !state.operationBusy) { controller.saveNodeDraft() }
                                BootstrapButton("取消", secondary = true, enabled = !state.operationBusy) { controller.cancelEditing() }
                            }
                        } else {
                            SelectionContainer { StatusText(node.body) }
                            if (state.section == DesktopMemorySection.CURRENT && onJumpToSource != null) BootstrapButton("跳到来源", secondary = true) {
                                scope.launch { controller.sourceMessage(node.id)?.let { messageId -> state.sessionId?.let { onJumpToSource(it, messageId) } } }
                            }
                            if (state.section == DesktopMemorySection.EDIT && node.tier != MemoryTier.LEGACY_REFERENCE) ActionRow {
                                BootstrapButton("编辑", secondary = true,
                                    enabled = !state.busy && !dirty) { controller.beginNodeEdit(node) }
                                BootstrapButton("重新生成候选…", secondary = true,
                                    enabled = !state.busy && !dirty) { confirmation = "node:${node.id}" }
                            }
                            if (confirmation == "node:${node.id}") {
                                StatusText("使用当前会话模型生成候选，可能产生费用；审阅并保存后才替换原记忆。")
                                ActionRow {
                                    BootstrapButton("取消", secondary = true) { confirmation = null }
                                    BootstrapButton("生成候选", enabled = !state.busy && !state.operationBusy) {
                                        confirmation = null
                                        controller.regenerateNode(node.id) { candidate -> controller.beginNodeEdit(node); controller.nodeText(candidate) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!embedded) ActionRow { BootstrapButton("返回聊天", secondary = true, onClick = controller::closePanel) }
        }
    }
    if (embedded) content() else DesktopScreenshotWindow(onClose = controller::closePanel, title = "长期记忆", content = content)
}

private fun MemoryUpdateStatus.memoryUiLabel(): String = when (this) {
    MemoryUpdateStatus.IDLE -> "空闲"
    MemoryUpdateStatus.UPDATING -> "更新中"
    MemoryUpdateStatus.WAITING_FOR_NETWORK -> "等待网络"
    MemoryUpdateStatus.ERROR -> "错误"
    MemoryUpdateStatus.LIMIT_DECISION_REQUIRED -> "等待容量选择"
    MemoryUpdateStatus.PAUSED -> "已暂停"
}

@Composable
private fun MemoryField(label: String, value: String, change: (String) -> Unit) {
    StatusText(label)
    BasicTextField(value, change, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
        .border(1.dp, DesktopBootstrapColors.border).padding(8.dp),
        cursorBrush = DesktopBootstrapColors.inputCaret, textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
}
