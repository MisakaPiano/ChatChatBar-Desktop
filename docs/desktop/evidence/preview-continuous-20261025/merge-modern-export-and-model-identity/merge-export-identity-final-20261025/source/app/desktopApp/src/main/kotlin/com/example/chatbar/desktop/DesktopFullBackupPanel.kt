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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DesktopDataSectionHeading("整应用备份与旧版文件检查")
        StatusText("备份角色、聊天、版本、记忆、图片、录音、存档和独立保全内容。恢复和手机迁移请使用上方合并入口。")
        StatusText("备份不含已配置的 API 密钥或受保护凭据。本机目录与凭据不因导出而改变。旧版文件检查仅用于核对。")
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
            StatusText("旧版检查不会恢复或替换数据。为避免把旧凭据绑定到不同模型账户，恢复统一使用上方的合并预览。")
            BootstrapButton("关闭检查", secondary = true, enabled = !state.busy) { controller.discardInspection() }
        }
        if (state.message.isNotBlank()) StatusText(state.message)
        state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        if (state.restartRequired) {
            StatusText("需要正常退出。新的数据操作已暂停，数据目录锁会在安全关闭后释放。", DesktopBootstrapColors.warning)
            BootstrapButton("正常退出应用", onClick = onExit)
        }
    }
}
