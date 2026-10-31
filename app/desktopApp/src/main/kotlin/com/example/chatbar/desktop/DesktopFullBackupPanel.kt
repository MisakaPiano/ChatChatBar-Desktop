package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopFullBackupPanel(controller: DesktopFullBackupController, onExit: () -> Unit) =
    DesktopFullBackupPanel(controller, DesktopFullBackupView.ALL, onExit = onExit)

@Composable
internal fun DesktopFullBackupPanel(controller: DesktopFullBackupController,
    view: DesktopFullBackupView, exportRequest: Int = 0, onExit: () -> Unit) {
    DisposableEffect(controller) { onDispose { controller.leavePanel() } }
    val state by controller.state.collectAsState()
    var password by remember { mutableStateOf("") }
    var exportOpen by remember { mutableStateOf(false) }
    LaunchedEffect(exportRequest) { if (exportRequest > 0) { exportOpen = true; controller.refreshExportCatalog() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DesktopDataSectionHeading(when (view) { DesktopFullBackupView.EXPORT -> "导出备份"; DesktopFullBackupView.CHECK -> "检查备份文件"; else -> "备份导出与文件检查" })
        StatusText("备份角色、聊天、版本、记忆、图片、录音、存档和独立保全内容。恢复和手机迁移请返回备份概览，选择“导入与合并”。")
        StatusText("可随备份保存已配置的 API 密钥等凭据；导出不会改变本机目录或凭据。文件检查仅用于核对，不会导入数据。")
        StatusText("包含 Desktop 专有数据时仅支持 Desktop 恢复。Android 双向迁移尚需独立设备验收，不保证所有历史或未来格式可用。")
        if (!state.restartRequired) {
            if (view != DesktopFullBackupView.EXPORT) BackupPasswordField("备份检查密码（加密文件填写；未加密留空）", password, !state.busy) { password = it }
            ActionRow {
                if (view != DesktopFullBackupView.CHECK) BootstrapButton("选择导出内容…", enabled = !state.busy) {
                    exportOpen = true; controller.refreshExportCatalog()
                }
                if (view != DesktopFullBackupView.EXPORT) BootstrapButton("选择备份并检查…", secondary = true, enabled = !state.busy) {
                    val secret = password.takeIf { it.isNotEmpty() }?.toCharArray(); password = ""; controller.verifyFile(secret)
                }
                if (state.busy) BootstrapButton("停止", secondary = true) { controller.stop() }
            }
        }
        state.summary?.takeIf { view != DesktopFullBackupView.EXPORT }?.let { summary ->
            StatusText("已校验 ${summary.fileCount} 个文件 · ${summary.totalBytes / 1024} KiB · 来源版本 ${summary.appVersionName}")
            if (state.desktopOnly) StatusText("该备份含 Desktop 专有数据，不能据此宣称 Android 兼容。", DesktopBootstrapColors.warning)
            StatusText("文件校验通过不代表合并已完成。请在“导入与合并”中核对当前电脑内容与凭据冲突。")
            BootstrapButton("关闭检查", secondary = true, enabled = !state.busy) { controller.discardInspection() }
        }
        if (state.message.isNotBlank()) StatusText(state.message)
        state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        if (state.restartRequired) {
            StatusText("需要正常退出。新的数据操作已暂停，数据目录锁会在安全关闭后释放。", DesktopBootstrapColors.warning)
            BootstrapButton("正常退出应用", onClick = onExit)
        }
    }
    if (exportOpen) DesktopBackupExportDialog(state, controller::refreshExportCatalog,
        onClose = { exportOpen = false }, onExport = { selection, secret -> controller.export(secret, true, selection) }, onStop = controller::stop)
    state.exportResult?.let { result ->
        DesktopModalSurface {
            Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(20.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().background(DesktopBootstrapColors.card).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DesktopDataSectionHeading(if (result.success) "导出成功" else "导出失败")
                    StatusText(result.message)
                    result.path?.let { path ->
                        StatusText("保存位置")
                        SelectionContainer { StatusText(path.toString()) }
                    }
                    BootstrapButton("确定") { controller.dismissExportResult() }
                }
            }
        }
    }
}

internal enum class DesktopFullBackupView { ALL, EXPORT, CHECK }
