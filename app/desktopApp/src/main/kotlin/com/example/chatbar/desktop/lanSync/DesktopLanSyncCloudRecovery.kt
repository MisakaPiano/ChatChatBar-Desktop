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

/** Temporary recovery inputs are discarded when this modal closes or management privacy changes. */
@Composable
internal fun DesktopLanSyncCloudRecovery(state: LanSyncGroupCloudRecoveryState, privacyRevision: Long,
    onPrepare: (LanSyncGroupCloudDraft, String) -> Unit, onConfirm: (String, Boolean) -> Unit,
    onClose: () -> Unit, onPrivacyHide: () -> Unit) {
    if (!state.visible) return
    var endpoint by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var secretRevision by remember { mutableStateOf(0) }
    var confirmed by remember(state.review?.binding) { mutableStateOf(false) }
    var saveConnection by remember(state.review?.binding) { mutableStateOf(true) }
    val closeOwner by rememberUpdatedState(onClose)
    val hideOwner by rememberUpdatedState(onPrivacyHide)
    fun clearSecrets() { password = ""; code = ""; secretRevision++; confirmed = false }
    fun close() { clearSecrets(); endpoint = ""; username = ""; closeOwner() }
    LaunchedEffect(state.review?.binding) { clearSecrets() }
    DisposableEffect(privacyRevision) {
        onDispose { clearSecrets(); endpoint = ""; username = ""; hideOwner() }
    }
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 780.dp).fillMaxWidth().fillMaxHeight(.94f)
                .background(DesktopBootstrapColors.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DesktopDataSectionHeading("从网盘恢复设备组")
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusText("原设备不在线时，可从已保存设备组密文的 WebDAV 目录恢复原设备组，并把此设备登记为同组的新日常设备。")
                    StatusText("只能核对网盘当前可取得的已签名历史，不能保证取得原设备组的最新状态。准备阶段只读取和核对，不签署新的组管理操作。", DesktopBootstrapColors.warning)
                    if (state.status.isNotBlank()) StatusText(state.status)
                    state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                    state.connectionNotice?.let { StatusText(it, if (state.connectionEnabled == false) DesktopBootstrapColors.warning else DesktopBootstrapColors.mutedForeground) }
                    val review = state.review
                    if (review != null && !state.complete) {
                        DesktopDataSectionHeading("核对原设备组与此设备")
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusText("原设备组：${review.group}")
                                StatusText("原管理身份：${review.rootFingerprint}")
                                StatusText("已核对的组检查点：${review.checkpoint}")
                                StatusText("已核对的设备成员数：${review.memberCount}")
                                StatusText("新日常设备：${review.name}")
                                StatusText("新设备身份：${review.device}")
                            }
                        }
                        StatusText("确认后会在原设备组中登记此设备并继续恢复资料。资料中的冲突、删除或缺失依赖仍按实际状态处理，云端送达另行核对。")
                        Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                            .toggleable(value = saveConnection, enabled = !state.busy, role = Role.Checkbox, onValueChange = { saveConnection = it })
                            .padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusText(if (saveConnection) "✓" else "□")
                            StatusText("恢复后保存此连接并继续自动同步")
                        }
                        StatusText("开启后，本机新内容会加密上传到此目录。关闭此选项则仅恢复资料，本次密码随后清除。")
                        Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                            .toggleable(value = confirmed, enabled = !state.busy, role = Role.Checkbox, onValueChange = { confirmed = it })
                            .padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusText(if (confirmed) "✓" else "□")
                            StatusText("我已核对以上身份，并同意登记此设备")
                        }
                        BootstrapButton("登记此设备并恢复资料", enabled = confirmed && !state.busy) {
                            confirmed = false
                            onConfirm(review.binding, saveConnection)
                        }
                    } else if (!state.complete) {
                        StatusText("输入在恢复过程中临时保留；是否在本机保存此连接并继续同步，由确认页的选项决定。")
                        LabeledField("WebDAV 目录地址（HTTPS）", endpoint, enabled = !state.busy) { endpoint = it }
                        LabeledField("用户名", username, enabled = !state.busy) { username = it }
                        key(secretRevision) {
                            LabeledField("密码或专用密码", password, secret = true, enabled = !state.busy) { password = it }
                            LabeledField("设备组恢复码", code, secret = true, enabled = !state.busy) { code = it }
                        }
                        BootstrapButton("读取并核对设备组", enabled = !state.busy && endpoint.isNotBlank() && code.isNotBlank()) {
                            val secret = password.toByteArray(Charsets.UTF_8)
                            val capturedCode = code
                            val draft = LanSyncGroupCloudDraft("设备组恢复", endpoint.trim(), username, secret, enabled = false)
                            clearSecrets()
                            try { onPrepare(draft, capturedCode) } finally { secret.fill(0) }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BootstrapButton(if (state.busy) "取消并关闭" else "关闭", secondary = true) { close() }
                }
            }
        }
    }
}
