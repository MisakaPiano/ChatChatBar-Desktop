package com.example.chatbar.lanSync

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.ui.kit.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.text.DateFormat
import java.util.Date

/** Pure screen over real owner actions. Leaving it locks management without stopping app sync. */
@Composable
internal fun LanSyncGroupPanel(state: LanSyncGroupPanelState, actions: LanSyncGroupPanelActions, scanInvitation: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(actions, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) actions.lockManagement() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); actions.lockManagement() }
    }
    val secretVisible = state.recoveryCode != null || state.invitation != null
    DisposableEffect(context, secretVisible) {
        val window = groupActivity(context)?.window
        val originallySecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (secretVisible) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secretVisible && !originallySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var joinCode by remember(state.privacyRevision) { mutableStateOf("") }
    var recoveryInput by remember(state.privacyRevision) { mutableStateOf("") }
    var saved by remember(state.setupBinding, state.privacyRevision) { mutableStateOf(false) }
    var copyFailed by remember(state.privacyRevision) { mutableStateOf(false) }
    fun copy(value: String) {
        copyFailed = try {
            val clip = ClipData.newPlainText("设备组", value).apply {
                description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            false
        } catch (_: Exception) {
            true
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CbText("设备组同步", style = ChatBarTheme.typography.title)
        CbText(state.status)
        state.error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
        if (copyFailed) CbText("未能复制，请重试。", color = ChatBarTheme.colors.destructive)
        if (state.stage == LanSyncGroupPanelStage.EMPTY) {
            CbText(LanSyncScope.description)
            CbButton("创建新设备组", actions::createGroup, enabled = !state.busy)
        }
        if (state.stage == LanSyncGroupPanelStage.EMPTY || state.stage == LanSyncGroupPanelStage.JOINING) {
            val joining = state.stage == LanSyncGroupPanelStage.JOINING
            CbButton(if (joining) "扫描更新邀请" else "扫描设备组邀请", scanInvitation, enabled = !state.busy, variant = ButtonVariant.Secondary)
            CbField(if (joining) "更新邀请 · 粘贴新邀请" else "加入已有设备组 · 粘贴邀请") {
                key(state.privacyRevision) { CbInput(joinCode, { joinCode = it }, secure = true, enabled = !state.busy) }
            }
            CbButton(if (joining) "更新邀请并继续" else "检查并加入", { val captured = joinCode; joinCode = ""; actions.beginJoin(captured) },
                enabled = !state.busy && joinCode.isNotBlank(), variant = ButtonVariant.Secondary)
        }
        state.recoveryCode?.let { code ->
            CbText("请保存恢复码。丢失设备后，还需要另一台已加入设备或对应的加密管理资料；仅有恢复码无法恢复。")
            Box(Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState())) { CbText(code) }
            CbButton("复制恢复码", { copy(code) }, enabled = !state.busy, variant = ButtonVariant.Secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbCheckbox(saved, { saved = it }, enabled = !state.busy, contentDescription = "已妥善保存恢复码")
                CbText("我已妥善保存恢复码")
            }
            state.setupBinding?.let { binding -> CbButton("完成创建", { actions.recoverySaved(binding) }, enabled = !state.busy && saved) }
        }
        if (state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED || state.stage == LanSyncGroupPanelStage.READY && !state.managementUnlocked) {
            CbField("恢复码 · 临时解锁管理") {
                key(state.privacyRevision) { CbInput(recoveryInput, { recoveryInput = it }, secure = true, enabled = !state.busy) }
            }
            CbButton("解锁管理", { val captured = recoveryInput; recoveryInput = ""; actions.unlockManagement(captured) },
                enabled = !state.busy && recoveryInput.isNotBlank(), variant = ButtonVariant.Secondary)
        }
        if (state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED)
            CbButton("放弃未完成的设置", actions::abandonUnusedSetup, enabled = !state.busy, variant = ButtonVariant.Secondary)
        if (state.stage == LanSyncGroupPanelStage.JOINING) {
            CbButton("继续加入", actions::retryJoin, enabled = !state.busy)
            CbButton("重新核对本机资料并继续", actions::refreshJoinPreparation, enabled = !state.busy, variant = ButtonVariant.Secondary)
        }
        if (state.stage == LanSyncGroupPanelStage.READY) {
            GroupRoute("本机", state.local, "最近保存"); GroupRoute("附近设备", state.nearby, "最近送达"); GroupRoute("网盘", state.cloud, "最近上传")
            CbButton("立即检查", actions::retry, enabled = !state.busy, variant = ButtonVariant.Secondary)
            CbButton("网盘设置", actions::openCloudSettings, enabled = !state.busy, variant = ButtonVariant.Secondary)
            CbButton("邀请设备", actions::createInvitation, enabled = !state.busy && state.managementUnlocked)
            if (state.managementUnlocked) CbButton("锁定管理", actions::lockManagement, variant = ButtonVariant.Secondary)
            state.devices.forEach { device ->
                CbText(device.name + if (device.thisDevice) " · 本机" else if (device.managementIdentity) " · 恢复管理身份" else "")
                CbText(device.status)
                if (device.canRevoke) CbButton("撤销此设备", { actions.prepareRevocation(device.id) },
                    enabled = !state.busy && state.managementUnlocked, variant = ButtonVariant.Destructive)
            }
        }
        state.invitation?.let { offer ->
            val code = remember(offer) { offer.code() }
            val bitmap = remember(code) { val matrix = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 384, 384)
                Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).apply {
                    for (y in 0 until 384) for (x in 0 until 384) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }.asImageBitmap()
            }
            Image(bitmap, "新设备扫描此邀请", Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f))
            CbText("在新设备打开设备组同步，扫描此邀请。邀请短时有效；随后在这里确认设备身份。")
            CbButton("复制邀请", { copy(code) }, variant = ButtonVariant.Secondary)
            CbButton("关闭邀请", actions::closeInvitation, variant = ButtonVariant.Secondary)
        }
        state.peerConfirmation?.let { peer ->
            CbText("允许 ${peer.name} 加入设备组？"); CbText("设备身份：${peer.fingerprint}")
            CbButton("确认加入此设备", { actions.acceptPeer(peer.binding) }, enabled = !state.busy && state.managementUnlocked)
        }
        state.revocation?.let { review ->
            CbText("撤销 ${review.name}？")
            CbText("该设备将无法取得新的同步密钥，已保存的副本仍会保留。网盘账号授权需要单独撤销。")
            CbButton("确认撤销", { actions.confirmRevocation(review.binding) }, enabled = !state.busy && state.managementUnlocked, variant = ButtonVariant.Destructive)
            CbButton("取消", actions::cancelRevocation, enabled = !state.busy, variant = ButtonVariant.Secondary)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun GroupRoute(name: String, status: LanSyncGroupRouteStatus, observation: String) {
    CbText("$name · ${status.text}")
    status.lastSuccessAt?.let { CbText("$observation：${DateFormat.getDateTimeInstance().format(Date(it))}") }
}
private fun groupActivity(context: Context): Activity? {
    var current = context
    repeat(12) {
        if (current is Activity) return current as Activity
        val next = (current as? ContextWrapper)?.baseContext ?: return null
        if (next === current) return null
        current = next
    }
    return null
}
