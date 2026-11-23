package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.lanSync.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.text.DateFormat
import java.util.Date

/** App-owned runtime survives page disposal; only transient management authority is locked here. */
@Composable
internal fun DesktopLanSyncGroupPanel(state: LanSyncGroupPanelState, actions: LanSyncGroupPanelActions,
    view: LanSyncGroupNodeView? = null, actionReview: LanSyncGroupActionReview? = null,
    recoveryEntries: List<LanSyncRecoveryEntry> = emptyList(), recoveryView: LanSyncGroupRecoveryView? = null,
    imageState: LanSyncGroupImageState = LanSyncGroupImageState()) {
    val interactionBlocked = state.busy || (state.paused && state.stage == LanSyncGroupPanelStage.READY)
    DisposableEffect(actions) { onDispose { actions.cancelActionReview(); actions.closeRecovery(); actions.closeImagePreview() } }
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
        state.membershipRecoveryImport?.let { review ->
            DesktopGroupMembershipRecoveryImport(review, state.busy, actions)
            return@Column
        }
        if (state.stage == LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT) {
            StatusText("同步和成员变更已暂停，本机资料与签名记录均已保留。请由设备组管理员核对这些分支及各自的签发设备。")
            state.membershipConflict?.let { conflict ->
                StatusText("共同账本记录为第 ${conflict.commonSequence} 条。已核实 ${conflict.totalHeads} 个分支均由原设备组管理身份签名。")
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        conflict.branches.forEach { branch ->
                            StatusText("签名分支 ${branch.id}")
                            branch.added.forEach { StatusText("批准设备 ${it}") }
                            branch.revoked.forEach { StatusText("撤销设备 ${it}") }
                        }
                    }
                }
                if (conflict.branches.size < conflict.totalHeads) StatusText("此处显示 ${conflict.branches.size} 个分支，其余签名记录仍保留。")
            }
            DesktopGroupMembershipRecovery(state, actions)
            return@Column
        }
        imageState.error?.let { StatusText(it, DesktopBootstrapColors.warning) }
        if (copyFailed) StatusText("未能复制，请保持此窗口打开后重试。", DesktopBootstrapColors.warning)
        if (state.stage == LanSyncGroupPanelStage.EMPTY) {
            StatusText(lanSyncGroupDescription)
            if (state.shelvedJoins.isEmpty()) BootstrapButton("创建新设备组", enabled = !interactionBlocked) { actions.createGroup() }
            else StatusText("加入其他设备组后，需先处理当前组，才能恢复暂存申请。")
            state.shelvedJoins.forEach { saved ->
                BootstrapButton("恢复原加入申请 · ${saved.group.take(8)}", secondary = true, enabled = !interactionBlocked) {
                    actions.restoreShelvedJoin(saved.operation)
                }
            }
        }
        if (!state.recoveryJoining && !state.joiningLedgerConflict &&
            (state.stage == LanSyncGroupPanelStage.EMPTY || state.stage == LanSyncGroupPanelStage.JOINING)) {
            val joining = state.stage == LanSyncGroupPanelStage.JOINING
            key(state.privacyRevision) { LabeledField(if (joining) "更新邀请 · 粘贴新邀请" else "加入已有设备组 · 粘贴邀请",
                joinCode, secret = true, enabled = !interactionBlocked, onChange = { joinCode = it }) }
            BootstrapButton(if (joining) "更新邀请并继续" else "检查并加入", secondary = true, enabled = !interactionBlocked && joinCode.isNotBlank()) {
                val captured = joinCode; joinCode = ""; actions.beginJoin(captured)
            }
        }
        state.recoveryCode?.let { code ->
            StatusText("请保存恢复码。丢失设备后，还需要另一台已加入设备或对应的加密管理资料；仅有恢复码无法恢复。")
            Box(Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState())) { StatusText(code) }
            BootstrapButton("复制恢复码", secondary = true, enabled = !interactionBlocked) { copy(code) }
            BootstrapButton(if (saved) "✓ 已妥善保存恢复码" else "我已妥善保存恢复码", secondary = true, enabled = !interactionBlocked) { saved = !saved }
            state.setupBinding?.let { binding -> BootstrapButton("完成创建", enabled = !interactionBlocked && saved) { actions.recoverySaved(binding) } }
        }
        if (state.localManagementAvailable && !state.managementUnlocked &&
            state.stage in setOf(LanSyncGroupPanelStage.PREPARED_LOCKED, LanSyncGroupPanelStage.READY)) {
            val continuing = state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED
            key(state.privacyRevision) { LabeledField(if (continuing) "为该组保存的恢复码" else "恢复码 · 临时解锁管理",
                recoveryInput, secret = true, enabled = !interactionBlocked, onChange = { recoveryInput = it }) }
            BootstrapButton(if (continuing) "用恢复码继续设置" else "解锁管理", secondary = true, enabled = !interactionBlocked && recoveryInput.isNotBlank()) {
                val captured = recoveryInput; recoveryInput = ""; actions.unlockManagement(captured)
            }
        }
        if (state.stage == LanSyncGroupPanelStage.READY && !state.localManagementAvailable &&
            state.managementRecoveryAvailable && !state.managementUnlocked) {
            StatusText("使用这台已加入设备保留的加密管理资料和恢复码，恢复原设备组的管理权。")
            key(state.privacyRevision) { LabeledField("恢复码 · 恢复设备组管理", recoveryInput, secret = true,
                enabled = !interactionBlocked, onChange = { recoveryInput = it }) }
            BootstrapButton("用恢复码恢复组管理", secondary = true, enabled = !interactionBlocked && recoveryInput.isNotBlank()) {
                val captured = recoveryInput; recoveryInput = ""; actions.recoverManagement(captured)
            }
        }
        if (state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED && state.canAbandonUnactivated)
            BootstrapButton("放弃未完成的设置", secondary = true, enabled = !interactionBlocked) { actions.abandonUnusedSetup() }
        if (!state.recoveryJoining && state.stage == LanSyncGroupPanelStage.JOINING) {
            state.joiningDevice?.let { StatusText("本机设备指纹，请与邀请设备上的身份逐字核对\n$it") }
            if (state.canArchiveRevokedJoin) BootstrapButton("结束并归档已撤销的申请", enabled = !interactionBlocked) { actions.archiveRevokedJoin() }
            else BootstrapButton(if (state.joiningLedgerConflict) "重新核对原申请" else "继续加入",
                enabled = !interactionBlocked && !state.paused) { actions.retryJoin() }
            if (state.canAbandonJoin) BootstrapButton("放弃未发送的申请", secondary = true, enabled = !interactionBlocked) { actions.abandonUnprovedJoin() }
            if (state.canShelveJoin) BootstrapButton("暂存原申请并重新加入", secondary = true, enabled = !interactionBlocked) { actions.shelveUnsettledJoin() }
            if (state.canRefreshJoinPreparation) BootstrapButton("重新核对本机资料并继续", secondary = true, enabled = !interactionBlocked) { actions.refreshJoinPreparation() }
        }
        if (state.stage == LanSyncGroupPanelStage.READY) {
            if (!state.localManagementAvailable && !state.managementRecoveryAvailable && !state.managementUnlocked)
                StatusText("此设备可日常同步；管理恢复还需要对应的加密管理资料。")
            groupRoute("本机", state.local, "最近保存"); groupRoute("附近设备", state.nearby, "最近连接"); groupRoute("网盘", state.cloud, "最近上传")
            state.nearbyNotice?.let { StatusText(it) }
            ActionRow {
                BootstrapButton("立即检查", secondary = true, enabled = !interactionBlocked) { actions.retry() }
                BootstrapButton("网盘设置", secondary = true, enabled = !interactionBlocked) { actions.openCloudSettings() }
                BootstrapButton("邀请设备", enabled = !interactionBlocked && state.managementUnlocked && state.managementCanInvite) { actions.createInvitation() }
                if (state.managementUnlocked) BootstrapButton("生成邀请文件", secondary = true,
                    enabled = !interactionBlocked && state.managementCanInvite) { actions.createInvitation(forFile = true) }
                if (state.managementUnlocked) BootstrapButton("锁定管理", secondary = true) { actions.lockManagement() }
            }
            state.invitation?.takeUnless { state.invitationFileOnly }?.let { offer ->
                val code = remember(offer) { offer.code() }
                val matrix = remember(code) { desktopGroupInvitationQrMatrix(code) }
                if (matrix == null) {
                    StatusText("此邀请暂无法显示二维码，请复制邀请并在新设备粘贴。")
                } else BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val density = LocalDensity.current
                    // Keep the normal preview compact; a dense code may need 330px at 100% DPI.
                    val preferredPixels = with(density) { 280.dp.roundToPx() }
                    val maximumPixels = minOf(constraints.maxWidth, maxOf(preferredPixels, matrix.width * 2))
                    val image = remember(matrix, maximumPixels) {
                        desktopGroupInvitationQrImage(matrix, maximumPixels)?.toComposeImageBitmap()
                    }
                    if (image == null) {
                        StatusText("窗口过窄，无法清晰显示完整二维码。请加宽窗口，或复制邀请并在新设备粘贴。")
                    } else Image(image, "新设备扫描此邀请",
                        Modifier.size(with(density) { image.width.toDp() }),
                        contentScale = ContentScale.None, filterQuality = FilterQuality.None)
                }
                StatusText("邀请有效期为三分钟。切换程序后可返回这里，重新解锁管理并核对新设备。")
                ActionRow { BootstrapButton("复制邀请", secondary = true) { copy(code) }; BootstrapButton("关闭邀请", secondary = true,
                    enabled = !state.invitationApprovalInProgress) { actions.closeInvitation() } }
                state.invitationAddresses.filter { it != offer.host }.forEach { address ->
                    BootstrapButton("用 $address 生成新邀请", secondary = true, enabled = !interactionBlocked && state.managementUnlocked) {
                        actions.createInvitation(address)
                    }
                }
            }
            if (state.managementUnlocked) {
                StatusText("仅接续先前已确认的加入或撤销，不会重新决定设备成员。")
                BootstrapButton("接续已确认的管理操作", secondary = true, enabled = !interactionBlocked) { actions.resumeManagementOperation() }
            }
            state.devices.forEach { device -> key(device.id) {
                StatusText(device.name + if (device.thisDevice) " · 本机" else if (device.managementIdentity) " · 恢复管理身份" else "")
                StatusText("设备指纹 ${device.id}")
                StatusText(device.status)
                device.nearby?.let { nearby ->
                    StatusText(nearby.stage.label)
                    nearby.lastSuccessAt?.let { StatusText("最近连接：${DateFormat.getDateTimeInstance().format(Date(it))}") }
                }
                if (device.canRevoke) BootstrapButton("撤销此设备", secondary = true, enabled = !interactionBlocked && state.managementUnlocked) { actions.prepareRevocation(device.id) }
            } }
        }
        DesktopGroupFrozenReviewBrowser(view, interactionBlocked, actions)
        DesktopLanSyncGroupResolutionHistory(view, interactionBlocked, actions)
        actionReview?.let { review -> DesktopGroupActionReview(review, state.busy, actions) }
        if (state.stage == LanSyncGroupPanelStage.READY || recoveryEntries.isNotEmpty() || recoveryView != null)
            DesktopGroupRecoveryBrowser(recoveryEntries, recoveryView, interactionBlocked, actions)
        state.peerConfirmation?.let { peer ->
            StatusText(if (state.invitationApprovalInProgress) "正在批准 ${peer.name}" else "待批准 · 允许 ${peer.name} 加入设备组？")
            StatusText("设备身份：${peer.fingerprint}")
            BootstrapButton("确认加入此设备", enabled = !interactionBlocked && state.managementUnlocked) { actions.acceptPeer(peer.binding) }
        }
        state.revocation?.let { review ->
            StatusText("撤销 ${review.name}？")
            StatusText("设备指纹 ${review.fingerprint}")
            StatusText("该设备将无法取得新的同步密钥，已保存的副本仍会保留。网盘账号授权需要单独撤销。")
            ActionRow {
                BootstrapButton("确认撤销", enabled = !interactionBlocked && state.managementUnlocked) { actions.confirmRevocation(review.binding) }
                BootstrapButton("取消", secondary = true, enabled = !interactionBlocked) { actions.cancelRevocation() }
            }
        }
    }
    imageState.preview?.let { preview ->
        DesktopTransientImagePreview(preview.id, { actions.readImagePreview(preview.id) },
            previewNote = "冻结静态预览 · 原始图片完整保留", onClose = actions::closeImagePreview)
    }

}

