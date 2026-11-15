package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class DataPage(val label: String) { BACKUP("备份与导入"), LAN("局域网同步"), LOCATION("数据目录"), AUTO("自动备份"), UPDATE("版本与更新"), RECOVERY("恢复与支持"), DIAGNOSTICS("支持与诊断") }
private enum class BackupPage { OVERVIEW, EXPORT, IMPORT, CHECK }

@Composable
internal fun DesktopDataWorkspace(root: DesktopDataRootSwitchController, full: DesktopFullBackupController,
    merge: DesktopMergeImportController?, automatic: DesktopAutomaticBackupSettingsController,
    onExit: () -> Unit, openUpdateRequest: Int = 0, onUpdateRequestConsumed: () -> Unit = {},
    openImportRequest: Int = 0, onImportRequestConsumed: () -> Unit = {},
    taskDetail: DesktopWorkDetail? = null, onTaskDetailConsumed: () -> Unit = {},
    diagnostics: (@Composable () -> Unit)? = null,
    lanSync: com.example.chatbar.domain.lanSync.LanSyncRuntime? = null,
    lanSyncGroup: com.example.chatbar.domain.lanSync.LanSyncGroupCoordinator? = null,
    legacyLanSync: (() -> com.example.chatbar.domain.lanSync.LanSyncRuntime)? = null,
    hasLanSyncGroupEvidence: () -> Boolean = { false }, lanSyncFilePicker: DesktopFilePicker? = null) {
    val updateNotices = LocalDesktopUpdateNotices.current
    val updateNotice = updateNotices?.state?.collectAsState()?.value
    val rootState by root.state.collectAsState()
    val backup by full.state.collectAsState()
    val imported = merge?.state?.collectAsState()?.value
    val t = LocalDesktopUiStrings.current
    val recovery = desktopRecoveryStatuses(rootState, backup, imported)
    var selected by remember { mutableStateOf(DataPage.BACKUP) }
    var operation by remember { mutableStateOf(if (imported?.ready == true) BackupPage.IMPORT else BackupPage.OVERVIEW) }
    var exportRequest by remember { mutableStateOf(0) }
    val busy = backup.busy || backup.restartRequired || imported?.busy == true || imported?.restartRequired == true
    val moving = rootState !is DesktopDataRootSwitchState.Idle
    fun openRecoveryDestination(destination: DesktopRecoveryDestination) {
        val current = full.state.value
        val incoming = merge?.state?.value
        if (root.state.value !is DesktopDataRootSwitchState.Idle || current.busy || current.restartRequired ||
            incoming?.busy == true || incoming?.restartRequired == true) return
        when (destination) {
            DesktopRecoveryDestination.LOCATION -> selected = DataPage.LOCATION
            DesktopRecoveryDestination.AUTO -> selected = DataPage.AUTO
            DesktopRecoveryDestination.UPDATE -> selected = DataPage.UPDATE
            DesktopRecoveryDestination.EXPORT -> { selected = DataPage.BACKUP; operation = BackupPage.EXPORT }
            DesktopRecoveryDestination.CHECK -> { selected = DataPage.BACKUP; operation = BackupPage.CHECK }
            DesktopRecoveryDestination.IMPORT -> if (merge != null) { selected = DataPage.BACKUP; operation = BackupPage.IMPORT }
        }
    }
    LaunchedEffect(taskDetail) {
        when (taskDetail) {
            DesktopWorkDetail.BACKUP_EXPORT -> { selected = DataPage.BACKUP; operation = BackupPage.EXPORT }
            DesktopWorkDetail.BACKUP_CHECK -> { selected = DataPage.BACKUP; operation = BackupPage.CHECK }
            DesktopWorkDetail.MERGE_IMPORT -> { selected = DataPage.BACKUP; operation = BackupPage.IMPORT }
            DesktopWorkDetail.AUTOMATIC_BACKUP -> selected = DataPage.AUTO
            DesktopWorkDetail.UPDATES -> selected = DataPage.UPDATE
            else -> Unit
        }
        if (taskDetail != null) onTaskDetailConsumed()
    }
    LaunchedEffect(imported?.busy, imported?.restartRequired) {
        if (imported?.busy == true || imported?.restartRequired == true) operation = BackupPage.IMPORT
    }
    val page = if (moving) DataPage.LOCATION else if (busy) DataPage.BACKUP else selected
    // Retained panes are not disposed when hidden; retire only their interactive password flow.
    DisposableEffect(page, operation, full, merge) {
        val departingOperation = operation
        onDispose {
            if (page == DataPage.BACKUP) when (departingOperation) {
                BackupPage.CHECK -> full.leavePasswordFlow()
                BackupPage.IMPORT -> merge?.leavePasswordFlow()
                else -> Unit
            }
        }
    }
    LaunchedEffect(openUpdateRequest) {
        if (openUpdateRequest > 0) { selected = DataPage.UPDATE; onUpdateRequestConsumed() }
    }
    LaunchedEffect(openImportRequest) {
        if (openImportRequest > 0) { selected = DataPage.BACKUP; operation = BackupPage.IMPORT; onImportRequestConsumed() }
    }
    LaunchedEffect(page, updateNotice?.showStartupNotice) {
        if (page == DataPage.UPDATE) updateNotices?.dismissStartupNotice()
    }
    DesktopWorkspacePage("数据", DataPage.entries.filter { it != DataPage.DIAGNOSTICS || diagnostics != null }.map {
        DesktopWorkspaceDestination(it.name, if (it == DataPage.RECOVERY) t(DesktopUiText.RECOVERY_SUPPORT) else it.label,
            it == DataPage.UPDATE && updateNotice?.pendingVersion != null)
    }, page.name,
        enabled = !busy && !moving, onSelect = { selected = DataPage.valueOf(it) }) {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) {
            DesktopRetainedPane(page == DataPage.BACKUP) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (busy || backup.error != null || imported?.error != null) {
                        val area = if (operation == BackupPage.IMPORT) DesktopRecoveryArea.MERGE else DesktopRecoveryArea.BACKUP
                        StatusText(t(desktopRecoveryText(recovery.first { it.area == area })))
                    }
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
                        merge?.let { DesktopMergeImportPanel(it, showPasswordDialog = false, onExit = onExit) }
                    }
                    DesktopRetainedPane(operation == BackupPage.EXPORT || operation == BackupPage.CHECK) {
                        DesktopFullBackupPanel(full,
                            if (operation == BackupPage.CHECK) DesktopFullBackupView.CHECK else DesktopFullBackupView.EXPORT,
                            exportRequest, showPasswordDialog = false, onExit = onExit)
                    }
                }
            }
            DesktopRetainedPane(page == DataPage.LOCATION) { DataWorkspaceScroll {
                DesktopDataSectionHeading("数据目录")
                if (moving) StatusText(t(desktopRecoveryText(recovery.first { it.area == DesktopRecoveryArea.DIRECTORY })))
                DesktopDataRootPanel(root, onExit)
            } }
            if (page == DataPage.LAN) {
                if (lanSyncGroup != null) DesktopLanSyncEntry(lanSyncGroup, legacyLanSync, hasLanSyncGroupEvidence, lanSyncFilePicker)
                else lanSync?.let { DesktopLanSyncPanel(it) }
            }
            DesktopRetainedPane(page == DataPage.AUTO) { DataWorkspaceScroll { DesktopAutomaticBackupSettingsPanel(automatic) } }
            DesktopRetainedPane(page == DataPage.UPDATE) { DataWorkspaceScroll { DesktopUpdatePanel(onExit = onExit) } }
            DesktopRetainedPane(page == DataPage.DIAGNOSTICS) { diagnostics?.invoke() }
            DesktopRetainedPane(page == DataPage.RECOVERY) { DataWorkspaceScroll {
                DesktopRecoveryPanel(recovery, !busy && !moving, ::openRecoveryDestination)
            } }
            // Modal dimensions come from the bounded workspace, not an unmeasured retained/scrolling pane.
            if (page == DataPage.BACKUP && operation == BackupPage.CHECK) backup.passwordRequest?.let { request ->
                DesktopBackupPasswordDialog(request, full::retryFilePassword, full::discardInspection) { full.verifyFile(null) }
            }
            if (page == DataPage.BACKUP && operation == BackupPage.IMPORT) imported?.passwordRequest?.takeUnless { it.externalQueue }?.let { request ->
                merge?.let { controller ->
                    DesktopBackupPasswordDialog(request, controller::retryFilePassword, controller::discard, controller::selectFile)
                }
            }
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
