@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.desktop.backup.DesktopFullBackupSource
import com.example.chatbar.desktop.backup.DesktopBackupScratchCleanupException

/** No password or source path enters presentation state or task metadata. */
internal data class DesktopBackupPasswordRequest(val fileName: String, val error: String? = null,
    val externalQueue: Boolean = false)

internal fun Throwable.requestsBackupPasswordRetry(): Boolean = this is DesktopFullBackupSource.Refusal &&
    encrypted == true && stage == "AUTHENTICATION_ZIP" &&
    suppressed.none { it is DesktopBackupScratchCleanupException }

@Composable
internal fun DesktopBackupPasswordDialog(request: DesktopBackupPasswordRequest,
    onPassword: (CharArray) -> Unit, onCancel: () -> Unit, onChooseFile: (() -> Unit)?) {
    var password by remember(request) { mutableStateOf("") }
    DesktopModalSurface(kind = DesktopModalKind.BACKUP) {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 680.dp)
                .background(DesktopBootstrapColors.card).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopDataSectionHeading("输入存档密码") }
                    DesktopChatIconAction("取消密码输入", DesktopAppIcons.Close) { password = ""; onCancel() }
                }
                StatusText(request.fileName)
                StatusText("此备份已加密。输入密码后继续检查；不会提交合并或修改现有资料。")
                request.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                BackupPasswordField("存档密码", password, true) { password = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BootstrapButton("解锁并继续检查", enabled = password.length in 8..1024) {
                        val secret = password.toCharArray(); password = ""; onPassword(secret)
                    }
                    onChooseFile?.let { choose -> BootstrapButton("选择其他文件", secondary = true) { password = ""; choose() } }
                    BootstrapButton("取消", secondary = true) { password = ""; onCancel() }
                }
            }
        }
    }
}