@Composable
private fun DesktopGroupMembershipRecovery(state: LanSyncGroupPanelState, actions: LanSyncGroupPanelActions) {
    val review = state.membershipRecovery
    if (review == null) {
        var code by remember(state.privacyRevision) { mutableStateOf("") }
        LabeledField("原设备组恢复码", code, secret = true, enabled = !state.busy, onChange = { code = it })
        BootstrapButton("核对成员恢复方案", secondary = true, enabled = !state.busy && code.isNotBlank()) {
            val submitted = code
            code = ""
            actions.prepareMembershipRecovery(submitted)
        }
        return
    }
    var primaryHeadId by remember(review.binding, state.privacyRevision) { mutableStateOf<String?>(null) }
    val choices = remember(review.binding, state.privacyRevision) { mutableStateMapOf<String, Boolean>() }
    val selectable = review.members.filter { it.authority == LanSyncMembershipRecoveryAuthority.CHOICE }
    StatusText("选择一个已核实的分支作为同步权限基准。另一分支尚未获共同认可的聊天仍留在原设备，可回到本机会话查看。")
    review.retainedPrimaryHeadId?.let { StatusText("待续办的原权限基准为 ${it.take(12)}。请核对先前授权后继续。") }
    review.heads.forEach { head ->
        key(head.id) {
            DesktopGroupMembershipRecoveryHead(head)
            BootstrapButton(if (primaryHeadId == head.id) "已选为权限基准" else "选为权限基准 · ${head.id.take(12)}",
                secondary = true, enabled = !state.busy && primaryHeadId != head.id) { primaryHeadId = head.id }
        }
    }
    StatusText("明确核对每台设备的权限。曾被有效撤销的身份无法恢复。")
    review.members.forEach { member ->
        key(member.fingerprint) {
            DesktopGroupMembershipRecoveryMember(member, review.heads)
            when (member.authority) {
                LanSyncMembershipRecoveryAuthority.REQUIRED_ACTIVE -> StatusText("保留此身份的权限")
                LanSyncMembershipRecoveryAuthority.REVOKED -> StatusText("保留撤销记录，不发放新同步密钥")
                LanSyncMembershipRecoveryAuthority.CHOICE -> {
                    val choice = choices[member.fingerprint]
                    StatusText(when (choice) {
                        true -> "已选继续同步"
                        false -> "已选撤销权限"
                        null -> "请选择恢复后的权限"
                    })
                    ActionRow {
                        BootstrapButton("继续同步", secondary = true, enabled = !state.busy && choice != true) {
                            choices[member.fingerprint] = true
                        }
                        BootstrapButton("撤销权限", secondary = true, enabled = !state.busy && choice != false) {
                            choices[member.fingerprint] = false
                        }
                    }
                }
            }
        }
    }
    StatusText("签发会轮换同步密钥。恢复后有权限的设备可获得新密钥，已撤销的设备仍保留原本机副本。")
    BootstrapButton("确认权限并签发恢复包",
        enabled = !state.busy && primaryHeadId != null && selectable.all { it.fingerprint in choices }) {
        val active = review.members.filter { member ->
            member.authority == LanSyncMembershipRecoveryAuthority.REQUIRED_ACTIVE || choices[member.fingerprint] == true
        }.mapTo(linkedSetOf()) { it.fingerprint }
        actions.confirmMembershipRecovery(review.binding, requireNotNull(primaryHeadId), active)
    }
    BootstrapButton("锁定恢复审阅", secondary = true, enabled = !state.busy) { actions.lockMembershipRecovery() }
}

