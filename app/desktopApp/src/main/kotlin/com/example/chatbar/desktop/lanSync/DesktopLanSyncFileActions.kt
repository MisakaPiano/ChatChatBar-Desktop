@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.lanSync.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uses the application's actual native picker. Locations and recovery secrets are never saveable. */
@Composable
internal fun DesktopLanSyncFileActions(coordinator: LanSyncGroupCoordinator, state: LanSyncGroupPanelState,
    filePicker: DesktopFilePicker? = null) {
    val recovery = coordinator.fileRecovery
    val exporter = coordinator.fileExport
    val recoveryState = recovery?.state?.collectAsState()?.value ?: LanSyncGroupFileRecoveryState()
    val exportState = exporter?.state?.collectAsState()?.value ?: LanSyncGroupEncryptedFileExportState()
    val cloudBusy = coordinator.cloudRecovery?.state?.collectAsState()?.value?.busy == true
    val scope = rememberCoroutineScope()
    var selectedFile by remember(coordinator) { mutableStateOf<Path?>(null) }
    var pickerBusy by remember(coordinator) { mutableStateOf(false) }
    var pickerNotice by remember(coordinator) { mutableStateOf<String?>(null) }
    var code by remember(coordinator, state.privacyRevision) { mutableStateOf("") }
    var secretRevision by remember(coordinator) { mutableStateOf(0) }
    var confirmed by remember(recoveryState.review?.binding, state.privacyRevision) { mutableStateOf(false) }
    fun clearSecrets() { code = ""; confirmed = false; secretRevision++ }
    val clearCurrentSecrets by rememberUpdatedState(::clearSecrets)
    val currentState by rememberUpdatedState(state)
    val canRecover = state.stage == LanSyncGroupPanelStage.EMPTY || state.stage == LanSyncGroupPanelStage.JOINING || state.canResumeExternalRecovery
    val type = remember { DesktopFileType("设备组加密同步文件", listOf("cbgroup")) }
    DisposableEffect(coordinator) {
        onDispose { clearCurrentSecrets(); selectedFile = null; recovery?.hide(); exporter?.hide() }
    }
    LaunchedEffect(recoveryState.visible, recoveryState.review?.binding) { clearSecrets() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (canRecover) BootstrapButton(if (state.stage == LanSyncGroupPanelStage.READY) "接续加密文件恢复" else "从加密文件恢复设备组", secondary = true,
            enabled = filePicker != null && recovery != null && !state.busy && !cloudBusy && !exportState.busy && !pickerBusy) {
            pickerNotice = null; clearSecrets(); coordinator.openFileRecovery()
        }
        if (state.stage == LanSyncGroupPanelStage.READY) BootstrapButton("导出加密同步文件", secondary = true,
            enabled = filePicker != null && exporter != null && !state.busy && !cloudBusy && !recoveryState.busy && !pickerBusy) {
            pickerNotice = null; coordinator.openFileExport()
        }
        if (filePicker == null && (canRecover || state.stage == LanSyncGroupPanelStage.READY)) StatusText("文件选择暂不可用", DesktopBootstrapColors.mutedForeground)
        pickerNotice?.let { StatusText(it, DesktopBootstrapColors.mutedForeground) }
    }

    if (recoveryState.visible && recovery != null) {
        fun closeRecovery() {
            clearSecrets(); selectedFile = null; pickerNotice = null; recovery.lock(); coordinator.open()
        }
        DesktopFileActionModal("从加密文件恢复设备组", if (recoveryState.busy) "取消并关闭" else "关闭", ::closeRecovery) {
            StatusText("原设备不在线时，可用加密同步文件和对应的恢复码恢复原设备组，并将此设备登记为同组的新日常设备。")
            StatusText("只能恢复文件中实际可取得并已核验的历史，不能保证是原设备组的最新状态。读取和核对阶段不会签署新的组管理操作。", DesktopBootstrapColors.warning)
            StatusText(recoveryState.status)
            recoveryState.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
            pickerNotice?.let { StatusText(it, DesktopBootstrapColors.mutedForeground) }
            if (recoveryState.pending) StatusText("仍有资料待核对或审阅，请按同步页的实际状态继续处理。", DesktopBootstrapColors.warning)
            val review = recoveryState.review
            if (review != null && !recoveryState.complete) {
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusText("原设备组：${review.group}")
                    StatusText("原管理身份：${review.rootFingerprint}")
                    StatusText("已核对的组检查点：${review.checkpoint}")
                    StatusText("已核对的设备成员数：${review.memberCount}")
                    StatusText("新日常设备：${review.name}")
                    StatusText("新设备身份：${review.device}")
                } }
                StatusText("确认后会在原设备组中登记此设备并恢复资料。冲突、删除和缺失依赖仍按实际状态处理；文件核验不代表网盘或其他设备已收到资料。")
                Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                    .toggleable(confirmed, enabled = !recoveryState.busy && canRecover, role = Role.Checkbox, onValueChange = { confirmed = it })
                    .padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusText(if (confirmed) "✓" else "□")
                    StatusText("我已核对以上身份，并同意登记此设备")
                }
                BootstrapButton("登记此设备并恢复资料", enabled = confirmed && !recoveryState.busy && canRecover) {
                    confirmed = false; recovery.confirm(review.binding)
                }
            } else if (!recoveryState.complete) {
                StatusText(selectedFile?.fileName?.toString()?.let { "所选文件：$it" } ?: "尚未选择加密同步文件")
                BootstrapButton("选择加密同步文件", secondary = true,
                    enabled = filePicker != null && !recoveryState.busy && !pickerBusy && canRecover && !cloudBusy) {
                    val picker = filePicker ?: return@BootstrapButton
                    clearSecrets(); pickerNotice = null; pickerBusy = true; recovery.hide()
                    scope.launch {
                        try {
                            val path = withContext(Dispatchers.IO) { picker.pickOpenFile(type) }
                            clearCurrentSecrets()
                            if ((currentState.stage in setOf(LanSyncGroupPanelStage.EMPTY, LanSyncGroupPanelStage.JOINING) || currentState.canResumeExternalRecovery)) {
                                if (path != null) selectedFile = path
                                pickerNotice = if (path == null) "未选择文件" else null
                                coordinator.openFileRecovery()
                            } else pickerNotice = "此设备已加入设备组，未开始文件恢复"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { pickerNotice = "无法打开文件选择器，请重试"; coordinator.openFileRecovery() }
                        finally { pickerBusy = false }
                    }
                }
                key(secretRevision, state.privacyRevision) { LabeledField("设备组恢复码", code, secret = true,
                    enabled = !recoveryState.busy && canRecover) { code = it } }
                BootstrapButton("读取并核对设备组", enabled = !recoveryState.busy && !cloudBusy && selectedFile != null && code.isNotBlank() && canRecover) {
                    val path = selectedFile ?: return@BootstrapButton
                    val captured = code; clearSecrets(); pickerNotice = null
                    if (!coordinator.prepareFileRecovery({ Files.newInputStream(path, NOFOLLOW_LINKS) }, captured))
                        pickerNotice = "另一恢复正在进行，请先继续或停止当前恢复"
                }
            }
        }
    }
    if (exportState.visible && exporter != null) {
        DesktopFileActionModal("导出加密同步文件", "关闭", exporter::hide) {
            StatusText("导出本次已核验的设备组密文。恢复时还需要单独保存的设备组恢复码。")
            StatusText(exportState.status)
            exportState.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
            pickerNotice?.let { StatusText(it, DesktopBootstrapColors.mutedForeground) }
            exportState.bytes?.let { StatusText("文件大小：${NumberFormat.getIntegerInstance().format(it)} 字节") }
            StatusText(if (exportState.writeCompleted) "写出：已完成" else "写出：尚未确认")
            StatusText(if (exportState.readbackVerified) "回读核验：已通过" else "回读核验：尚未完成")
            StatusText("回读核验只确认所选文件的完整字节，不等于已送达其他设备或网盘。", DesktopBootstrapColors.mutedForeground)
            BootstrapButton("准备加密同步文件", enabled = !exportState.busy && !pickerBusy && state.stage == LanSyncGroupPanelStage.READY) {
                pickerNotice = null; exporter.prepare()
            }
            exportState.preparedBinding?.let { binding -> BootstrapButton("选择保存位置并写出", secondary = true,
                enabled = filePicker != null && !exportState.busy && !pickerBusy) {
                val picker = filePicker ?: return@BootstrapButton
                pickerNotice = null; pickerBusy = true; exporter.hide()
                scope.launch {
                    try {
                        val path = withContext(Dispatchers.IO) { picker.pickSaveFile(type, "ChatBar.cbgroup") }
                        if (currentState.stage != LanSyncGroupPanelStage.READY || exporter.state.value.preparedBinding != binding || exporter.state.value.busy) {
                            pickerNotice = "这次文件选择已失效，请重新准备或选择保存位置"
                        } else {
                            coordinator.openFileExport()
                            if (path == null) pickerNotice = "未选择保存位置"
                            else exporter.write(binding, writer = { streamer -> DesktopExternalFileWriter.write(path) { streamer(this) } },
                                readback = { Files.newInputStream(path, NOFOLLOW_LINKS) })
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { pickerNotice = "无法选择保存位置，请重试"; exporter.open() }
                    finally { pickerBusy = false }
                }
            } }
            if (exportState.busy || exportState.preparedBinding != null) BootstrapButton("取消本次导出", secondary = true) { exporter.lock() }
        }
    }
}

@Composable
private fun DesktopFileActionModal(title: String, closeLabel: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 780.dp).fillMaxWidth().fillMaxHeight(.94f).background(DesktopBootstrapColors.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DesktopDataSectionHeading(title)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BootstrapButton(closeLabel, secondary = true, onClick = onClose)
                }
            }
        }
    }
}
