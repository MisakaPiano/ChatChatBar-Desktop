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
    view: LanSyncGroupNodeView? = null, actionReview: LanSyncGroupActionReview? = null,
    recoveryEntries: List<LanSyncRecoveryEntry> = emptyList(), recoveryView: LanSyncGroupRecoveryView? = null,
    imageState: LanSyncGroupImageState = LanSyncGroupImageState(), scanInvitation: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(actions, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) actions.lockManagement() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); actions.lockManagement(); actions.cancelActionReview(); actions.closeRecovery(); actions.closeImagePreview() }
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
        imageState.error?.let { CbText(it, color = ChatBarTheme.colors.warning) }
        if (copyFailed) CbText("未能复制，请重试。", color = ChatBarTheme.colors.destructive)
        if (state.stage == LanSyncGroupPanelStage.EMPTY) {
            CbText(lanSyncGroupDescription)
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
        if (state.stage == LanSyncGroupPanelStage.READY && !state.localManagementAvailable &&
            state.managementRecoveryAvailable && !state.managementUnlocked) {
            CbText("使用这台已加入设备保留的加密管理资料和恢复码，恢复原设备组的管理权。")
            CbField("恢复码 · 恢复设备组管理") {
                key(state.privacyRevision) { CbInput(recoveryInput, { recoveryInput = it }, secure = true, enabled = !state.busy) }
            }
            CbButton("用恢复码恢复组管理", {
                val captured = recoveryInput; recoveryInput = ""; actions.recoverManagement(captured)
            }, enabled = !state.busy && recoveryInput.isNotBlank(), variant = ButtonVariant.Secondary)
        }
        if (state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED)
            CbButton("放弃未完成的设置", actions::abandonUnusedSetup, enabled = !state.busy, variant = ButtonVariant.Secondary)
        if (state.stage == LanSyncGroupPanelStage.JOINING) {
            CbButton("继续加入", actions::retryJoin, enabled = !state.busy)
            CbButton("重新核对本机资料并继续", actions::refreshJoinPreparation, enabled = !state.busy, variant = ButtonVariant.Secondary)
        }
        if (state.stage == LanSyncGroupPanelStage.READY) {
            if (!state.localManagementAvailable && !state.managementRecoveryAvailable && !state.managementUnlocked)
                CbText("此设备可日常同步；管理恢复还需要对应的加密管理资料。")
            GroupRoute("本机", state.local, "最近保存"); GroupRoute("附近设备", state.nearby, "最近送达"); GroupRoute("网盘", state.cloud, "最近上传")
            CbButton("立即检查", actions::retry, enabled = !state.busy, variant = ButtonVariant.Secondary)
            CbButton("网盘设置", actions::openCloudSettings, enabled = !state.busy, variant = ButtonVariant.Secondary)
            CbButton("邀请设备", actions::createInvitation, enabled = !state.busy && state.managementUnlocked && state.managementCanInvite)
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
        GroupFrozenReviewBrowser(view, state.busy, actions)
        LanSyncGroupResolutionHistory(view, state.busy, actions)
        actionReview?.let { review -> GroupActionReview(review, state.busy, actions) }
        if (state.stage == LanSyncGroupPanelStage.READY || recoveryEntries.isNotEmpty() || recoveryView != null)
            GroupRecoveryBrowser(recoveryEntries, recoveryView, state.busy, actions)
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
    imageState.preview?.let { preview -> LanSyncFrozenImageDialog(preview, actions::closeImagePreview) }

}

/** A selected inspection stays frozen while the application continues synchronizing. */
@Composable
private fun GroupFrozenReviewBrowser(view: LanSyncGroupNodeView?, busy: Boolean, actions: LanSyncGroupPanelActions) {
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
        CbText("可浏览本次检查取得的完整字段，待确认的内容会保留当前状态。核对发布或传入目标后，可再确认执行。")
        units.drop(unitPage * 10).take(10).forEach { unit ->
            val row = view?.plan?.units?.get(unit)
            val display = view?.let { LanSyncGroupDisplay.unit(it, unit) } ?: LanSyncGroupDisplay.unit(unit, emptyList())
            CbText(display.identifier)
            CbButton("查看 ${display.kind} · ${display.title} · ${row?.stage?.let(::groupFrozenStage).orEmpty()}",
                { view?.let { actions.closeImagePreview(); selected = it to unit } }, variant = ButtonVariant.Secondary)
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
    val display = LanSyncGroupDisplay.unit(frozen.first, unit)
    CbText("已冻结：${display.kind} · ${display.title}")
    CbText(display.identifier)
    CbText("同步继续运行；这里保留所选检查结果，重新选择内容组可查看新的结果。图片从本次冻结内容对应的本机缓存中读取。")
    CbButton("关闭冻结详情", { actions.closeImagePreview(); selected = null }, variant = ButtonVariant.Secondary)
    if (unit !in frozen.first.reservedUnits && row.stage in setOf(LanSyncGroupNativeStage.LOCAL_PROPOSAL,
            LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW, LanSyncGroupNativeStage.CONFLICT,
            LanSyncGroupNativeStage.RECORD_REVIEW, LanSyncGroupNativeStage.DEPENDENCY_HELD)) {
        CbButton("核对本机发布目标", { actions.prepareSourceReview(frozen.first, unit) },
            enabled = !busy, variant = ButtonVariant.Secondary)
    }
    if (unit in frozen.first.incomingReview?.reductions.orEmpty()) {
        CbButton("核对传入删除或覆盖", { actions.prepareIncomingReview(frozen.first, unit) },
            enabled = !busy, variant = ButtonVariant.Secondary)
    }
    LanSyncGroupResolutionChoices(frozen.first, unit, busy, actions)
    val imageContext = LanSyncGroupFrozenImageContext.Inspection(frozen.first)
    val current = remember(plan, unit) { plan.before.units()[unit].orEmpty() }
    key(frozen.first, unit) {
        GroupFrozenRecords("本次检查时的本机内容", unit, current, imageContext, actions)
        row.frontiers.sortedBy { it.version.id.value }.forEach { frontier ->
            key(frontier.version) {
                CbText("已签名版本：${frontier.version.id.value}")
                val incoming = row.incoming[frontier.version]
                when {
                    frontier.change.content == null -> CbText("此版本为删除标记，没有正文记录。")
                    incoming != null -> GroupFrozenRecords("已取得并验证的传入版本", unit, incoming.decoded.payload.records, imageContext, actions)
                    else -> CbText("此版本的正文或附件尚未完整取得，暂不能显示完整内容。")
                }
            }
        }
        frozen.first.incomingReview?.reductions?.get(unit)?.let { reduction ->
            GroupFrozenRecords("待确认的传入目标", unit, reduction.proposed, imageContext, actions)
        }
    }
}

/** Only verified owner snapshots reach this browser; closing it retains every historical entry. */
@Composable
private fun GroupRecoveryBrowser(entries: List<LanSyncRecoveryEntry>, view: LanSyncGroupRecoveryView?,
    busy: Boolean, actions: LanSyncGroupPanelActions) {
    CbText("本机保全与恢复", style = ChatBarTheme.typography.title)
    CbButton("刷新保全记录", actions::refreshRecovery, enabled = !busy, variant = ButtonVariant.Secondary)
    var entryPage by remember(entries) { mutableStateOf(0) }
    entries.drop(entryPage * 10).take(10).forEach { entry ->
        key(entry.transaction) {
            GroupRecoveryMetadata(entry)
            CbButton("浏览此保全记录", { actions.openRecovery(entry.transaction) }, enabled = !busy, variant = ButtonVariant.Secondary)
        }
    }
    if (entries.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页保全记录", { entryPage-- }, enabled = entryPage > 0, variant = ButtonVariant.Secondary)
        CbText("${entryPage + 1}/${(entries.size + 9) / 10}")
        CbButton("下一页保全记录", { entryPage++ }, enabled = (entryPage + 1) * 10 < entries.size, variant = ButtonVariant.Secondary)
    }
    if (view == null) return
    val imageContext = LanSyncGroupFrozenImageContext.Recovery(view)
    CbText("正在浏览的历史保全")
    GroupRecoveryMetadata(view.entry)
    CbText("此处展示已保全的历史内容。准备恢复会先生成核对目标，确认后才修改本机资料。")
    CbButton("关闭保全浏览", actions::closeRecovery, enabled = !busy, variant = ButtonVariant.Secondary)
    val units = remember(view) { view.units.keys.sorted() }
    var unitPage by remember(view) { mutableStateOf(0) }
    units.drop(unitPage * 10).take(10).forEach { unit ->
        key(view, unit) {
            val display = LanSyncGroupDisplay.unit(unit, view.units.getValue(unit))
            CbText("历史内容组：${display.kind} · ${display.title}")
            CbText(display.identifier)
            GroupFrozenRecords("历史保全内容", unit, view.units.getValue(unit), imageContext, actions)
            CbButton("准备恢复此组", { actions.prepareRestore(unit) }, enabled = !busy, variant = ButtonVariant.Secondary)
        }
    }
    if (units.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页历史内容组", { unitPage-- }, enabled = unitPage > 0, variant = ButtonVariant.Secondary)
        CbText("${unitPage + 1}/${(units.size + 9) / 10}")
        CbButton("下一页历史内容组", { unitPage++ }, enabled = (unitPage + 1) * 10 < units.size, variant = ButtonVariant.Secondary)
    }
}

@Composable
private fun GroupRecoveryMetadata(entry: LanSyncRecoveryEntry) {
    CbText("${DateFormat.getDateTimeInstance().format(Date(entry.createdAt))} · ${entry.affectedUnits} 个内容组 · ${entry.recordCount} 条记录（含 ${entry.messageCount} 条实时消息）")
    CbText(if (entry.hasLocalOriginals) "本机原始文件与本机选择已独立保全。" else "此记录保全了对端同步内容，不含对端账户和本机模型选择。")
}

/** All confirmations refer to this exact owner-prepared review, never the latest screen state. */
@Composable
private fun GroupActionReview(review: LanSyncGroupActionReview, busy: Boolean, actions: LanSyncGroupPanelActions) {
    val imageContext = LanSyncGroupFrozenImageContext.Action(review)
    var confirmed by remember(review.binding) { mutableStateOf(false) }
    var unitPage by remember(review.binding) { mutableStateOf(0) }
    val recovery = review.recovery
    val resolution = review.resolution
    val canConfirm = review.canConfirm && (recovery?.hasChanges != false)
    CbText(when { resolution != null -> "核对版本处理目标"; review.source != null -> "核对本机发布目标"; recovery != null -> "核对本机恢复目标"; else -> "核对传入删除或覆盖" },
        style = ChatBarTheme.typography.title)
    CbText("以下关联内容按同一核对结果处理；请查看各组正文和影响后确认。确认时会再次检查资料是否变化。")
    val source = review.source
    if (resolution != null) {
        LanSyncGroupResolutionDetails(review, actions)
    } else if (recovery != null) {
        CbText("恢复仅作用于所选内容组和必需的缺失依赖；其余本机内容保留。")
        CbText("本次恢复的内容组：${recovery.restoredUnits.sorted().joinToString("、")}")
        GroupFrozenRecords("恢复前的本机内容", recovery.unit, recovery.currentRecords, imageContext, actions)
        GroupFrozenRecords("待恢复的目标内容", recovery.unit, recovery.restoredRecords, imageContext, actions)
        val impact = remember(review.binding) { LanSyncImpactReview.changes(recovery.currentRecords, recovery.restoredRecords) }
        CbText(groupActionImpactSummary(impact))
        if (!recovery.hasChanges) CbText("当前内容与恢复目标一致，没有需要写入的变化。")
    } else {
        val units = remember(review.binding) { source?.units?.keys?.sorted() ?: review.incomingBindings.keys.sorted() }
        CbText("本次核对 ${units.size} 个内容组")
        units.drop(unitPage * 10).take(10).forEach { unit ->
            key(review.binding, unit) {
                val displayRows = source?.units?.get(unit)?.let { row -> row.current.ifEmpty { row.versions.values.flatten() } }
                    ?: review.incoming?.reductions?.get(unit)?.let { row -> row.current.ifEmpty { row.proposed } }.orEmpty()
                val display = LanSyncGroupDisplay.unit(unit, displayRows)
                CbText("内容组：${display.kind} · ${display.title}")
                CbText(display.identifier)
                if (source != null) {
                    val row = source.units.getValue(unit)
                    GroupFrozenRecords("准备发布的本机目标", unit, row.current, imageContext, actions)
                    row.versions.entries.sortedWith(compareBy({ it.key.id.value }, { it.key.bodyHash })).forEach { (version, records) ->
                        key(version) {
                            CbText("已签名的核对版本：${version.id.value}")
                            CbText("版本正文指纹：${version.bodyHash}")
                            GroupFrozenRecords("此已签名版本的正文", unit, records, imageContext, actions)
                            row.impact[version]?.let { CbText(groupActionImpactSummary(it)) }
                                ?: CbText("此版本的影响摘要尚未取得。")
                        }
                    }
                    if (row.versions.isEmpty()) CbText("本次尚无可显示的已签名核对正文。")
                    if (row.missingBodies.isNotEmpty()) CbText("仍缺少 ${row.missingBodies.size} 份已签名版本的完整正文。")
                    if (row.records.missing.isNotEmpty()) CbText("仍缺少 ${row.records.missing.size} 份记录归属依据。")
                    if (row.dependencies.isNotEmpty()) CbText("尚需一并核对的关联组：${row.dependencies.sorted().joinToString("、")}")
                } else {
                    val row = review.incoming?.reductions?.get(unit)
                    if (row == null) CbText("此组的核对数据尚未取得。") else {
                        GroupFrozenRecords("核对时的本机内容", unit, row.current, imageContext, actions)
                        GroupFrozenRecords("待应用的传入目标", unit, row.proposed, imageContext, actions)
                        CbText(groupActionImpactSummary(row.impact))
                    }
                }
            }
        }
        if (units.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CbButton("上一页核对组", { unitPage-- }, enabled = unitPage > 0, variant = ButtonVariant.Secondary)
            CbText("${unitPage + 1}/${(units.size + 9) / 10}")
            CbButton("下一页核对组", { unitPage++ }, enabled = (unitPage + 1) * 10 < units.size, variant = ButtonVariant.Secondary)
        }
    }
    review.heldReason?.let { CbText(it, color = ChatBarTheme.colors.warning) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbCheckbox(confirmed, { confirmed = it }, enabled = !busy && canConfirm,
            contentDescription = "已核对全部内容组和影响，并确认以上目标")
        CbText("我已核对全部内容组和影响，并确认以上目标")
    }
    CbButton(when { resolution != null -> "确认以上版本处理与暂缓"; source != null -> "确认发布以上本机目标"; recovery != null -> "确认恢复以上本机目标"; else -> "确认应用以上传入目标" },
        { actions.confirmActionReview(review.binding) }, enabled = !busy && canConfirm && confirmed)
    CbButton("取消本次核对", actions::cancelActionReview, enabled = !busy, variant = ButtonVariant.Secondary)
}

internal fun groupActionImpactSummary(impact: List<LanSyncRecordImpact>): String {
    val added = impact.count { it.change == LanSyncRecordChange.ADD }
    val overwritten = impact.count { it.change == LanSyncRecordChange.REPLACE }
    val removed = impact.count { it.change == LanSyncRecordChange.REMOVE }
    val messages = impact.count { it.change == LanSyncRecordChange.REMOVE && it.message }
    val versions = impact.sumOf { it.removedVersionIds.size.toLong() }
    val attachments = impact.sumOf { it.removedAttachmentReferences.toLong() }
    val archivedMessages = impact.sumOf { it.removedEmbeddedMessageIds.size.toLong() }
    return "相对以上原内容，目标新增 $added 条记录、覆盖 $overwritten 条记录、移除 $removed 条记录（含 $messages 条实时消息）；" +
        "移除 $versions 个消息版本、$attachments 条附件引用和 $archivedMessages 条存档内消息。"
}

@Composable
internal fun GroupFrozenRecords(label: String, unit: String, records: List<LanSyncRecord>,
    imageContext: LanSyncGroupFrozenImageContext, actions: LanSyncGroupPanelActions) {
    var expanded by remember(records, label) { mutableStateOf(false) }
    CbButton((if (expanded) "收起" else "展开") + "$label · ${records.size} 条记录",
        { if (expanded) actions.closeImagePreview(); expanded = !expanded }, variant = ButtonVariant.Secondary)
    if (!expanded) return
    val detail = remember(records, unit) { LanSyncDetailedReview.detail(unit, records, emptyList()) }
    val keys = detail.recordKeys
    if (keys.isEmpty()) { CbText("此内容没有记录。"); return }
    var recordPage by remember(detail) { mutableStateOf(0) }
    var selectedKey by remember(detail) { mutableStateOf<String?>(null) }
    keys.drop(recordPage * 10).take(10).forEach { recordKey ->
        val display = detail.record(recordKey, true)?.let(LanSyncGroupDisplay::record)
        CbText(display?.identifier ?: recordKey)
        CbButton(display?.let { "${it.kind} · ${it.title}" } ?: recordKey, { actions.closeImagePreview(); selectedKey = recordKey }, variant = ButtonVariant.Secondary)
    }
    if (keys.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页记录", { recordPage-- }, enabled = recordPage > 0, variant = ButtonVariant.Secondary)
        CbText("${recordPage + 1}/${(keys.size + 9) / 10}")
        CbButton("下一页记录", { recordPage++ }, enabled = (recordPage + 1) * 10 < keys.size, variant = ButtonVariant.Secondary)
    }
    val recordKey = selectedKey ?: return
    val imageRecord = detail.record(recordKey, true)
    val images = remember(detail, recordKey) { imageRecord?.let(LanSyncReviewImages::references).orEmpty() }
    var imagePage by remember(detail, recordKey) { mutableStateOf(0) }
    images.drop(imagePage * 10).take(10).forEachIndexed { index, image ->
        CbButton("查看冻结图片 ${imagePage * 10 + index + 1} · ${image.field}", {
            imageRecord?.let { actions.previewImage(imageContext, it, image.reference) }
        }, variant = ButtonVariant.Secondary)
    }
    if (images.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页图片", { imagePage-- }, enabled = imagePage > 0, variant = ButtonVariant.Secondary)
        CbText("${imagePage + 1}/${(images.size + 9) / 10}")
        CbButton("下一页图片", { imagePage++ }, enabled = (imagePage + 1) * 10 < images.size, variant = ButtonVariant.Secondary)
    }
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