@Composable
private fun DesktopGroupMembershipRecoveryImport(review: LanSyncMembershipRecoveryImportReview, busy: Boolean,
    actions: LanSyncGroupPanelActions) {
    DesktopDataSectionHeading("核对已验证的签名恢复包")
    StatusText("共同账本记录为第 ${review.commonSequence} 条。")
    review.heads.forEach { head ->
        DesktopGroupMembershipRecoveryHead(head)
        if (head.id == review.primaryHeadId) StatusText("签名指定的权限基准")
    }
    review.members.forEach { member ->
        DesktopGroupMembershipRecoveryMember(member, review.heads)
        StatusText(if (member.fingerprint in review.activeDevices) "恢复后保留权限" else "恢复后没有同步权限")
    }
    StatusText("已签明新的同步密钥。原资料和所有签名历史会保留。")
    if (review.remainingFork) StatusText("本机还有此包未覆盖的签名分支。保留此包后，同步仍将暂停。")
    BootstrapButton(if (review.remainingFork) "确认保留恢复包" else "确认应用签名恢复包", enabled = !busy) {
        actions.confirmMembershipRecoveryImport(review.binding)
    }
    BootstrapButton("取消导入", secondary = true, enabled = !busy) { actions.cancelMembershipRecoveryImport() }
}

