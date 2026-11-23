package com.example.chatbar.lanSync

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.ui.kit.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.DateFormat
import java.util.Date

/** Pure screen over real owner actions. Leaving it locks management without stopping app sync. */
@Composable
internal fun LanSyncGroupPanel(state: LanSyncGroupPanelState, actions: LanSyncGroupPanelActions,
    view: LanSyncGroupNodeView? = null, actionReview: LanSyncGroupActionReview? = null,
    recoveryEntries: List<LanSyncRecoveryEntry> = emptyList(), recoveryView: LanSyncGroupRecoveryView? = null,
    imageState: LanSyncGroupImageState = LanSyncGroupImageState(), pageControls: @Composable () -> Unit = {},
    scanInvitation: () -> Unit) {
    val interactionBlocked = state.busy || (state.paused && state.stage == LanSyncGroupPanelStage.READY)
    val context = LocalContext.current
    DisposableEffect(actions) {
        onDispose { actions.cancelActionReview(); actions.closeRecovery(); actions.closeImagePreview() }
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
    LanSyncGroupViewport(pageControls) {
        CbText("设备组同步", style = ChatBarTheme.typography.title)
        CbText(state.status)
        state.error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
        state.membershipRecoveryImport?.let { review ->
            GroupMembershipRecoveryImport(review, state.busy, actions)
            return@LanSyncGroupViewport
        }
        if (state.stage == LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT) {
            CbText("同步和成员变更已暂停，本机资料与签名记录均已保留。请由设备组管理员核对这些分支及各自的签发设备。")
            state.membershipConflict?.let { conflict ->
                CbText("共同账本记录为第 ${conflict.commonSequence} 条。已核实 ${conflict.totalHeads} 个分支均由原设备组管理身份签名。")
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        conflict.branches.forEach { branch ->
                            CbText("签名分支 ${branch.id}")
                            branch.added.forEach { CbText("批准设备 ${it}") }
                            branch.revoked.forEach { CbText("撤销设备 ${it}") }
                        }
                    }
                }
                if (conflict.branches.size < conflict.totalHeads) CbText("此处显示 ${conflict.branches.size} 个分支，其余签名记录仍保留。")
            }
            GroupMembershipRecovery(state, actions)
            return@LanSyncGroupViewport
        }
        imageState.error?.let { CbText(it, color = ChatBarTheme.colors.warning) }
        if (copyFailed) CbText("未能复制，请重试。", color = ChatBarTheme.colors.destructive)
        if (state.stage == LanSyncGroupPanelStage.EMPTY) {
            CbText(lanSyncGroupDescription)
            if (state.shelvedJoins.isEmpty()) CbButton("创建新设备组", actions::createGroup, enabled = !interactionBlocked)
            else CbText("加入其他设备组后，需先处理当前组，才能恢复暂存申请。")
            state.shelvedJoins.forEach { saved ->
                CbButton("恢复原加入申请 · ${saved.group.take(8)}", { actions.restoreShelvedJoin(saved.operation) },
                    enabled = !interactionBlocked, variant = ButtonVariant.Secondary)
            }
        }
        if (state.canEnterInvitation) {
            val joining = state.stage == LanSyncGroupPanelStage.JOINING
            CbButton(if (joining) "扫描更新邀请" else "扫描设备组邀请", scanInvitation, enabled = !interactionBlocked, variant = ButtonVariant.Secondary)
            CbField(if (joining) "更新邀请 · 粘贴新邀请" else "加入已有设备组 · 粘贴邀请") {
                key(state.privacyRevision) { CbInput(joinCode, { joinCode = it }, secure = true, enabled = !interactionBlocked) }
            }
            CbButton(if (joining) "更新邀请并继续" else "检查并加入", { val captured = joinCode; joinCode = ""; actions.beginJoin(captured) },
                enabled = !interactionBlocked && joinCode.isNotBlank(), variant = ButtonVariant.Secondary)
        }
        state.recoveryCode?.let { code ->
            CbText("请保存恢复码。丢失设备后，还需要另一台已加入设备或对应的加密管理资料；仅有恢复码无法恢复。")
            Box(Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState())) { CbText(code) }
            CbButton("复制恢复码", { copy(code) }, enabled = !interactionBlocked, variant = ButtonVariant.Secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbCheckbox(saved, { saved = it }, enabled = !interactionBlocked, contentDescription = "已妥善保存恢复码")
                CbText("我已妥善保存恢复码")
            }
            state.setupBinding?.let { binding -> CbButton("完成创建", { actions.recoverySaved(binding) }, enabled = !interactionBlocked && saved) }
        }
        if (state.localManagementAvailable && !state.managementUnlocked &&
            state.stage in setOf(LanSyncGroupPanelStage.PREPARED_LOCKED, LanSyncGroupPanelStage.READY)) {
            val continuing = state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED
            CbField(if (continuing) "为该组保存的恢复码" else "恢复码 · 临时解锁管理") {
                key(state.privacyRevision) { CbInput(recoveryInput, { recoveryInput = it }, secure = true, enabled = !interactionBlocked) }
            }
            CbButton(if (continuing) "用恢复码继续设置" else "解锁管理", { val captured = recoveryInput; recoveryInput = ""; actions.unlockManagement(captured) },
                enabled = !interactionBlocked && recoveryInput.isNotBlank(), variant = ButtonVariant.Secondary)
        }
        if (state.stage == LanSyncGroupPanelStage.READY && !state.localManagementAvailable &&
            state.managementRecoveryAvailable && !state.managementUnlocked) {
            CbText("使用这台已加入设备保留的加密管理资料和恢复码，恢复原设备组的管理权。")
            CbField("恢复码 · 恢复设备组管理") {
                key(state.privacyRevision) { CbInput(recoveryInput, { recoveryInput = it }, secure = true, enabled = !interactionBlocked) }
            }
            CbButton("用恢复码恢复组管理", {
                val captured = recoveryInput; recoveryInput = ""; actions.recoverManagement(captured)
            }, enabled = !interactionBlocked && recoveryInput.isNotBlank(), variant = ButtonVariant.Secondary)
        }
        if (state.stage == LanSyncGroupPanelStage.PREPARED_LOCKED && state.canAbandonUnactivated)
            CbButton("放弃未完成的设置", actions::abandonUnusedSetup, enabled = !interactionBlocked, variant = ButtonVariant.Secondary)
        if (!state.recoveryJoining && state.stage == LanSyncGroupPanelStage.JOINING) {
            state.joiningDevice?.let { CbText("本机设备指纹，请与邀请设备上的身份逐字核对\n$it") }
            if (state.canArchiveRevokedJoin) CbButton("结束并归档已撤销的申请", actions::archiveRevokedJoin, enabled = !interactionBlocked)
            else if (!state.joiningMembershipRevoked) CbButton(if (state.joiningLedgerConflict) "重新核对原申请" else "继续加入", actions::retryJoin,
                enabled = state.canContinueJoin)
            if (state.canAbandonJoin)
                CbButton("放弃未发送的申请", actions::abandonUnprovedJoin, enabled = !interactionBlocked, variant = ButtonVariant.Secondary)
            if (state.canShelveJoin)
                CbButton("暂存原申请并重新加入", actions::shelveUnsettledJoin, enabled = !interactionBlocked, variant = ButtonVariant.Secondary)
            if (state.canRefreshJoinPreparation)
                CbButton("重新核对本机资料并继续", actions::refreshJoinPreparation, enabled = state.canContinueJoin, variant = ButtonVariant.Secondary)
        }
        if (state.stage == LanSyncGroupPanelStage.READY) {
            if (!state.localManagementAvailable && !state.managementRecoveryAvailable && !state.managementUnlocked)
                CbText("此设备可日常同步；管理恢复还需要对应的加密管理资料。")
            GroupRoute("本机", state.local, "最近保存"); GroupRoute("附近设备", state.nearby, "最近连接"); GroupRoute("网盘", state.cloud, "最近上传")
            state.nearbyNotice?.let { CbText(it) }
            CbButton("立即检查", actions::retry, enabled = !interactionBlocked, variant = ButtonVariant.Secondary)
            CbButton("网盘设置", actions::openCloudSettings, enabled = !interactionBlocked, variant = ButtonVariant.Secondary)
            CbButton("邀请设备", { actions.createInvitation() }, enabled = !interactionBlocked && state.managementUnlocked && state.managementCanInvite)
            if (state.managementUnlocked) {
                CbButton("生成邀请文件", { actions.createInvitation(forFile = true) },
                    enabled = !interactionBlocked && state.managementCanInvite, variant = ButtonVariant.Secondary)
                CbButton("锁定管理", actions::lockManagement, variant = ButtonVariant.Secondary)
                CbText("仅接续先前已确认的加入或撤销，不会重新决定设备成员。")
                CbButton("接续已确认的管理操作", actions::resumeManagementOperation, enabled = !interactionBlocked, variant = ButtonVariant.Secondary)
            }
            state.devices.forEach { device -> key(device.id) {
                CbText(device.name + if (device.thisDevice) " · 本机" else if (device.managementIdentity) " · 恢复管理身份" else "")
                CbText("设备指纹 ${device.id}")
                CbText(device.status)
                device.nearby?.let { nearby ->
                    CbText(nearby.stage.label)
                    nearby.lastSuccessAt?.let { CbText("最近连接：${DateFormat.getDateTimeInstance().format(Date(it))}") }
                }
                if (device.canRevoke) CbButton("撤销此设备", { actions.prepareRevocation(device.id) },
                    enabled = !interactionBlocked && state.managementUnlocked, variant = ButtonVariant.Destructive)
            } }
        }
        GroupFrozenReviewBrowser(view, interactionBlocked, actions)
        LanSyncGroupResolutionHistory(view, interactionBlocked, actions)
        actionReview?.let { review -> GroupActionReview(review, view?.plan, state.busy, actions) }
        if (state.stage == LanSyncGroupPanelStage.READY || recoveryEntries.isNotEmpty() || recoveryView != null)
            GroupRecoveryBrowser(recoveryEntries, recoveryView, interactionBlocked, actions)
        if (state.invitationRetainedWhileLocked) {
            CbText(if (state.invitationFileWriteBinding != null) "正在写入邀请文件" else "邀请仍在有效期内。请解锁管理以核对申请设备，或关闭邀请。")
            CbButton("关闭邀请", actions::closeInvitation, enabled = state.canCloseInvitation, variant = ButtonVariant.Secondary)
        }
        state.invitation?.takeUnless { state.invitationFileOnly }?.let { offer ->
            val code = remember(offer) { offer.code() }
            LanSyncGroupInvitationQr(code)
            CbButton("复制邀请", { copy(code) }, variant = ButtonVariant.Secondary)
            CbButton("关闭邀请", actions::closeInvitation, enabled = state.canCloseInvitation, variant = ButtonVariant.Secondary)
            state.invitationAddresses.filter { it != offer.host }.forEach { address ->
                CbButton("用 $address 生成新邀请", { actions.createInvitation(address) },
                    enabled = !interactionBlocked && state.managementUnlocked, variant = ButtonVariant.Secondary)
            }
        }
        state.peerConfirmation?.let { peer ->
            CbText(if (state.invitationApprovalInProgress) "正在批准 ${peer.name}" else "待批准 · 允许 ${peer.name} 加入设备组？")
            CbText("设备身份：${peer.fingerprint}")
            CbButton("确认加入此设备", { actions.acceptPeer(peer.binding) }, enabled = !interactionBlocked && state.managementUnlocked)
        }
        state.revocation?.let { review ->
            CbText("撤销 ${review.name}？")
            CbText("设备指纹 ${review.fingerprint}")
            CbText("该设备将无法取得新的同步密钥，已保存的副本仍会保留。网盘账号授权需要单独撤销。")
            CbButton("确认撤销", { actions.confirmRevocation(review.binding) }, enabled = !interactionBlocked && state.managementUnlocked, variant = ButtonVariant.Destructive)
            CbButton("取消", actions::cancelRevocation, enabled = !interactionBlocked, variant = ButtonVariant.Secondary)
        }
        Spacer(Modifier.height(12.dp))
    }
    imageState.preview?.let { preview -> LanSyncFrozenImageDialog(preview, actions::closeImagePreview) }

}

