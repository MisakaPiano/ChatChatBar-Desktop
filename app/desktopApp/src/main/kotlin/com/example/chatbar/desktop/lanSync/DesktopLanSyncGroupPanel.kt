package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.lanSync.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.text.DateFormat
import java.util.Date

/** App-owned runtime survives page disposal; only transient management authority is locked here. */
@Composable
internal fun DesktopLanSyncGroupPanel(state: LanSyncGroupPanelState, actions: LanSyncGroupPanelActions) {
    DisposableEffect(actions) { onDispose { actions.lockManagement() } }
    var joinCode by remember(state.privacyRevision) { mutableStateOf("") }
    var recoveryInput by remember(state.privacyRevision) { mutableStateOf("") }
    var saved by remember(state.setupBinding, state.privacyRevision) { mutableStateOf(false) }
    fun copy(value: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DesktopDataSectionHeading("设备组同步")
        StatusText(state.status)
        state.error?.let { StatusText(it, DesktopBootstrapColors.warning) }
        if (state.stage == LanSyncGroupPanelStage.EMPTY) {
            StatusText(LanSyncScope.description)
            BootstrapButton("创建新设备组", enabled = !state.busy) { actions.createGroup() }
            key(state.privacyRevision) { LabeledField("加入已有设备组 · 粘贴邀请", joinCode, secret = true, enabled = !state.busy, onChange = { joinCode = it }) }
            BootstrapButton("检查并加入", secondary = true, enabled = !state.busy && joinCode.isNotBlank()) {
                val captured = joinCode; joinCode = ""; actions.beginJoin(captured)
            }
        }
        state.recoveryCode?.let { code ->
            StatusText("请保存这份恢复码。它用于管理设备组和丢失设备后的恢复。")
            Box(Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState())) { StatusText(code) }
            BootstrapButton("复制恢复码", secondary = true, enabled = !state.busy) { copy(code) }
            BootstrapButton(if (saved) "✓ 已妥善保存恢复码" else "我已妥善保存恢复码", secondary = true, enabled = !state.busy) { saved = !saved }
            state.setupBinding?.let { binding -> BootstrapButton("完成创建", enabled = !state.busy && saved) { actions.recoverySaved(binding) } }
        }
        if (state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED || state.stage == LanSyncGroupPanelStage.READY && !state.managementUnlocked) {
            key(state.privacyRevision) { LabeledField("恢复码 · 临时解锁管理", recoveryInput, secret = true, enabled = !state.busy, onChange = { recoveryInput = it }) }
            BootstrapButton("解锁管理", secondary = true, enabled = !state.busy && recoveryInput.isNotBlank()) {
                val captured = recoveryInput; recoveryInput = ""; actions.unlockManagement(captured)
            }
        }
        if (state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED)
            BootstrapButton("放弃未完成的设置", secondary = true, enabled = !state.busy) { actions.abandonUnusedSetup() }
        if (state.stage == LanSyncGroupPanelStage.JOINING)
            BootstrapButton("继续加入", enabled = !state.busy) { actions.retryJoin() }
        if (state.stage == LanSyncGroupPanelStage.READY) {
            groupRoute("本机", state.local, "最近保存"); groupRoute("附近设备", state.nearby, "最近送达"); groupRoute("网盘", state.cloud, "最近上传")
            ActionRow {
                BootstrapButton("立即检查", secondary = true, enabled = !state.busy) { actions.retry() }
                BootstrapButton("网盘设置", secondary = true, enabled = !state.busy) { actions.openCloudSettings() }
                BootstrapButton("邀请设备", enabled = !state.busy && state.managementUnlocked) { actions.createInvitation() }
                if (state.managementUnlocked) BootstrapButton("锁定管理", secondary = true) { actions.lockManagement() }
            }
            state.devices.forEach { device ->
                StatusText(device.name + if (device.thisDevice) " · 本机" else if (device.managementIdentity) " · 恢复管理身份" else "")
                StatusText(device.status)
                if (device.canRevoke) BootstrapButton("撤销此设备", secondary = true, enabled = !state.busy && state.managementUnlocked) { actions.prepareRevocation(device.id) }
            }
        }
        state.invitation?.let { offer ->
            val code = remember(offer) { offer.code() }
            val image = remember(code) { val matrix = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 384, 384)
                BufferedImage(384, 384, BufferedImage.TYPE_INT_RGB).apply {
                    for (y in 0 until 384) for (x in 0 until 384) setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xffffff)
                }.toComposeImageBitmap()
            }
            Image(image, "新设备扫描此邀请", Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f))
            StatusText("在新设备打开设备组同步，扫描此邀请。邀请短时有效；随后在这里确认设备身份。")
            ActionRow { BootstrapButton("复制邀请", secondary = true) { copy(code) }; BootstrapButton("关闭邀请", secondary = true) { actions.closeInvitation() } }
        }
        state.peerConfirmation?.let { peer ->
            StatusText("允许 ${peer.name} 加入设备组？")
            StatusText("设备身份：${peer.fingerprint}")
            BootstrapButton("确认加入此设备", enabled = !state.busy && state.managementUnlocked) { actions.acceptPeer(peer.binding) }
        }
        state.revocation?.let { review ->
            StatusText("撤销 ${review.name}？")
            StatusText("该设备将无法取得新的同步密钥，已保存的副本仍会保留。网盘账号授权需要单独撤销。")
            ActionRow {
                BootstrapButton("确认撤销", enabled = !state.busy && state.managementUnlocked) { actions.confirmRevocation(review.binding) }
                BootstrapButton("取消", secondary = true, enabled = !state.busy) { actions.cancelRevocation() }
            }
        }
    }
}

@Composable private fun groupRoute(name: String, status: LanSyncGroupRouteStatus, observation: String) {
    StatusText("$name · ${status.text}")
    status.lastSuccessAt?.let { StatusText("$observation：${DateFormat.getDateTimeInstance().format(Date(it))}") }
}