@Composable
private fun DesktopGroupMembershipRecoveryHead(head: LanSyncMembershipRecoveryHead) {
    SelectionContainer {
        Column {
            StatusText("分支末端序号 ${head.sequence} · ${head.id}")
            StatusText("签名记录摘要 ${head.bodyHash}")
        }
    }
}

@Composable
private fun DesktopGroupMembershipRecoveryMember(member: LanSyncMembershipRecoveryMember, heads: List<LanSyncMembershipRecoveryHead>) {
    StatusText(member.displayName + if (member.thisDevice) " · 本机" else "")
    SelectionContainer { StatusText("设备指纹 ${member.fingerprint}") }
    StatusText(if (member.activeAtCommon) "原共同记录允许此身份同步" else "原共同记录无此身份的同步权限")
    heads.forEach { head ->
        val authorization = when (head.id) {
            in member.revokedAtHeads -> "已撤销"
            in member.activeAtHeads -> "有权限"
            else -> "没有此身份"
        }
        StatusText("分支 ${head.id.take(12)} · $authorization")
    }
    member.defaultActive?.let { StatusText(if (it) "建议选择继续同步" else "建议选择撤销权限") }
}

/** Preserve the exact v1 invitation text and four white quiet-zone modules. */
internal fun desktopGroupInvitationQrMatrix(code: String): BitMatrix? = try {
    QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0, mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 4))
} catch (_: WriterException) {
    null // Copy remains available when a valid invitation exceeds QR capacity.
}

