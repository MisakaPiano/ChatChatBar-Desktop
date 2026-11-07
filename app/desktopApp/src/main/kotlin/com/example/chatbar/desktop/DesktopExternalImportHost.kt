package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.example.chatbar.domain.image.*

@Composable
internal fun DesktopExternalImportNotice(controller: DesktopExternalImportController) {
    val queue by controller.queue.state.collectAsState()
    val state by controller.state.collectAsState()
    if (queue.files.isEmpty() && queue.error == null) return
    Row(Modifier.fillMaxWidth().background(DesktopBootstrapColors.card).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { BasicText(if (queue.error != null) "外部导入暂停，原队列记录已保留" else "待处理文件 ${queue.files.size} · ${state.item?.path?.fileName ?: "正在准备"}",
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 13.sp)) }
        if (queue.error != null) BootstrapButton("查看原因", secondary = true) { controller.show(true) }
        else BootstrapButton("查看导入队列", secondary = true) { controller.show(true) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopExternalImportHost(controller: DesktopExternalImportController, sessions: List<DesktopPrimarySessionItem>,
    onView: (DesktopExternalImportKind) -> Unit, onMerge: () -> Unit) {
    val state by controller.state.collectAsState()
    val queue by controller.queue.state.collectAsState()
    val transfer by controller.typed.state.collectAsState()
    val merge by controller.merge.state.collectAsState()
    if (!state.shown) return
    if (queue.error != null) {
        DesktopModalSurface { Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 650.dp).fillMaxWidth().background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopDataSectionHeading("外部导入暂停") }
                    DesktopIconAction("关闭导入提示", DesktopAppIcons.Close) { controller.show(false) }
                }
                StatusText(queue.error!!)
                controller.queue.recordPath?.let { SelectionContainer { StatusText("队列记录位置：$it") } }
                BootstrapButton("重新读取队列", secondary = true, enabled = queue.canReload) { controller.queue.reload() }
            }
        } }
        return
    }
    val item = state.item ?: return
    if (state.phase == DesktopExternalImportPhase.PASSWORD && !state.busy) {
        merge.passwordRequest?.takeIf { it.externalQueue }?.let { request ->
            DesktopBackupPasswordDialog(request, controller::retryBackupPassword, controller::cancelBackupPassword, null)
        }
        return
    }
    var sessionId by remember(item.id) { mutableStateOf<String?>(null) }
    var imageTarget by remember(item.id) { mutableStateOf(NovelAiImageUseTarget.IMAGE_TO_IMAGE) }
    var previewImage by remember(item.id) { mutableStateOf(false) }
    fun view() { controller.show(false); onView(state.kind) }
    if (previewImage) {
        DesktopTransientImagePreview(item.id, controller::previewImage) { previewImage = false }
        return
    }
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 700.dp).fillMaxWidth().heightIn(max = 650.dp)
                .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopDataSectionHeading("打开文件 · 待处理 ${queue.files.size} 项") }
                    DesktopIconAction("暂时收起导入队列", DesktopAppIcons.Close) { controller.show(false) }
                }
                SelectionContainer { StatusText(item.path.toString()) }
                StatusText("类型：${state.kind.label}")
                StatusText(if (merge.busy && state.kind == DesktopExternalImportKind.BACKUP) merge.message
                    else if (merge.restartRequired && state.kind == DesktopExternalImportKind.BACKUP)
                    "合并已进入启动切换阶段，请正常重启。其余文件保留在队列，不会继续写入当前目录。" else state.message)
                if (state.phase == DesktopExternalImportPhase.READY) {
                    if (state.kind == DesktopExternalImportKind.UNKNOWN) {
                        StatusText("未能自动识别。可手动选择类型；仍会通过该类型原有的严格检查。")
                    }
                    if (state.canChooseType) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DesktopExternalImportKind.entries.filter { it != DesktopExternalImportKind.UNKNOWN }.forEach { kind ->
                                BootstrapButton(kind.label, secondary = true, enabled = !state.busy) { controller.choose(kind) }
                            }
                        }
                    }
                    if (state.kind == DesktopExternalImportKind.SAVE_SLOT) {
                        StatusText("选择接收存档的会话。只加入存档列表，不恢复或替换聊天。")
                        Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            sessions.forEach { session -> BootstrapButton((if (sessionId == session.id) "✓ " else "") + session.title,
                                secondary = sessionId != session.id, enabled = !state.busy) { sessionId = session.id } }
                        }
                        if (sessions.isEmpty()) StatusText("请先在对话页创建接收存档的会话，然后回到队列。")
                    }
                    if (state.kind == DesktopExternalImportKind.IMAGE) {
                        BootstrapButton("预览图片", secondary = true, enabled = !state.busy) { previewImage = true }
                        StatusText("预览保留原动画。静态图片可选择 Studio 中的用途；不会发送生图请求。")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.imageTargets.forEach { target -> BootstrapButton(target.displayName,
                                secondary = imageTarget != target, enabled = !state.busy) { imageTarget = target } }
                        }
                    }
                    BootstrapButton(if (state.kind == DesktopExternalImportKind.BACKUP) "检查备份" else "导入当前文件",
                        enabled = !state.busy && state.kind != DesktopExternalImportKind.UNKNOWN &&
                            (state.kind != DesktopExternalImportKind.SAVE_SLOT || sessionId != null)) {
                        controller.import(sessionId, imageTarget = imageTarget)
                    }
                }
                if (state.phase == DesktopExternalImportPhase.CONFLICT) {
                    transfer.pendingConflict?.let { conflict -> StatusText("现有：${conflict.existingName}\n导入：${conflict.incomingName}") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BootstrapButton("作为新内容导入", enabled = !state.busy) { controller.resolve(DesktopTransferConflictAction.IMPORT_AS_NEW) }
                        val overwrite = (transfer.pendingConflict as? DesktopPendingTransferConflict.Character)?.overwriteAllowed != false
                        BootstrapButton("覆盖现有内容", enabled = !state.busy && overwrite, secondary = true) { controller.resolve(DesktopTransferConflictAction.OVERWRITE) }
                    }
                }
                if (state.phase == DesktopExternalImportPhase.PREVIEW && !merge.restartRequired) {
                    BootstrapButton("前往合并预览", enabled = !state.busy && !merge.busy) { controller.show(false); onMerge() }
                    BootstrapButton("结束此项检查，不合并", enabled = !state.busy && !merge.busy, secondary = true) { controller.skip() }
                }
                if (state.phase == DesktopExternalImportPhase.COMPLETE) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BootstrapButton("查看导入结果", secondary = true, enabled = !state.busy) { view() }
                    BootstrapButton("完成，处理下一项", enabled = !state.busy) { controller.next() }
                }
                if (state.phase == DesktopExternalImportPhase.UNCERTAIN) {
                    BootstrapButton("前往对应页面核对", secondary = true, enabled = !state.busy) { view() }
                    BootstrapButton("重新核对导入结果", secondary = true, enabled = !state.busy) { controller.recheck() }
                    StatusText("跳过不会撤销已经完成的写入，也不会清除导入器保留的未确认记录。")
                    BootstrapButton("已核对，保留结果并结束此项", secondary = true, enabled = !state.busy && !merge.busy && !merge.restartRequired) { controller.skip(confirmedUncertain = true) }
                }
                if (state.phase == DesktopExternalImportPhase.ERROR) BootstrapButton("重试检查", enabled = !state.busy, secondary = true) { controller.retryInspection() }
                if (!state.busy && state.phase !in setOf(DesktopExternalImportPhase.COMPLETE, DesktopExternalImportPhase.PREVIEW, DesktopExternalImportPhase.UNCERTAIN))
                    BootstrapButton("跳过此文件", secondary = true, enabled = !merge.restartRequired) { controller.skip() }
                if (state.busy && state.kind in setOf(DesktopExternalImportKind.BACKUP, DesktopExternalImportKind.SAVE_SLOT))
                    BootstrapButton("请求停止", secondary = true) { controller.stop() }
            }
        }
    }
}
