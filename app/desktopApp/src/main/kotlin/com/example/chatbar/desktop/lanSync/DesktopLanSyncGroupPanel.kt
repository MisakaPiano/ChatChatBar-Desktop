package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.lanSync.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.awt.image.BufferedImage
import java.text.DateFormat
import java.util.Date

/** App-owned runtime survives page disposal; only transient management authority is locked here. */
@Composable
internal fun DesktopLanSyncGroupPanel(state: LanSyncGroupPanelState, actions: LanSyncGroupPanelActions,
    view: LanSyncGroupNodeView? = null, actionReview: LanSyncGroupActionReview? = null,
    recoveryEntries: List<LanSyncRecoveryEntry> = emptyList(), recoveryView: LanSyncGroupRecoveryView? = null) {
    DisposableEffect(actions) { onDispose { actions.lockManagement(); actions.cancelActionReview(); actions.closeRecovery() } }
    var joinCode by remember(state.privacyRevision) { mutableStateOf("") }
    var recoveryInput by remember(state.privacyRevision) { mutableStateOf("") }
    var saved by remember(state.setupBinding, state.privacyRevision) { mutableStateOf(false) }
    val owner = LocalDesktopFishParentWindow.current
    var copyFailed by remember(state.privacyRevision) { mutableStateOf(false) }
    fun copy(value: String) { copyFailed = !DesktopLanSyncSensitiveClipboard.copy(value, owner) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DesktopDataSectionHeading("设备组同步")
        StatusText(state.status)
        state.error?.let { StatusText(it, DesktopBootstrapColors.warning) }
        if (copyFailed) StatusText("未能复制，请保持此窗口打开后重试。", DesktopBootstrapColors.warning)
        if (state.stage == LanSyncGroupPanelStage.EMPTY) {
            StatusText(lanSyncGroupDescription)
            BootstrapButton("创建新设备组", enabled = !state.busy) { actions.createGroup() }
        }
        if (state.stage == LanSyncGroupPanelStage.EMPTY || state.stage == LanSyncGroupPanelStage.JOINING) {
            val joining = state.stage == LanSyncGroupPanelStage.JOINING
            key(state.privacyRevision) { LabeledField(if (joining) "更新邀请 · 粘贴新邀请" else "加入已有设备组 · 粘贴邀请",
                joinCode, secret = true, enabled = !state.busy, onChange = { joinCode = it }) }
            BootstrapButton(if (joining) "更新邀请并继续" else "检查并加入", secondary = true, enabled = !state.busy && joinCode.isNotBlank()) {
                val captured = joinCode; joinCode = ""; actions.beginJoin(captured)
            }
        }
        state.recoveryCode?.let { code ->
            StatusText("请保存恢复码。丢失设备后，还需要另一台已加入设备或对应的加密管理资料；仅有恢复码无法恢复。")
            Box(Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState())) { StatusText(code) }
            BootstrapButton("复制恢复码", secondary = true, enabled = !state.busy) { copy(code) }
            BootstrapButton(if (saved) "✓ 已妥善保存恢复码" else "我已妥善保存恢复码", secondary = true, enabled = !state.busy) { saved = !saved }
            state.setupBinding?.let { binding -> BootstrapButton("完成创建", enabled = !state.busy && saved) { actions.recoverySaved(binding) } }
        }
        if (state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED || state.stage == LanSyncGroupPanelStage.READY && state.localManagementAvailable && !state.managementUnlocked) {
            key(state.privacyRevision) { LabeledField("恢复码 · 临时解锁管理", recoveryInput, secret = true, enabled = !state.busy, onChange = { recoveryInput = it }) }
            BootstrapButton("解锁管理", secondary = true, enabled = !state.busy && recoveryInput.isNotBlank()) {
                val captured = recoveryInput; recoveryInput = ""; actions.unlockManagement(captured)
            }
        }
        if (state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED)
            BootstrapButton("放弃未完成的设置", secondary = true, enabled = !state.busy) { actions.abandonUnusedSetup() }
        if (state.stage == LanSyncGroupPanelStage.JOINING) {
            BootstrapButton("继续加入", enabled = !state.busy) { actions.retryJoin() }
            BootstrapButton("重新核对本机资料并继续", secondary = true, enabled = !state.busy) { actions.refreshJoinPreparation() }
        }
        if (state.stage == LanSyncGroupPanelStage.READY) {
            if (!state.localManagementAvailable) StatusText("此设备可日常同步；管理恢复还需要对应的加密管理资料。")
            groupRoute("本机", state.local, "最近保存"); groupRoute("附近设备", state.nearby, "最近送达"); groupRoute("网盘", state.cloud, "最近上传")
            ActionRow {
                BootstrapButton("立即检查", secondary = true, enabled = !state.busy) { actions.retry() }
                BootstrapButton("网盘设置", secondary = true, enabled = !state.busy) { actions.openCloudSettings() }
                BootstrapButton("邀请设备", enabled = !state.busy && state.managementUnlocked) { actions.createInvitation() }
                if (state.managementUnlocked) BootstrapButton("锁定管理", secondary = true) { actions.lockManagement() }
            }
            if (state.managementUnlocked) {
                StatusText("仅接续先前已确认的加入或撤销，不会重新决定设备成员。")
                BootstrapButton("接续已确认的管理操作", secondary = true, enabled = !state.busy) { actions.resumeManagementOperation() }
            }
            state.devices.forEach { device ->
                StatusText(device.name + if (device.thisDevice) " · 本机" else if (device.managementIdentity) " · 恢复管理身份" else "")
                StatusText(device.status)
                if (device.canRevoke) BootstrapButton("撤销此设备", secondary = true, enabled = !state.busy && state.managementUnlocked) { actions.prepareRevocation(device.id) }
            }
        }
        DesktopGroupFrozenReviewBrowser(view, state.busy, actions)
        actionReview?.let { review -> DesktopGroupActionReview(review, state.busy, actions) }
        if (state.stage == LanSyncGroupPanelStage.READY || recoveryEntries.isNotEmpty() || recoveryView != null)
            DesktopGroupRecoveryBrowser(recoveryEntries, recoveryView, state.busy, actions)
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

/** A selected inspection stays frozen while the application continues synchronizing. */
@Composable
private fun DesktopGroupFrozenReviewBrowser(view: LanSyncGroupNodeView?, busy: Boolean, actions: LanSyncGroupPanelActions) {
    var selected by remember { mutableStateOf<Pair<LanSyncGroupNodeView, String>?>(null) }
    val units = remember(view) {
        val plan = view?.plan
        plan?.units?.values?.filter { it.stage != LanSyncGroupNativeStage.UNCHANGED ||
            it.unit in view?.reservedUnits.orEmpty() || it.unit in view?.incomingReview?.reductions.orEmpty() }
            ?.map { it.unit }?.sorted().orEmpty()
    }
    var unitPage by remember(units) { mutableStateOf(0) }
    if (units.isNotEmpty() || selected != null) {
        DesktopDataSectionHeading("冻结内容详情")
        StatusText("可浏览本次检查取得的完整字段，待确认的内容会保留当前状态。核对发布或传入目标后，可再确认执行。")
        units.drop(unitPage * 10).take(10).forEach { unit ->
            val row = view?.plan?.units?.get(unit)
            DesktopGroupReviewButton("查看 $unit · ${row?.stage?.let(::desktopGroupFrozenStage).orEmpty()}",
                { view?.let { selected = it to unit } })
        }
        if (units.size > 10) ActionRow {
            DesktopGroupReviewButton("上一页内容组", { unitPage-- }, enabled = unitPage > 0)
            StatusText("${unitPage + 1}/${(units.size + 9) / 10}")
            DesktopGroupReviewButton("下一页内容组", { unitPage++ }, enabled = (unitPage + 1) * 10 < units.size)
        }
    }
    val frozen = selected ?: return
    val plan = frozen.first.plan ?: return
    val unit = frozen.second
    val row = plan.units[unit] ?: return
    StatusText("已冻结：$unit")
    StatusText("同步继续运行；这里保留所选检查结果，重新选择内容组可查看新的结果。此处可查看附件引用，暂不提供图片预览。")
    DesktopGroupReviewButton("关闭冻结详情", { selected = null })
    if (unit !in frozen.first.reservedUnits && row.stage in setOf(LanSyncGroupNativeStage.LOCAL_PROPOSAL,
            LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW, LanSyncGroupNativeStage.CONFLICT,
            LanSyncGroupNativeStage.RECORD_REVIEW, LanSyncGroupNativeStage.DEPENDENCY_HELD)) {
        DesktopGroupReviewButton("核对本机发布目标", { actions.prepareSourceReview(frozen.first, unit) }, enabled = !busy)
    }
    if (unit in frozen.first.incomingReview?.reductions.orEmpty()) {
        DesktopGroupReviewButton("核对传入删除或覆盖", { actions.prepareIncomingReview(frozen.first, unit) }, enabled = !busy)
    }
    val current = remember(plan, unit) { plan.before.units()[unit].orEmpty() }
    key(frozen.first, unit) {
        DesktopGroupFrozenRecords("本次检查时的本机内容", unit, current)
        row.frontiers.sortedBy { it.version.id.value }.forEach { frontier ->
            key(frontier.version) {
                StatusText("已签名版本：${frontier.version.id.value}")
                val incoming = row.incoming[frontier.version]
                when {
                    frontier.change.content == null -> StatusText("此版本为删除标记，没有正文记录。")
                    incoming != null -> DesktopGroupFrozenRecords("已取得并验证的传入版本", unit, incoming.decoded.payload.records)
                    else -> StatusText("此版本的正文或附件尚未完整取得，暂不能显示完整内容。")
                }
            }
        }
        frozen.first.incomingReview?.reductions?.get(unit)?.let { reduction ->
            DesktopGroupFrozenRecords("待确认的传入目标", unit, reduction.proposed)
        }
    }
}

/** Only verified owner snapshots reach this browser; closing it retains every historical entry. */
@Composable
private fun DesktopGroupRecoveryBrowser(entries: List<LanSyncRecoveryEntry>, view: LanSyncGroupRecoveryView?,
    busy: Boolean, actions: LanSyncGroupPanelActions) {
    DesktopDataSectionHeading("本机保全与恢复")
    DesktopGroupReviewButton("刷新保全记录", actions::refreshRecovery, enabled = !busy)
    var entryPage by remember(entries) { mutableStateOf(0) }
    entries.drop(entryPage * 10).take(10).forEach { entry ->
        key(entry.transaction) {
            DesktopGroupRecoveryMetadata(entry)
            DesktopGroupReviewButton("浏览此保全记录", { actions.openRecovery(entry.transaction) }, enabled = !busy)
        }
    }
    if (entries.size > 10) ActionRow {
        DesktopGroupReviewButton("上一页保全记录", { entryPage-- }, enabled = entryPage > 0)
        StatusText("${entryPage + 1}/${(entries.size + 9) / 10}")
        DesktopGroupReviewButton("下一页保全记录", { entryPage++ }, enabled = (entryPage + 1) * 10 < entries.size)
    }
    if (view == null) return
    StatusText("正在浏览的历史保全")
    DesktopGroupRecoveryMetadata(view.entry)
    StatusText("此处展示已保全的历史内容。准备恢复会先生成核对目标，确认后才修改本机资料。")
    DesktopGroupReviewButton("关闭保全浏览", actions::closeRecovery, enabled = !busy)
    val units = remember(view) { view.units.keys.sorted() }
    var unitPage by remember(view) { mutableStateOf(0) }
    units.drop(unitPage * 10).take(10).forEach { unit ->
        key(view, unit) {
            StatusText("历史内容组：$unit")
            DesktopGroupFrozenRecords("历史保全内容", unit, view.units.getValue(unit))
            DesktopGroupReviewButton("准备恢复此组", { actions.prepareRestore(unit) }, enabled = !busy)
        }
    }
    if (units.size > 10) ActionRow {
        DesktopGroupReviewButton("上一页历史内容组", { unitPage-- }, enabled = unitPage > 0)
        StatusText("${unitPage + 1}/${(units.size + 9) / 10}")
        DesktopGroupReviewButton("下一页历史内容组", { unitPage++ }, enabled = (unitPage + 1) * 10 < units.size)
    }
}

@Composable
private fun DesktopGroupRecoveryMetadata(entry: LanSyncRecoveryEntry) {
    StatusText("${DateFormat.getDateTimeInstance().format(Date(entry.createdAt))} · ${entry.affectedUnits} 个内容组 · ${entry.recordCount} 条记录（含 ${entry.messageCount} 条实时消息）")
    StatusText(if (entry.hasLocalOriginals) "本机原始文件与本机选择已独立保全。" else "此记录保全了对端同步内容，不含对端账户和本机模型选择。")
}

/** All confirmations refer to this exact owner-prepared review, never the latest screen state. */
@Composable
private fun DesktopGroupActionReview(review: LanSyncGroupActionReview, busy: Boolean, actions: LanSyncGroupPanelActions) {
    var confirmed by remember(review.binding) { mutableStateOf(false) }
    var unitPage by remember(review.binding) { mutableStateOf(0) }
    val recovery = review.recovery
    val canConfirm = review.canConfirm && (recovery?.hasChanges != false)
    DesktopDataSectionHeading(when { review.source != null -> "核对本机发布目标"; recovery != null -> "核对本机恢复目标"; else -> "核对传入删除或覆盖" })
    StatusText("以下关联内容按同一核对结果处理；请查看各组正文和影响后确认。确认时会再次检查资料是否变化。")
    val source = review.source
    if (recovery != null) {
        StatusText("恢复仅作用于所选内容组和必需的缺失依赖；其余本机内容保留。")
        StatusText("本次恢复的内容组：${recovery.restoredUnits.sorted().joinToString("、")}")
        DesktopGroupFrozenRecords("恢复前的本机内容", recovery.unit, recovery.currentRecords)
        DesktopGroupFrozenRecords("待恢复的目标内容", recovery.unit, recovery.restoredRecords)
        val impact = remember(review.binding) { LanSyncImpactReview.changes(recovery.currentRecords, recovery.restoredRecords) }
        StatusText(desktopGroupActionImpactSummary(impact))
        if (!recovery.hasChanges) StatusText("当前内容与恢复目标一致，没有需要写入的变化。")
    } else {
        val units = remember(review.binding) { source?.units?.keys?.sorted() ?: review.incomingBindings.keys.sorted() }
        StatusText("本次核对 ${units.size} 个内容组")
        units.drop(unitPage * 10).take(10).forEach { unit ->
            key(review.binding, unit) {
                StatusText("内容组：$unit")
                if (source != null) {
                    val row = source.units.getValue(unit)
                    DesktopGroupFrozenRecords("准备发布的本机目标", unit, row.current)
                    row.versions.entries.sortedWith(compareBy({ it.key.id.value }, { it.key.bodyHash })).forEach { (version, records) ->
                        key(version) {
                            StatusText("已签名的核对版本：${version.id.value}")
                            StatusText("版本正文指纹：${version.bodyHash}")
                            DesktopGroupFrozenRecords("此已签名版本的正文", unit, records)
                            row.impact[version]?.let { StatusText(desktopGroupActionImpactSummary(it)) }
                                ?: StatusText("此版本的影响摘要尚未取得。")
                        }
                    }
                    if (row.versions.isEmpty()) StatusText("本次尚无可显示的已签名核对正文。")
                    if (row.missingBodies.isNotEmpty()) StatusText("仍缺少 ${row.missingBodies.size} 份已签名版本的完整正文。")
                    if (row.records.missing.isNotEmpty()) StatusText("仍缺少 ${row.records.missing.size} 份记录归属依据。")
                    if (row.dependencies.isNotEmpty()) StatusText("尚需一并核对的关联组：${row.dependencies.sorted().joinToString("、")}")
                } else {
                    val row = review.incoming?.reductions?.get(unit)
                    if (row == null) StatusText("此组的核对数据尚未取得。") else {
                        DesktopGroupFrozenRecords("核对时的本机内容", unit, row.current)
                        DesktopGroupFrozenRecords("待应用的传入目标", unit, row.proposed)
                        StatusText(desktopGroupActionImpactSummary(row.impact))
                    }
                }
            }
        }
        if (units.size > 10) ActionRow {
            DesktopGroupReviewButton("上一页核对组", { unitPage-- }, enabled = unitPage > 0)
            StatusText("${unitPage + 1}/${(units.size + 9) / 10}")
            DesktopGroupReviewButton("下一页核对组", { unitPage++ }, enabled = (unitPage + 1) * 10 < units.size)
        }
    }
    review.heldReason?.let { StatusText(it, DesktopBootstrapColors.warning) }
    DesktopGroupReviewButton((if (confirmed) "✓ " else "") + "我已核对全部内容组和影响，并确认以上目标",
        { confirmed = !confirmed }, enabled = !busy && canConfirm)
    BootstrapButton(when { source != null -> "确认发布以上本机目标"; recovery != null -> "确认恢复以上本机目标"; else -> "确认应用以上传入目标" },
        enabled = !busy && canConfirm && confirmed) { actions.confirmActionReview(review.binding) }
    DesktopGroupReviewButton("取消本次核对", actions::cancelActionReview, enabled = !busy)
}

private fun desktopGroupActionImpactSummary(impact: List<LanSyncRecordImpact>): String {
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
private fun DesktopGroupFrozenRecords(label: String, unit: String, records: List<LanSyncRecord>) {
    var expanded by remember(records, label) { mutableStateOf(false) }
    DesktopGroupReviewButton((if (expanded) "收起" else "展开") + "$label · ${records.size} 条记录",
        { expanded = !expanded })
    if (!expanded) return
    val detail = remember(records, unit) { LanSyncDetailedReview.detail(unit, records, emptyList()) }
    val keys = detail.recordKeys
    if (keys.isEmpty()) { StatusText("此内容没有记录。"); return }
    var recordPage by remember(detail) { mutableStateOf(0) }
    var selectedKey by remember(detail) { mutableStateOf<String?>(null) }
    keys.drop(recordPage * 10).take(10).forEach { recordKey ->
        DesktopGroupReviewButton(recordKey, { selectedKey = recordKey })
    }
    if (keys.size > 10) ActionRow {
        DesktopGroupReviewButton("上一页记录", { recordPage-- }, enabled = recordPage > 0)
        StatusText("${recordPage + 1}/${(keys.size + 9) / 10}")
        DesktopGroupReviewButton("下一页记录", { recordPage++ }, enabled = (recordPage + 1) * 10 < keys.size)
    }
    val recordKey = selectedKey ?: return
    val fields = detail.fields(recordKey)
    var fieldIndex by remember(detail, recordKey) { mutableStateOf(0) }
    var bodyPage by remember(detail, recordKey, fieldIndex) { mutableStateOf(0) }
    StatusText("所选记录：$recordKey")
    if (fields.isEmpty()) { StatusText("此记录没有字段。"); return }
    val field = fields[fieldIndex]
    StatusText("字段 ${fieldIndex + 1}/${fields.size} · $field")
    ActionRow {
        DesktopGroupReviewButton("上一字段", { fieldIndex-- }, enabled = fieldIndex > 0)
        DesktopGroupReviewButton("下一字段", { fieldIndex++ }, enabled = fieldIndex + 1 < fields.size)
    }
    val body = detail.fieldPage(recordKey, field, true, bodyPage)
    SelectionContainer { StatusText(body.first) }
    if (body.second > 1) ActionRow {
        DesktopGroupReviewButton("上一页字段正文", { bodyPage-- }, enabled = bodyPage > 0)
        StatusText("${bodyPage + 1}/${body.second}")
        DesktopGroupReviewButton("下一页字段正文", { bodyPage++ }, enabled = bodyPage + 1 < body.second)
    }
}

private fun desktopGroupFrozenStage(stage: LanSyncGroupNativeStage): String = when (stage) {
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

@Composable
private fun DesktopGroupReviewButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    BootstrapButton(label, enabled = enabled, secondary = true, onClick = onClick)
}

@Composable private fun groupRoute(name: String, status: LanSyncGroupRouteStatus, observation: String) {
    StatusText("$name · ${status.text}")
    status.lastSuccessAt?.let { StatusText("$observation：${DateFormat.getDateTimeInstance().format(Date(it))}") }
}
