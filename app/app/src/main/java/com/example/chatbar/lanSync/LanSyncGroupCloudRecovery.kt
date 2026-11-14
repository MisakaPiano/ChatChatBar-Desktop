package com.example.chatbar.lanSync

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.ui.kit.*

/** Temporary credentials and code belong only to this visible recovery form. */
@Composable
internal fun LanSyncGroupCloudRecovery(state: LanSyncGroupCloudRecoveryState, privacyRevision: Long,
    onPrepare: (LanSyncGroupCloudDraft, String) -> Unit, onConfirm: (String) -> Unit, onClose: () -> Unit) {
    if (!state.visible) return
    val context = LocalContext.current
    var endpoint by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var secretRevision by remember { mutableStateOf(0) }
    var confirmed by remember(state.review?.binding) { mutableStateOf(false) }
    val closeOwner by rememberUpdatedState(onClose)
    fun clearSecrets() { password = ""; code = ""; secretRevision++; confirmed = false }
    fun close() { clearSecrets(); endpoint = ""; username = ""; closeOwner() }
    LaunchedEffect(state.review?.binding) { clearSecrets() }
    DisposableEffect(context, privacyRevision) {
        val window = cloudRecoveryActivity(context)?.window
        val originallySecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            clearSecrets(); endpoint = ""; username = ""; closeOwner()
            if (!originallySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    CbDialog(onDismissRequest = ::close, title = "从网盘恢复设备组", dismissOnClickOutside = false,
        confirm = { CbButton(if (state.busy) "取消并关闭" else "关闭", ::close, variant = ButtonVariant.Secondary) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbText("原设备不在线时，可从已保存设备组密文的 WebDAV 目录恢复原设备组，并把此设备登记为同组的新日常设备。", color = ChatBarTheme.colors.mutedForeground)
            CbText("只能核对网盘当前可取得的已签名历史，不能保证取得原设备组的最新状态。准备阶段只读取和核对，不签署新的组管理操作。", color = ChatBarTheme.colors.warning)
            if (state.status.isNotBlank()) CbText(state.status)
            state.error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
            val review = state.review
            if (review != null && !state.complete) {
                CbText("核对原设备组与此设备", style = ChatBarTheme.typography.heading)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CbText("原设备组：${review.group}")
                        CbText("原管理身份：${review.rootFingerprint}")
                        CbText("已核对的组检查点：${review.checkpoint}")
                        CbText("已核对的设备成员数：${review.memberCount}")
                        CbText("新日常设备：${review.name}")
                        CbText("新设备身份：${review.device}")
                    }
                }
                CbText("确认后会在原设备组中登记此设备并继续恢复资料。资料中的冲突、删除或缺失依赖仍按实际状态处理，云端送达另行核对。")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CbCheckbox(confirmed, { confirmed = it }, enabled = !state.busy,
                        contentDescription = "已核对原设备组和新设备身份，并同意登记此设备")
                    CbText("我已核对以上身份，并同意登记此设备", modifier = Modifier.weight(1f))
                }
                CbButton("登记此设备并恢复资料", {
                    confirmed = false
                    onConfirm(review.binding)
                }, enabled = confirmed && !state.busy)
            } else if (!state.complete) {
                CbText("此处输入仅用于本次恢复，密码不会回填；不会自动启用日常网盘传输。", color = ChatBarTheme.colors.mutedForeground)
                CbField("WebDAV 目录地址（HTTPS）") {
                    CbInput(endpoint, { endpoint = it }, enabled = !state.busy, singleLine = true, placeholder = "https://…/")
                }
                CbField("用户名") { CbInput(username, { username = it }, enabled = !state.busy, singleLine = true) }
                key(secretRevision) {
                    CbField("密码或专用密码") { CbInput(password, { password = it }, enabled = !state.busy, secure = true) }
                    CbField("设备组恢复码") { CbInput(code, { code = it }, enabled = !state.busy, secure = true) }
                }
                CbButton("读取并核对设备组", {
                    val secret = password.toByteArray(Charsets.UTF_8)
                    val capturedCode = code
                    val draft = LanSyncGroupCloudDraft("设备组恢复", endpoint.trim(), username, secret, enabled = false)
                    clearSecrets()
                    try { onPrepare(draft, capturedCode) } finally { secret.fill(0) }
                }, enabled = !state.busy && endpoint.isNotBlank() && code.isNotBlank())
            }
        }
    }
}

private fun cloudRecoveryActivity(context: Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}
