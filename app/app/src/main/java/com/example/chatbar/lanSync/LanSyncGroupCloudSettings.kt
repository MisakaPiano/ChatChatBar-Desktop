@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.chatbar.lanSync

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.ui.kit.*
import java.text.DateFormat
import java.util.Date

/** Local, non-saveable connection form over required application callbacks. */
@Composable
internal fun LanSyncGroupCloudSettings(state: LanSyncGroupCloudSettingsState, actions: LanSyncGroupCloudSettingsActions) {
    if (!state.visible) return
    val context = LocalContext.current
    val name = remember { TextFieldState() }
    val endpoint = remember { TextFieldState() }
    val username = remember { TextFieldState() }
    var password by remember { mutableStateOf("") }
    var secretRevision by remember { mutableStateOf(0) }
    var remove by remember { mutableStateOf<LanSyncGroupCloudRoute?>(null) }
    var page by remember(state.rows.map { it.id }) { mutableStateOf(0) }
    fun clearDraft() {
        name.edit { replace(0, length, "") }
        endpoint.edit { replace(0, length, "") }
        username.edit { replace(0, length, "") }
        password = ""; secretRevision++
    }
    fun close() { clearDraft(); remove = null; actions.closeCloudSettings() }
    DisposableEffect(context) {
        val window = cloudSettingsActivity(context)?.window
        val secureBefore = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { clearDraft(); if (!secureBefore) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    CbDialog(onDismissRequest = ::close, title = "网盘连接", dismissOnClickOutside = false,
        confirm = { CbButton("关闭", ::close, variant = ButtonVariant.Secondary) },
        dismiss = { CbButton("刷新", actions::refreshCloudSettings, enabled = !state.busy, variant = ButtonVariant.Ghost) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbText("支持提供 WebDAV 地址的网盘。连接设置保存在此设备的受保护目录中，新连接默认停止，不代表资料已上传。", color = ChatBarTheme.colors.mutedForeground)
            if (!state.canEnable) CbText("目前可保存连接设置，暂不可启用。", color = ChatBarTheme.colors.mutedForeground)
            state.notice?.let { CbText(it) }
            state.error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
            if (state.busy) CbText("正在处理连接设置…")
            CbText("已保存的连接", style = ChatBarTheme.typography.heading)
            if (state.rows.isEmpty()) CbText("尚未添加网盘连接。", color = ChatBarTheme.colors.mutedForeground)
            state.rows.drop(page * 10).take(10).forEach { row -> key(row.id) {
                var details by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CbText(row.name, style = ChatBarTheme.typography.heading)
                    CbText(row.endpoint)
                    CbText(if (row.username.isBlank()) "未填写用户名" else "用户名：${row.username}")
                    CbText(if (row.enabled) "此连接已启用" else "此连接已停止")
                    val progress = state.progress[row.id]
                    if (progress == null) CbText("尚未取得此连接的送达记录。", color = ChatBarTheme.colors.mutedForeground)
                    else {
                        CbText(progress.text)
                        progress.lastObservedAt?.let { CbText("最近核对：${DateFormat.getDateTimeInstance().format(Date(it))}") }
                        progress.pendingOperations?.let { CbText("待处理：$it 项") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CbButton(if (row.enabled) "停止此连接" else "启用此连接", { actions.setCloudRouteEnabled(row.id, !row.enabled) },
                            enabled = !state.busy && (row.enabled || state.canEnable), variant = ButtonVariant.Secondary)
                        CbButton("移除", { remove = row }, enabled = !state.busy, variant = ButtonVariant.Ghost)
                        CbButton(if (details) "收起详情" else "连接详情", { details = !details }, variant = ButtonVariant.Ghost)
                    }
                    if (details) CbText("连接标识：${row.id.value}", color = ChatBarTheme.colors.mutedForeground)
                }
            } }
            if (state.rows.size > 10) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CbButton("上一页", { page-- }, enabled = page > 0, variant = ButtonVariant.Ghost)
                CbText("${page + 1}/${(state.rows.size + 9) / 10}")
                CbButton("下一页", { page++ }, enabled = (page + 1) * 10 < state.rows.size, variant = ButtonVariant.Ghost)
            }
            remove?.let { selected ->
                CbText("移除 ${selected.name} 的本机连接设置？", style = ChatBarTheme.typography.heading)
                CbText("远端文件、本机原件和待传队列都会保留。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CbButton("确认移除本机设置", { remove = null; actions.removeCloudRoute(selected.id) }, enabled = !state.busy, variant = ButtonVariant.Destructive)
                    CbButton("取消", { remove = null }, enabled = !state.busy, variant = ButtonVariant.Ghost)
                }
            }
            CbText("添加新连接", style = ChatBarTheme.typography.heading)
            CbText("如需修改地址或账号，请添加新连接。已保存的密码不会回填。", color = ChatBarTheme.colors.mutedForeground)
            CbField("连接名称") { CbInput(name, enabled = !state.busy, singleLine = true) }
            CbField("WebDAV 目录地址（HTTPS）") { CbInput(endpoint, enabled = !state.busy, singleLine = true, placeholder = "https://…/") }
            CbField("用户名") { CbInput(username, enabled = !state.busy, singleLine = true) }
            key(secretRevision) { CbField("密码或专用密码") { CbInput(password, { password = it }, enabled = !state.busy, secure = true) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CbButton("保存为停止的连接", {
                    val secret = password.toByteArray(Charsets.UTF_8)
                    try {
                        val draft = LanSyncGroupCloudDraft(name.text.toString(), endpoint.text.toString().trim(), username.text.toString(), secret, enabled = false)
                        clearDraft()
                        actions.saveCloudRoute(draft)
                    } finally { secret.fill(0) }
                }, enabled = !state.busy && name.text.isNotBlank() && endpoint.text.isNotBlank())
                CbButton("清空输入", ::clearDraft, enabled = !state.busy, variant = ButtonVariant.Ghost)
            }
        }
    }
}

private fun cloudSettingsActivity(context: Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}