/** Never squeeze modules into fractional pixels or show a one-pixel scan target. */
internal fun desktopGroupInvitationQrImage(matrix: BitMatrix, maximumPixels: Int): BufferedImage? {
    val pixelsPerModule = maximumPixels / matrix.width
    if (pixelsPerModule < 2) return null
    val side = matrix.width * pixelsPerModule
    return BufferedImage(side, side, BufferedImage.TYPE_INT_RGB).apply {
        for (y in 0 until side) for (x in 0 until side)
            setRGB(x, y, if (matrix[x / pixelsPerModule, y / pixelsPerModule]) 0x000000 else 0xffffff)
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
            val display = view?.let { LanSyncGroupDisplay.unit(it, unit) } ?: LanSyncGroupDisplay.unit(unit, emptyList())
            StatusText(display.identifier)
            DesktopGroupReviewButton("查看 ${display.kind} · ${display.title} · ${row?.let(::desktopGroupFrozenStage).orEmpty()}",
                { view?.let { actions.closeImagePreview(); selected = it to unit } })
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
    val display = LanSyncGroupDisplay.unit(frozen.first, unit)
    StatusText("已冻结：${display.kind} · ${display.title}")
    StatusText(display.identifier)
    LanSyncGroupDisplay.missingLocalImageNotice(row.code)?.let { StatusText(it) }
    StatusText("同步继续运行；这里保留所选检查结果，重新选择内容组可查看新的结果。图片从本次冻结内容对应的本机缓存中读取。")
    DesktopGroupReviewButton("关闭冻结详情", { actions.closeImagePreview(); selected = null })
    if (unit !in frozen.first.reservedUnits && row.stage in setOf(LanSyncGroupNativeStage.LOCAL_PROPOSAL,
            LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW, LanSyncGroupNativeStage.CONFLICT,
            LanSyncGroupNativeStage.RECORD_REVIEW, LanSyncGroupNativeStage.DEPENDENCY_HELD)) {
        DesktopGroupReviewButton("核对本机发布目标", { actions.prepareSourceReview(frozen.first, unit) }, enabled = !busy)
    }
    if (unit in frozen.first.incomingReview?.reductions.orEmpty()) {
        DesktopGroupReviewButton("核对传入删除或覆盖", { actions.prepareIncomingReview(frozen.first, unit) }, enabled = !busy)
    }
    DesktopLanSyncGroupResolutionChoices(frozen.first, unit, busy, actions)
    val imageContext = LanSyncGroupFrozenImageContext.Inspection(frozen.first)
    val current = remember(plan, unit) { plan.before.units()[unit].orEmpty() }
    key(frozen.first, unit) {
        DesktopGroupFrozenRecords("本次检查时的本机内容", unit, current, imageContext, actions)
        row.frontiers.sortedBy { it.version.id.value }.forEach { frontier ->
            key(frontier.version) {
                StatusText("已签名版本：${frontier.version.id.value}")
                val incoming = row.incoming[frontier.version]
                when {
                    frontier.change.content == null -> StatusText("此版本为删除标记，没有正文记录。")
                    incoming != null -> DesktopGroupFrozenRecords("已取得并验证的传入版本", unit, incoming.decoded.payload.records, imageContext, actions)
                    else -> StatusText("此版本的正文或附件尚未完整取得，暂不能显示完整内容。")
                }
            }
        }
        frozen.first.incomingReview?.reductions?.get(unit)?.let { reduction ->
            DesktopGroupFrozenRecords("待确认的传入目标", unit, reduction.proposed, imageContext, actions)
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
    val imageContext = LanSyncGroupFrozenImageContext.Recovery(view)
    StatusText("正在浏览的历史保全")
    DesktopGroupRecoveryMetadata(view.entry)
    StatusText("此处展示已保全的历史内容。准备恢复会先生成核对目标，确认后才修改本机资料。")
    DesktopGroupReviewButton("关闭保全浏览", actions::closeRecovery, enabled = !busy)
    val units = remember(view) { view.units.keys.sorted() }
    var unitPage by remember(view) { mutableStateOf(0) }
    units.drop(unitPage * 10).take(10).forEach { unit ->
        key(view, unit) {
            val display = LanSyncGroupDisplay.unit(unit, view.units.getValue(unit))
            StatusText("历史内容组：${display.kind} · ${display.title}")
            StatusText(display.identifier)
            DesktopGroupFrozenRecords("历史保全内容", unit, view.units.getValue(unit), imageContext, actions)
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
    val imageContext = LanSyncGroupFrozenImageContext.Action(review)
    var confirmed by remember(review.binding) { mutableStateOf(false) }
    var unitPage by remember(review.binding) { mutableStateOf(0) }
    val recovery = review.recovery
    val resolution = review.resolution
    val canConfirm = review.canConfirm && (recovery?.hasChanges != false)
    DesktopDataSectionHeading(when { resolution != null -> "核对版本处理目标"; review.source != null -> "核对本机发布目标"; recovery != null -> "核对本机恢复目标"; else -> "核对传入删除或覆盖" })
    StatusText("以下关联内容按同一核对结果处理；请查看各组正文和影响后确认。确认时会再次检查资料是否变化。")
    val source = review.source
    if (resolution != null) {
        DesktopLanSyncGroupResolutionDetails(review, actions)
    } else if (recovery != null) {
        StatusText("恢复仅作用于所选内容组和必需的缺失依赖；其余本机内容保留。")
        StatusText("本次恢复的内容组：${recovery.restoredUnits.sorted().joinToString("、")}")
        DesktopGroupFrozenRecords("恢复前的本机内容", recovery.unit, recovery.currentRecords, imageContext, actions)
        DesktopGroupFrozenRecords("待恢复的目标内容", recovery.unit, recovery.restoredRecords, imageContext, actions)
        val impact = remember(review.binding) { LanSyncImpactReview.changes(recovery.currentRecords, recovery.restoredRecords) }
        StatusText(desktopGroupActionImpactSummary(impact))
        if (!recovery.hasChanges) StatusText("当前内容与恢复目标一致，没有需要写入的变化。")
    } else {
        val units = remember(review.binding) { source?.units?.keys?.sorted() ?: review.incomingBindings.keys.sorted() }
        StatusText("本次核对 ${units.size} 个内容组")
        units.drop(unitPage * 10).take(10).forEach { unit ->
            key(review.binding, unit) {
                val displayRows = source?.units?.get(unit)?.let { row -> row.current.ifEmpty { row.versions.values.flatten() } }
                    ?: review.incoming?.reductions?.get(unit)?.let { row -> row.current.ifEmpty { row.proposed } }.orEmpty()
                val display = LanSyncGroupDisplay.unit(unit, displayRows)
                StatusText("内容组：${display.kind} · ${display.title}")
                StatusText(display.identifier)
                if (source != null) {
                    val row = source.units.getValue(unit)
                    DesktopGroupFrozenRecords("准备发布的本机目标", unit, row.current, imageContext, actions)
                    row.versions.entries.sortedWith(compareBy({ it.key.id.value }, { it.key.bodyHash })).forEach { (version, records) ->
                        key(version) {
                            StatusText("已签名的核对版本：${version.id.value}")
                            StatusText("版本正文指纹：${version.bodyHash}")
                            DesktopGroupFrozenRecords("此已签名版本的正文", unit, records, imageContext, actions)
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
                        DesktopGroupFrozenRecords("核对时的本机内容", unit, row.current, imageContext, actions)
                        DesktopGroupFrozenRecords("待应用的传入目标", unit, row.proposed, imageContext, actions)
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
    BootstrapButton(when { resolution != null -> "确认以上版本处理与暂缓"; source != null -> "确认发布以上本机目标"; recovery != null -> "确认恢复以上本机目标"; else -> "确认应用以上传入目标" },
        enabled = !busy && canConfirm && confirmed) { actions.confirmActionReview(review.binding) }
    DesktopGroupReviewButton("取消本次核对", actions::cancelActionReview, enabled = !busy)
}

internal fun desktopGroupActionImpactSummary(impact: List<LanSyncRecordImpact>): String {
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
internal fun DesktopGroupFrozenRecords(label: String, unit: String, records: List<LanSyncRecord>,
    imageContext: LanSyncGroupFrozenImageContext, actions: LanSyncGroupPanelActions) {
    var expanded by remember(records, label) { mutableStateOf(false) }
    DesktopGroupReviewButton((if (expanded) "收起" else "展开") + "$label · ${records.size} 条记录",
        { if (expanded) actions.closeImagePreview(); expanded = !expanded })
    if (!expanded) return
    val detail = remember(records, unit) { LanSyncDetailedReview.detail(unit, records, emptyList()) }
    val keys = detail.recordKeys
    if (keys.isEmpty()) { StatusText("此内容没有记录。"); return }
    var recordPage by remember(detail) { mutableStateOf(0) }
    var selectedKey by remember(detail) { mutableStateOf<String?>(null) }
    keys.drop(recordPage * 10).take(10).forEach { recordKey ->
        val display = detail.record(recordKey, true)?.let(LanSyncGroupDisplay::record)
        StatusText(display?.identifier ?: recordKey)
        DesktopGroupReviewButton(display?.let { "${it.kind} · ${it.title}" } ?: recordKey, { actions.closeImagePreview(); selectedKey = recordKey })
    }
    if (keys.size > 10) ActionRow {
        DesktopGroupReviewButton("上一页记录", { recordPage-- }, enabled = recordPage > 0)
        StatusText("${recordPage + 1}/${(keys.size + 9) / 10}")
        DesktopGroupReviewButton("下一页记录", { recordPage++ }, enabled = (recordPage + 1) * 10 < keys.size)
    }
    val recordKey = selectedKey ?: return
    val imageRecord = detail.record(recordKey, true)
    val images = remember(detail, recordKey) { imageRecord?.let(LanSyncReviewImages::references).orEmpty() }
    var imagePage by remember(detail, recordKey) { mutableStateOf(0) }
    images.drop(imagePage * 10).take(10).forEachIndexed { index, image ->
        DesktopGroupReviewButton("查看冻结图片 ${imagePage * 10 + index + 1} · ${image.field}", {
            imageRecord?.let { actions.previewImage(imageContext, it, image.reference) }
        })
    }
    if (images.size > 10) ActionRow {
        DesktopGroupReviewButton("上一页图片", { imagePage-- }, enabled = imagePage > 0)
        StatusText("${imagePage + 1}/${(images.size + 9) / 10}")
        DesktopGroupReviewButton("下一页图片", { imagePage++ }, enabled = (imagePage + 1) * 10 < images.size)
    }
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

private fun desktopGroupFrozenStage(row: LanSyncGroupNativeUnit): String = when (row.stage) {
    LanSyncGroupNativeStage.UNCHANGED -> "无变化"
    LanSyncGroupNativeStage.READY_CONTENT -> "传入内容已就绪"
    LanSyncGroupNativeStage.READY_DELETION -> "传入删除"
    LanSyncGroupNativeStage.READY_OBSERVATION -> "内容一致"
    LanSyncGroupNativeStage.LOCAL_PROPOSAL -> "本机修改"
    LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW -> "本机删除或覆盖待核对"
    LanSyncGroupNativeStage.CONFLICT -> "多版本待选择"
    LanSyncGroupNativeStage.AUTHOR_FORK -> "同一设备版本分叉"
    LanSyncGroupNativeStage.WAITING_DEPENDENCY -> "等待正文或附件"
    LanSyncGroupNativeStage.LOCAL_RESOURCE_BLOCKED ->
        if (LanSyncGroupDisplay.missingLocalImageNotice(row.code) != null) "本机图片文件缺失" else "本会话图片暂缓处理"
    LanSyncGroupNativeStage.RECORD_REVIEW -> "记录归属待核对"
    LanSyncGroupNativeStage.DEPENDENCY_HELD -> "关联内容暂缓"
    LanSyncGroupNativeStage.INVALID_MIXED_GRAPH -> "关联资料尚未通过检查"
}

@Composable
internal fun DesktopGroupReviewButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    BootstrapButton(label, enabled = enabled, secondary = true, onClick = onClick)
}

@Composable private fun groupRoute(name: String, status: LanSyncGroupRouteStatus, observation: String) {
    StatusText("$name · ${status.text}")
    status.lastSuccessAt?.let { StatusText("$observation：${DateFormat.getDateTimeInstance().format(Date(it))}") }
}