@Composable
private fun LanSyncGroupViewport(pageControls: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
        pageControls()
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun GroupMembershipRecovery(state: LanSyncGroupPanelState, actions: LanSyncGroupPanelActions) {
    val review = state.membershipRecovery
    if (review == null) {
        var code by remember(state.privacyRevision) { mutableStateOf("") }
        CbField("原设备组恢复码") { CbInput(code, { code = it }, secure = true, enabled = !state.busy) }
        CbButton("核对成员恢复方案", {
            val submitted = code
            code = ""
            actions.prepareMembershipRecovery(submitted)
        }, enabled = !state.busy && code.isNotBlank(), variant = ButtonVariant.Secondary)
        return
    }
    var primaryHeadId by remember(review.binding, state.privacyRevision) { mutableStateOf<String?>(null) }
    val choices = remember(review.binding, state.privacyRevision) { mutableStateMapOf<String, Boolean>() }
    val selectable = review.members.filter { it.authority == LanSyncMembershipRecoveryAuthority.CHOICE }
    CbText("选择一个已核实的分支作为同步权限基准。另一分支尚未获共同认可的聊天仍留在原设备，可回到本机会话查看。")
    review.retainedPrimaryHeadId?.let { CbText("待续办的原权限基准为 ${it.take(12)}。请核对先前授权后继续。") }
    review.heads.forEach { head ->
        key(head.id) {
            GroupMembershipRecoveryHead(head)
            CbButton(
                if (primaryHeadId == head.id) "已选为权限基准" else "选为权限基准 · ${head.id.take(12)}",
                { primaryHeadId = head.id }, enabled = !state.busy && primaryHeadId != head.id,
                variant = ButtonVariant.Secondary,
            )
        }
    }
    CbText("明确核对每台设备的权限。曾被有效撤销的身份无法恢复。")
    review.members.forEach { member ->
        key(member.fingerprint) {
            GroupMembershipRecoveryMember(member, review.heads)
            when (member.authority) {
                LanSyncMembershipRecoveryAuthority.REQUIRED_ACTIVE -> CbText("保留此身份的权限")
                LanSyncMembershipRecoveryAuthority.REVOKED -> CbText("保留撤销记录，不发放新同步密钥")
                LanSyncMembershipRecoveryAuthority.CHOICE -> {
                    val choice = choices[member.fingerprint]
                    CbText(when (choice) {
                        true -> "已选继续同步"
                        false -> "已选撤销权限"
                        null -> "请选择恢复后的权限"
                    })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CbButton("继续同步", { choices[member.fingerprint] = true }, enabled = !state.busy && choice != true,
                            variant = ButtonVariant.Secondary)
                        CbButton("撤销权限", { choices[member.fingerprint] = false }, enabled = !state.busy && choice != false,
                            variant = ButtonVariant.Secondary)
                    }
                }
            }
        }
    }
    CbText("签发会轮换同步密钥。恢复后有权限的设备可获得新密钥，已撤销的设备仍保留原本机副本。")
    CbButton("确认权限并签发恢复包", {
        val active = review.members.filter { member ->
            member.authority == LanSyncMembershipRecoveryAuthority.REQUIRED_ACTIVE || choices[member.fingerprint] == true
        }.mapTo(linkedSetOf()) { it.fingerprint }
        actions.confirmMembershipRecovery(review.binding, requireNotNull(primaryHeadId), active)
    }, enabled = !state.busy && primaryHeadId != null && selectable.all { it.fingerprint in choices })
    CbButton("锁定恢复审阅", actions::lockMembershipRecovery, enabled = !state.busy, variant = ButtonVariant.Secondary)
}

