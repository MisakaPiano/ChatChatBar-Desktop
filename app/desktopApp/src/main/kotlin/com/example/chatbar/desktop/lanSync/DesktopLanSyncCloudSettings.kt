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
import com.example.chatbar.domain.lanSync.*
import java.text.DateFormat
import java.util.Date

/** Connection drafts remain in this modal only; saved passwords are never loaded into fields. */
@Composable
internal fun DesktopLanSyncCloudSettings(state: LanSyncGroupCloudSettingsState, actions: LanSyncGroupCloudSettingsActions) {
    if (!state.visible) return
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var draftRevision by remember { mutableStateOf(0) }
    var remove by remember { mutableStateOf<LanSyncGroupCloudRoute?>(null) }
    var page by remember(state.rows.map { it.id }) { mutableStateOf(0) }
    fun clearDraft() { name = ""; endpoint = ""; username = ""; password = ""; draftRevision++ }
    fun close() { clearDraft(); remove = null; actions.closeCloudSettings() }
    DisposableEffect(Unit) { onDispose { clearDraft() } }
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 780.dp).fillMaxWidth().fillMaxHeight(.94f)
                .background(DesktopBootstrapColors.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DesktopDataSectionHeading("网盘连接")
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusText("支持提供 WebDAV 地址的网盘。连接设置保存在此设备的受保护目录中，新连接默认停止，不代表资料已上传。")
                    if (!state.canEnable) StatusText("目前可保存连接设置，暂不可启用。")
                    state.notice?.let { StatusText(it) }
                    state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                    if (state.busy) StatusText("正在处理连接设置…")
                    DesktopDataSectionHeading("已保存的连接")
                    if (state.rows.isEmpty()) StatusText("尚未添加网盘连接。")
                    state.rows.drop(page * 10).take(10).forEach { row -> key(row.id) {
                        var details by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().background(DesktopBootstrapColors.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DesktopDataSectionHeading(row.name)
                            StatusText(row.endpoint)
                            StatusText(if (row.username.isBlank()) "未填写用户名" else "用户名：${row.username}")
                            StatusText(if (row.enabled) "此连接已启用" else "此连接已停止")
                            val progress = state.progress[row.id]
                            if (progress == null) StatusText("尚未取得此连接的送达记录。")
                            else {
                                StatusText(progress.text)
                                progress.lastObservedAt?.let { StatusText("最近核对：${DateFormat.getDateTimeInstance().format(Date(it))}") }
                                progress.pendingOperations?.let { StatusText("待处理：$it 项") }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BootstrapButton(if (row.enabled) "停止此连接" else "启用此连接", secondary = true,
                                    enabled = !state.busy && (row.enabled || state.canEnable)) { actions.setCloudRouteEnabled(row.id, !row.enabled) }
                                BootstrapButton("移除", secondary = true, enabled = !state.busy) { remove = row }
                                BootstrapButton(if (details) "收起详情" else "连接详情", secondary = true) { details = !details }
                            }
                            if (details) StatusText("连接标识：${row.id.value}")
                        }
                    } }
                    if (state.rows.size > 10) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BootstrapButton("上一页", secondary = true, enabled = page > 0) { page-- }
                        StatusText("${page + 1}/${(state.rows.size + 9) / 10}")
                        BootstrapButton("下一页", secondary = true, enabled = (page + 1) * 10 < state.rows.size) { page++ }
                    }
                    remove?.let { selected ->
                        DesktopDataSectionHeading("移除 ${selected.name} 的本机连接设置？")
                        StatusText("远端文件、本机原件和待传队列都会保留。")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BootstrapButton("确认移除本机设置", variant = DesktopActionVariant.DESTRUCTIVE, enabled = !state.busy) {
                                remove = null; actions.removeCloudRoute(selected.id)
                            }
                            BootstrapButton("取消", secondary = true, enabled = !state.busy) { remove = null }
                        }
                    }
                    DesktopDataSectionHeading("添加新连接")
                    StatusText("如需修改地址或账号，请添加新连接。已保存的密码不会回填。")
                    key(draftRevision) {
                        LabeledField("连接名称", name, enabled = !state.busy) { name = it }
                        LabeledField("WebDAV 目录地址（HTTPS）", endpoint, enabled = !state.busy) { endpoint = it }
                        LabeledField("用户名", username, enabled = !state.busy) { username = it }
                        LabeledField("密码或专用密码", password, secret = true, enabled = !state.busy) { password = it }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BootstrapButton("保存为停止的连接", enabled = !state.busy && name.isNotBlank() && endpoint.isNotBlank()) {
                            val secret = password.toByteArray(Charsets.UTF_8)
                            try {
                                val draft = LanSyncGroupCloudDraft(name, endpoint.trim(), username, secret, enabled = false)
                                clearDraft()
                                actions.saveCloudRoute(draft)
                            } finally { secret.fill(0) }
                        }
                        BootstrapButton("清空输入", secondary = true, enabled = !state.busy) { clearDraft() }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BootstrapButton("刷新", secondary = true, enabled = !state.busy) { actions.refreshCloudSettings() }
                    BootstrapButton("关闭", secondary = true) { close() }
                }
            }
        }
    }
}
