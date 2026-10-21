package com.example.chatbar.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopFullBackupPanel(controller: DesktopFullBackupController, onExit: () -> Unit) {
    DisposableEffect(controller) { onDispose { controller.leavePanel() } }
    val state by controller.state.collectAsState()
    var password by remember { mutableStateOf("") }
    var confirmation by remember(state.summary) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DesktopDataSectionHeading("整应用备份与恢复")
        StatusText("备份角色、聊天、版本、记忆、图片、录音和存档。恢复会替换当前数据目录内的这些内容，不合并聊天。")
        StatusText("本机数据目录设置和受保护凭据保持原样。备份不含密钥；含凭据的外来备份会拒绝导入。")
        StatusText("包含 Desktop 专有数据时仅支持 Desktop 恢复。Android 双向迁移尚需独立设备验收，不保证所有历史或未来格式可用。")
        if (!state.restartRequired) {
            StatusText("可选备份密码（加密导出至少 8 个字符；检查加密文件时输入原密码）")
            BasicTextField(password, { password = it.take(1024) }, Modifier.fillMaxWidth()
                .border(1.dp, DesktopBootstrapColors.border).padding(10.dp),
                enabled = !state.busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
            ActionRow {
                BootstrapButton("导出整应用备份", enabled = !state.busy && (password.isEmpty() || password.length >= 8)) {
                    val secret = password.takeIf { it.isNotEmpty() }?.toCharArray(); password = ""; controller.export(secret)
                }
                BootstrapButton("选择备份并检查…", secondary = true, enabled = !state.busy) {
                    val secret = password.takeIf { it.isNotEmpty() }?.toCharArray(); password = ""; controller.inspect(secret)
                }
                if (state.busy) BootstrapButton("停止", secondary = true) { controller.stop() }
            }
        }
        state.summary?.let { summary ->
            StatusText("已校验 ${summary.fileCount} 个文件 · ${summary.totalBytes / 1024} KiB · 来源版本 ${summary.appVersionName}")
            if (state.desktopOnly) StatusText("该备份含 Desktop 专有数据，不能据此宣称 Android 兼容。", DesktopBootstrapColors.warning)
            StatusText("确认后会停止音频并准备整体替换，随后需要正常退出。原数据保留在恢复记录中；不会自动重启。")
            ActionRow {
                BootstrapButton(if (confirmation) "确认替换并准备重启" else "准备恢复…", enabled = !state.busy) {
                    if (confirmation) { confirmation = false; controller.restore() } else confirmation = true
                }
                BootstrapButton("取消恢复", secondary = true, enabled = !state.busy) { controller.discardInspection() }
            }
        }
        if (state.message.isNotBlank()) StatusText(state.message)
        state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        if (state.restartRequired) {
            StatusText("需要正常退出。新的数据操作已暂停，数据目录锁会在安全关闭后释放。", DesktopBootstrapColors.warning)
            BootstrapButton("正常退出应用", onClick = onExit)
        }
    }
}