@Composable
private fun GroupMembershipRecoveryImport(review: LanSyncMembershipRecoveryImportReview, busy: Boolean, actions: LanSyncGroupPanelActions) {
    CbText("核对已验证的签名恢复包", style = ChatBarTheme.typography.title)
    CbText("共同账本记录为第 ${review.commonSequence} 条。")
    review.heads.forEach { head ->
        GroupMembershipRecoveryHead(head)
        if (head.id == review.primaryHeadId) CbText("签名指定的权限基准")
    }
    review.members.forEach { member ->
        GroupMembershipRecoveryMember(member, review.heads)
        CbText(if (member.fingerprint in review.activeDevices) "恢复后保留权限" else "恢复后没有同步权限")
    }
    CbText("已签明新的同步密钥。原资料和所有签名历史会保留。")
    if (review.remainingFork) CbText("本机还有此包未覆盖的签名分支。保留此包后，同步仍将暂停。")
    CbButton(if (review.remainingFork) "确认保留恢复包" else "确认应用签名恢复包",
        { actions.confirmMembershipRecoveryImport(review.binding) }, enabled = !busy)
    CbButton("取消导入", actions::cancelMembershipRecoveryImport, enabled = !busy, variant = ButtonVariant.Secondary)
}

@Composable
private fun GroupMembershipRecoveryHead(head: LanSyncMembershipRecoveryHead) {
    SelectionContainer {
        Column {
            CbText("分支末端序号 ${head.sequence} · ${head.id}")
            CbText("签名记录摘要 ${head.bodyHash}")
        }
    }
}

