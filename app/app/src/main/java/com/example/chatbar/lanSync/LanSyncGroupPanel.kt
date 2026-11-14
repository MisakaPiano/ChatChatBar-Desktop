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
import androidx.compose.foundation.text.selection.SelectionContainer
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
internal fun LanSyncGroupPanel(state: LanSyncGroupPanelState, actions: LanSyncGroupPanelActions,
    view: LanSyncGroupNodeView? = null, scanInvitation: () -> Unit) {
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
        if (state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED || state.stage == LanSyncGroupPanelStage.READY && state.localManagementAvailable && !state.managementUnlocked) {
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
            if (!state.localManagementAvailable) CbText("此设备可日常同步；管理恢复还需要对应的加密管理资料。")
            GroupRoute("本机", state.local, "最近保存"); GroupRoute("附近设备", state.nearby, "最近送达"); GroupRoute("网盘", state.cloud, "最近上传")
            CbButton("立即检查", actions::retry, enabled = !state.busy, variant = ButtonVariant.Secondary)
            CbButton("网盘设置", actions::openCloudSettings, enabled = !state.busy, variant = ButtonVariant.Secondary)
            CbButton("邀请设备", actions::createInvitation, enabled = !state.busy && state.managementUnlocked)
            if (state.managementUnlocked) {
                CbButton("锁定管理", actions::lockManagement, variant = ButtonVariant.Secondary)
                CbText("仅接续先前已确认的加入或撤销，不会重新决定设备成员。")
                CbButton("接续已确认的管理操作", actions::resumeManagementOperation, enabled = !state.busy, variant = ButtonVariant.Secondary)
            }
            state.devices.forEach { device ->
                CbText(device.name + if (device.thisDevice) " · 本机" else if (device.managementIdentity) " · 恢复管理身份" else "")
                CbText(device.status)
                if (device.canRevoke) CbButton("撤销此设备", { actions.prepareRevocation(device.id) },
                    enabled = !state.busy && state.managementUnlocked, variant = ButtonVariant.Destructive)
            }
        }
        GroupFrozenReviewBrowser(view)
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

/** A selected inspection stays frozen while the application continues synchronizing. */
@Composable
private fun GroupFrozenReviewBrowser(view: LanSyncGroupNodeView?) {
    var selected by remember { mutableStateOf<Pair<LanSyncGroupNodeView, String>?>(null) }
    val units = remember(view) {
        val plan = view?.plan
        plan?.units?.values?.filter { it.stage != LanSyncGroupNativeStage.UNCHANGED ||
            it.unit in view?.reservedUnits.orEmpty() || it.unit in view?.incomingReview?.reductions.orEmpty() }
            ?.map { it.unit }?.sorted().orEmpty()
    }
    var unitPage by remember(units) { mutableStateOf(0) }
    if (units.isNotEmpty() || selected != null) {
        CbText("冻结内容详情", style = ChatBarTheme.typography.title)
        CbText("可浏览本次检查取得的完整字段。本页只供核对，待确认的内容会保留当前状态。")
        units.drop(unitPage * 10).take(10).forEach { unit ->
            val row = view?.plan?.units?.get(unit)
            CbButton("查看 $unit · ${row?.stage?.let(::groupFrozenStage).orEmpty()}",
                { view?.let { selected = it to unit } }, variant = ButtonVariant.Secondary)
        }
        if (units.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CbButton("上一页内容组", { unitPage-- }, enabled = unitPage > 0, variant = ButtonVariant.Secondary)
            CbText("${unitPage + 1}/${(units.size + 9) / 10}")
            CbButton("下一页内容组", { unitPage++ }, enabled = (unitPage + 1) * 10 < units.size, variant = ButtonVariant.Secondary)
        }
    }
    val frozen = selected ?: return
    val plan = frozen.first.plan ?: return
    val unit = frozen.second
    val row = plan.units[unit] ?: return
    CbText("已冻结：$unit")
    CbText("同步继续运行；这里保留所选检查结果，重新选择内容组可查看新的结果。此处可查看附件引用，暂不提供图片预览。")
    CbButton("关闭冻结详情", { selected = null }, variant = ButtonVariant.Secondary)
    val current = remember(plan, unit) { plan.before.units()[unit].orEmpty() }
    key(frozen.first, unit) {
        GroupFrozenRecords("本次检查时的本机内容", unit, current)
        row.frontiers.sortedBy { it.version.id.value }.forEach { frontier ->
            key(frontier.version) {
                CbText("已签名版本：${frontier.version.id.value}")
                val incoming = row.incoming[frontier.version]
                when {
                    frontier.change.content == null -> CbText("此版本为删除标记，没有正文记录。")
                    incoming != null -> GroupFrozenRecords("已取得并验证的传入版本", unit, incoming.decoded.payload.records)
                    else -> CbText("此版本的正文或附件尚未完整取得，暂不能显示完整内容。")
                }
            }
        }
        frozen.first.incomingReview?.reductions?.get(unit)?.let { reduction ->
            GroupFrozenRecords("待确认的传入目标", unit, reduction.proposed)
        }
    }
}

@Composable
private fun GroupFrozenRecords(label: String, unit: String, records: List<LanSyncRecord>) {
    var expanded by remember(records, label) { mutableStateOf(false) }
    CbButton((if (expanded) "收起" else "展开") + "$label · ${records.size} 条记录",
        { expanded = !expanded }, variant = ButtonVariant.Secondary)
    if (!expanded) return
    val detail = remember(records, unit) { LanSyncDetailedReview.detail(unit, records, emptyList()) }
    val keys = detail.recordKeys
    if (keys.isEmpty()) { CbText("此内容没有记录。"); return }
    var recordPage by remember(detail) { mutableStateOf(0) }
    var selectedKey by remember(detail) { mutableStateOf<String?>(null) }
    keys.drop(recordPage * 10).take(10).forEach { recordKey ->
        CbButton(recordKey, { selectedKey = recordKey }, variant = ButtonVariant.Secondary)
    }
    if (keys.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页记录", { recordPage-- }, enabled = recordPage > 0, variant = ButtonVariant.Secondary)
        CbText("${recordPage + 1}/${(keys.size + 9) / 10}")
        CbButton("下一页记录", { recordPage++ }, enabled = (recordPage + 1) * 10 < keys.size, variant = ButtonVariant.Secondary)
    }
    val recordKey = selectedKey ?: return
    val fields = detail.fields(recordKey)
    var fieldIndex by remember(detail, recordKey) { mutableStateOf(0) }
    var bodyPage by remember(detail, recordKey, fieldIndex) { mutableStateOf(0) }
    CbText("所选记录：$recordKey")
    if (fields.isEmpty()) { CbText("此记录没有字段。"); return }
    val field = fields[fieldIndex]
    CbText("字段 ${fieldIndex + 1}/${fields.size} · $field")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一字段", { fieldIndex-- }, enabled = fieldIndex > 0, variant = ButtonVariant.Secondary)
        CbButton("下一字段", { fieldIndex++ }, enabled = fieldIndex + 1 < fields.size, variant = ButtonVariant.Secondary)
    }
    val body = detail.fieldPage(recordKey, field, true, bodyPage)
    SelectionContainer { CbText(body.first) }
    if (body.second > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页字段正文", { bodyPage-- }, enabled = bodyPage > 0, variant = ButtonVariant.Secondary)
        CbText("${bodyPage + 1}/${body.second}")
        CbButton("下一页字段正文", { bodyPage++ }, enabled = bodyPage + 1 < body.second, variant = ButtonVariant.Secondary)
    }
}

private fun groupFrozenStage(stage: LanSyncGroupNativeStage): String = when (stage) {
    LanSyncGroupNativeStage.UNCHANGED -> "无变化"
    LanSyncGroupNativeStage.READY_CONTENT -> "传入内容已就绪"
    LanSyncGroupNativeStage.READY_DELETION -> "传入删除"
    LanSyncGroupNativeStage.READY_OBSERVATION -> "内容一致"
    LanSyncGroupNativeStage.LOCAL_PROPOSAL -> "本机修改"
    LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW -> "本机删除或覆盖待核对"
    LanSyncGroupNativeStage.CONFLICT -> "多版本待选择"
    LanSyncGroupNativeStage.AUTHOR_FORK -> "同一设备版本分叉"
    LanSyncGroupNativeStage.WAITING_DEPENDENCY -> "等待正文或附件"
    LanSyncGroupNativeStage.RECORD_REVIEW -> "记录归属待核对"
    LanSyncGroupNativeStage.DEPENDENCY_HELD -> "关联内容暂缓"
    LanSyncGroupNativeStage.INVALID_MIXED_GRAPH -> "关联资料尚未通过检查"
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
