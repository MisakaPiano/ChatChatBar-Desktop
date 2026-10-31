package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class DataPage(val label: String) { BACKUP("备份与导入"), LOCATION("数据目录"), AUTO("自动备份"), UPDATE("版本与更新") }
private enum class BackupPage { OVERVIEW, EXPORT, IMPORT, CHECK }

@Composable
internal fun DesktopDataWorkspace(root: DesktopDataRootSwitchController, full: DesktopFullBackupController,
    merge: DesktopMergeImportController?, automatic: DesktopAutomaticBackupSettingsController,
    onExit: () -> Unit, openUpdateRequest: Int = 0, onUpdateRequestConsumed: () -> Unit = {}) {
    val updateNotices = LocalDesktopUpdateNotices.current
    val updateNotice = updateNotices?.state?.collectAsState()?.value
    val rootState by root.state.collectAsState()
    val backup by full.state.collectAsState()
    val imported = merge?.state?.collectAsState()?.value
    var selected by remember { mutableStateOf(DataPage.BACKUP) }
    var operation by remember { mutableStateOf(if (imported?.ready == true) BackupPage.IMPORT else BackupPage.OVERVIEW) }
    var exportRequest by remember { mutableStateOf(0) }
    val busy = backup.busy || backup.restartRequired || imported?.busy == true || imported?.restartRequired == true
    val moving = rootState !is DesktopDataRootSwitchState.Idle
    LaunchedEffect(imported?.busy, imported?.restartRequired) {
        if (imported?.busy == true || imported?.restartRequired == true) operation = BackupPage.IMPORT
    }
    val page = if (moving) DataPage.LOCATION else if (busy) DataPage.BACKUP else selected
    LaunchedEffect(openUpdateRequest) {
        if (openUpdateRequest > 0) { selected = DataPage.UPDATE; onUpdateRequestConsumed() }
    }
    LaunchedEffect(page, updateNotice?.showStartupNotice) {
        if (page == DataPage.UPDATE) updateNotices?.dismissStartupNotice()
    }
    DesktopWorkspacePage("数据", DataPage.entries.map {
        DesktopWorkspaceDestination(it.name, it.label, it == DataPage.UPDATE && updateNotice?.pendingVersion != null)
    }, page.name,
        enabled = !busy && !moving, onSelect = { selected = DataPage.valueOf(it) }) {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) {
            DesktopRetainedPane(page == DataPage.BACKUP) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (operation == BackupPage.OVERVIEW) {
                        DesktopDataSectionHeading("备份与导入")
                        StatusText("选择需要的操作，在检查结果和冲突选择确认后再导入。")
                        DataOperationCard("导出备份", "选择设置、角色、会话和媒体，并按需设置备份密码。", "选择导出内容") {
                            operation = BackupPage.EXPORT; exportRequest++
                        }
                        DataOperationCard("导入与合并", "读取手机或桌面备份，保留当前资料，并由你选择冲突处理方式。", "选择备份并检查", merge != null) {
                            operation = BackupPage.IMPORT
                        }
                        DataOperationCard("检查备份文件", "只检查完整性和文件格式，不导入数据。", "检查已有备份") { operation = BackupPage.CHECK }
                    } else BootstrapButton("返回备份概览", secondary = true, enabled = !busy) { operation = BackupPage.OVERVIEW }
                    DesktopRetainedPane(operation == BackupPage.IMPORT) {
                        merge?.let { DesktopMergeImportPanel(it, onExit) }
                    }
                    DesktopRetainedPane(operation == BackupPage.EXPORT || operation == BackupPage.CHECK) {
                        DesktopFullBackupPanel(full,
                            if (operation == BackupPage.CHECK) DesktopFullBackupView.CHECK else DesktopFullBackupView.EXPORT, exportRequest, onExit)
                    }
                }
            }
            DesktopRetainedPane(page == DataPage.LOCATION) { DataWorkspaceScroll { DesktopDataSectionHeading("数据目录"); DesktopDataRootPanel(root, onExit) } }
            DesktopRetainedPane(page == DataPage.AUTO) { DataWorkspaceScroll { DesktopAutomaticBackupSettingsPanel(automatic) } }
            DesktopRetainedPane(page == DataPage.UPDATE) { DataWorkspaceScroll { DesktopUpdatePanel(onExit = onExit) } }
        }
    }
}

@Composable
private fun DataWorkspaceScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
private fun DataOperationCard(title: String, description: String, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DesktopDataSectionHeading(title); StatusText(description)
        BootstrapButton(action, enabled = enabled, onClick = onClick)
    }
}