@Composable
private fun GroupMembershipRecoveryMember(member: LanSyncMembershipRecoveryMember, heads: List<LanSyncMembershipRecoveryHead>) {
    CbText(member.displayName + if (member.thisDevice) " · 本机" else "")
    SelectionContainer { CbText("设备指纹 ${member.fingerprint}") }
    CbText(if (member.activeAtCommon) "原共同记录允许此身份同步" else "原共同记录无此身份的同步权限")
    heads.forEach { head ->
        val authorization = when (head.id) {
            in member.revokedAtHeads -> "已撤销"
            in member.activeAtHeads -> "有权限"
            else -> "没有此身份"
        }
        CbText("分支 ${head.id.take(12)} · $authorization")
    }
    member.defaultActive?.let { CbText(if (it) "建议选择继续同步" else "建议选择撤销权限") }
}

private const val GROUP_INVITATION_QR_MAX_PIXELS = 1024
private const val GROUP_INVITATION_QR_MIN_MODULE_PIXELS = 2

@Composable
internal fun LanSyncGroupInvitationQr(code: String) {
    val matrix = remember(code) { androidGroupInvitationQrMatrix(code) }
    if (matrix == null) {
        CbText("此邀请无法显示二维码。请复制邀请，或生成邀请文件。")
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val preferredPixels = with(density) { 280.dp.roundToPx() }
        val minimumPixels = matrix.width * GROUP_INVITATION_QR_MIN_MODULE_PIXELS
        val availablePixels = minOf(constraints.maxWidth, constraints.maxHeight,
            maxOf(preferredPixels, minimumPixels), GROUP_INVITATION_QR_MAX_PIXELS)
        val modulePixels = availablePixels / matrix.width
        if (modulePixels < GROUP_INVITATION_QR_MIN_MODULE_PIXELS) {
            CbText("可用宽度不足，无法清晰显示完整二维码。请复制邀请，或生成邀请文件。")
        } else {
            val image = remember(matrix, modulePixels) { androidGroupInvitationQrImage(matrix, modulePixels) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Canvas(Modifier.size(with(density) { image.width.toDp() }).semantics {
                    contentDescription = "新设备扫描此邀请"
                    role = Role.Image
                }) {
                    drawImage(image, dstSize = IntSize(image.width, image.height), filterQuality = FilterQuality.None)
                }
                CbText("在新设备打开设备组同步，扫描此邀请，然后在这里核对设备身份。")
            }
        }
    }
}

/** Keep the exact v1 text and four white quiet-zone modules. */
internal fun androidGroupInvitationQrMatrix(code: String): BitMatrix? = try {
    QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0, mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 4))
} catch (_: WriterException) {
    null
}

private fun androidGroupInvitationQrImage(matrix: BitMatrix, modulePixels: Int): ImageBitmap {
    val side = matrix.width * modulePixels
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val row = IntArray(side)
    for (moduleY in 0 until matrix.height) {
        for (moduleX in 0 until matrix.width) {
            val color = if (matrix[moduleX, moduleY]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            row.fill(color, moduleX * modulePixels, (moduleX + 1) * modulePixels)
        }
        val rowStart = moduleY * modulePixels
        for (y in rowStart until rowStart + modulePixels) bitmap.setPixels(row, 0, side, 0, y, side, 1)
    }
    return bitmap.asImageBitmap()
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
            CbButton("查看 ${display.kind} · ${display.title} · ${row?.let(::groupFrozenStage).orEmpty()}",
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
    LanSyncGroupDisplay.missingLocalImageNotice(row.code)?.let { CbText(it, color = ChatBarTheme.colors.warning) }
    LanSyncGroupDisplay.localPresetReviewNotice(row.code)?.let { CbText(it) }
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
private fun GroupActionReview(review: LanSyncGroupActionReview, latestPlan: LanSyncGroupNativePlan?, busy: Boolean, actions: LanSyncGroupPanelActions) {
    val imageContext = LanSyncGroupFrozenImageContext.Action(review)
    var confirmed by remember(review.binding) { mutableStateOf(false) }
    var unitPage by remember(review.binding) { mutableStateOf(0) }
    val recovery = review.recovery
    val resolution = review.resolution
    val resourceBlocked = review.resolutionBlockedByLocalResources(latestPlan)
    val canConfirm = review.canConfirm && !resourceBlocked && (recovery?.hasChanges != false)
    if (resourceBlocked) CbText(LanSyncGroupDisplay.blockedResolutionImageNotice, color = ChatBarTheme.colors.warning)
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

private fun groupFrozenStage(row: LanSyncGroupNativeUnit): String = when (row.stage) {
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
