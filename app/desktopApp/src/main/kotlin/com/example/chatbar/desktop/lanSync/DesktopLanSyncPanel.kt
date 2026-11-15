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

/** Navigation owns only page visibility; the application owns the group task lifetime. */
@Composable
internal fun DesktopLanSyncEntry(coordinator: LanSyncGroupCoordinator,
    legacy: (() -> LanSyncRuntime)?, hasGroupEvidence: () -> Boolean, filePicker: DesktopFilePicker? = null) {
    val state by coordinator.state.collectAsState()
    val view by coordinator.view.collectAsState()
    val actionReview by coordinator.actionsReview.collectAsState()
    val recoveryEntries by coordinator.recoveryEntries.collectAsState()
    val recoveryView by coordinator.recoveryView.collectAsState()
    val imageState by coordinator.images.collectAsState()
    val cloudSettings by coordinator.cloudSettings.collectAsState()
    val cloudRecoveryOwner = remember(coordinator) { coordinator.cloudRecovery }
    var legacySelected by remember(coordinator) { mutableStateOf(false) }
    LaunchedEffect(coordinator) { coordinator.open() }
    DisposableEffect(coordinator, cloudRecoveryOwner) { onDispose { cloudRecoveryOwner?.hide(); coordinator.lockManagement() } }
    val legacyAllowed = !hasGroupEvidence() && state.stage == LanSyncGroupPanelStage.EMPTY
    Column(Modifier.fillMaxSize()) {
        if (legacySelected && legacyAllowed && legacy != null) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                BootstrapButton("返回设备组同步", secondary = true) { legacySelected = false }
            }
            Box(Modifier.weight(1f)) { DesktopLanSyncPanel(remember(legacy) { legacy() }) }
        } else {
            if (legacyAllowed && legacy != null) Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                BootstrapButton("打开旧版双端同步", secondary = true, enabled = !state.busy) { legacySelected = true }
            }
            if (cloudRecoveryOwner != null && (state.stage in setOf(LanSyncGroupPanelStage.EMPTY, LanSyncGroupPanelStage.JOINING) || state.canResumeExternalRecovery)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    BootstrapButton(if (state.stage == LanSyncGroupPanelStage.READY) "接续网盘恢复与连接设置" else "从网盘恢复设备组", secondary = true, enabled = !state.busy) { coordinator.openCloudRecovery() }
                }
            }
            DesktopLanSyncFileActions(coordinator, state, filePicker)
            Box(Modifier.weight(1f)) { DesktopLanSyncGroupPanel(state, coordinator, view, actionReview, recoveryEntries, recoveryView, imageState) }
        }
    }
    DesktopLanSyncCloudSettings(cloudSettings, coordinator)
    cloudRecoveryOwner?.let { recovery ->
        val recoveryState by recovery.state.collectAsState()
        DesktopLanSyncCloudRecovery(recoveryState, state.privacyRevision, { draft, code -> coordinator.prepareCloudRecovery(draft, code); Unit }, recovery::confirm,
            { recovery.lock(); coordinator.open() }, recovery::hide)
    }
}

@Composable
internal fun DesktopLanSyncPanel(runtime: LanSyncRuntime) {
    val state by runtime.state.collectAsState()
    DisposableEffect(runtime) { runtime.startHost(); onDispose { runtime.stop() } }
    val selected = remember { mutableStateMapOf<String, LanSyncChoice>() }
    LaunchedEffect(state.reviewBinding) { selected.clear() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DesktopDataSectionHeading("局域网同步")
        StatusText(LanSyncScope.description)
        StatusText(state.status)
        state.cleanupNotice?.let { StatusText(it, DesktopBootstrapColors.warning) }
        state.imagePreviewError?.let { StatusText(it, DesktopBootstrapColors.warning) }
        if (remember { DesktopBuildIdentity.load().version.substringAfter('-', "").startsWith("lan.") })
            BootstrapButton("载入离线验收样例", secondary = true, enabled = !state.busy) { runtime.createPreviewSample() }
        ActionRow {
            BootstrapButton("同步 / 接续", enabled = !state.busy && state.peers.isNotEmpty()) { runtime.requestSync() }
            state.addresses.forEach { address ->
                BootstrapButton("配对新设备 · $address", secondary = true, enabled = !state.busy) { runtime.pairOffer(address) }
            }
        }
        state.offer?.let { offer ->
            val code = remember(offer) { offer.code() }
            val bitmap = remember(code) {
                val matrix = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 320, 320)
                BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB).apply {
                    for (y in 0 until 320) for (x in 0 until 320) setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xffffff)
                }.toComposeImageBitmap()
            }
            Image(bitmap, "手机扫码配对", Modifier.size(280.dp))
            StatusText("安卓：管理 → 局域网同步 → 扫描电脑二维码，然后核对设备身份并确认。配对码两分钟有效。")
            StatusText("电脑身份：${offer.fingerprint.take(16)}")
            ActionRow {
                BootstrapButton("复制备用配对码", secondary = true) {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(code), null)
                }
                BootstrapButton("关闭配对", secondary = true) { runtime.cancelPairing() }
            }
        }
        if (state.conflicts.isNotEmpty()) {
            StatusText("双方都修改的内容需选择；保留两份会保留独立会话分支及其依赖。")
            state.conflicts.forEach { conflict ->
                StatusText("${conflict.title} · 电脑 ${if (conflict.computerDeleted) "已删除" else "${conflict.computerRecords} 条"} / 手机 ${if (conflict.phoneDeleted) "已删除" else "${conflict.phoneRecords} 条"}")
                state.reviewDetails.firstOrNull { it.header.unit == conflict.unit }?.let { DesktopLanSyncDetail(it, runtime = runtime) }
                if (!conflict.canKeepBoth) StatusText("此项是全局设定，请选择保留其中一端的版本。")
                ActionRow {
                    listOf(LanSyncChoice.KEEP_COMPUTER to "保留电脑", LanSyncChoice.KEEP_PHONE to "保留手机", LanSyncChoice.KEEP_BOTH to "保留两份")
                        .filter { it.first != LanSyncChoice.KEEP_BOTH || conflict.canKeepBoth }.forEach { (choice, label) ->
                        BootstrapButton((if (selected[conflict.unit] == choice) "✓ " else "") + label, secondary = selected[conflict.unit] != choice) { selected[conflict.unit] = choice }
                    }
                }
            }
            BootstrapButton("按以上选择继续", enabled = state.conflicts.all { it.unit in selected }) { runtime.resolveConflicts(selected.toMap(), state.reviewBinding) }
        }
        if (state.pendingReviews.isNotEmpty()) {
            StatusText("以下删除或精简尚未执行；各端现状保留，其余独立内容已同步。")
            state.pendingReviews.forEach { held ->
                val impact = remember(held.binding) { LanSyncImpactReview.reductionSummary(held.computer, held.phone, held.proposed) }
                StatusText(held.unit + if (held.dependencyOf.isEmpty()) " · 待确认" else " · 随关联项暂缓")
                if (held.dependencyIssue) StatusText("有关联会话继续依赖此内容，当前目标不能单独执行；请先核对或调整关联内容，再重新比较。", DesktopBootstrapColors.warning)
                StatusText("两端合计将移除 ${impact.recordsRemoved} 条记录（其中 ${impact.liveMessagesRemoved} 条实时消息）、${impact.versionsRemoved} 个消息版本、${impact.attachmentReferencesRemoved} 条附件引用；覆盖 ${impact.recordsOverwritten} 条记录。")
                val detail = remember(held.binding) { LanSyncDetailedReview.detail(held.unit, held.computer, held.phone, held.ancestorHashes[held.unit]) }
                val header = detail.header
                DesktopLanSyncDetail(detail, runtime = runtime)
                StatusText("确认后的目标共 ${held.proposed.size} 条记录；目标为空表示移除此组。")
                if (held.proposed.isNotEmpty()) DesktopLanSyncDetail(LanSyncDetailedUnit(
                    header.copy(unit = header.unit + "/target"), held.proposed, emptyList()), singleSide = true, runtime = runtime)
                if (held.dependencyOf.isEmpty()) BootstrapButton("确认按此目标同步删除或精简", secondary = true, enabled = !state.busy && !held.dependencyIssue) {
                    runtime.confirmReview(held.unit, held.binding)
                }
            }
        }
        BootstrapButton("查看本机保全与恢复记录", secondary = true, enabled = !state.busy) { runtime.refreshRecovery() }
        var recoveryPage by remember(state.recoveryEntries) { mutableStateOf(0) }
        val recoveryPages = maxOf(1, (state.recoveryEntries.size + 9) / 10)
        state.recoveryEntries.drop(recoveryPage * 10).take(10).forEach { entry ->
            StatusText("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.createdAt))} · ${entry.affectedUnits} 组受影响内容 · ${entry.recordCount} 条记录 / ${entry.messageCount} 条实时消息")
            StatusText(if (entry.hasLocalOriginals) "本机原始内容与本机选择已保全" else "对端同步内容已保全；不含对端账户和本机模型选择")
            BootstrapButton("浏览此保全记录", secondary = true, enabled = !state.busy) { runtime.openRecovery(entry.transaction) }
        }
        if (recoveryPages > 1) ActionRow {
            BootstrapButton("上一页记录", secondary = true, enabled = !state.busy && recoveryPage > 0) { recoveryPage-- }
            StatusText("${recoveryPage + 1}/$recoveryPages")
            BootstrapButton("下一页记录", secondary = true, enabled = !state.busy && recoveryPage + 1 < recoveryPages) { recoveryPage++ }
        }
        state.recoveryBrowse?.let { browse ->
            StatusText("恢复只作用于所选内容组和必需的缺失依赖；其他本机内容保留。")
            browse.units.sorted().forEach { unit ->
                BootstrapButton("预览恢复 · $unit", secondary = true, enabled = !state.busy) { runtime.previewRecovery(browse.entry.transaction, unit) }
            }
        }
        state.recoveryPreview?.let { preview ->
            val header = LanSyncReviewHeader("recovery/${preview.id}", null, null, null,
                preview.currentRecords.map { it.key }.toSet(), preview.restoredRecords.map { it.key }.toSet())
            val impact = remember(preview.id) { LanSyncImpactReview.reductionSummary(preview.currentRecords, emptyList(), preview.restoredRecords) }
            StatusText("本机将移除 ${impact.recordsRemoved} 条记录、${impact.versionsRemoved} 个消息版本和 ${impact.attachmentReferencesRemoved} 条附件引用，覆盖 ${impact.recordsOverwritten} 条记录；写入前会再次保全当前内容。")
            DesktopLanSyncDetail(LanSyncDetailedUnit(header, preview.currentRecords, emptyList()), singleSide = true, singleLabel = "当前本机", runtime = runtime)
            DesktopLanSyncDetail(LanSyncDetailedUnit(header.copy(unit = header.unit + "/target"), preview.restoredRecords, emptyList()), singleSide = true, singleLabel = "恢复目标", runtime = runtime)
            if (preview.restoredUnits.size > 1) StatusText("同时恢复必需依赖：${preview.restoredUnits.joinToString("、")}")
            ActionRow {
                BootstrapButton("恢复此组为本机新修订", enabled = !state.busy && preview.hasChanges) { runtime.applyRecovery(preview.id) }
                BootstrapButton("取消恢复预览", secondary = true, enabled = !state.busy) { runtime.cancelRecoveryPreview() }
            }
        }
        state.peers.forEach { peer ->
            StatusText("已记住：${peer.name} · ${peer.fingerprint.take(16)}")
            BootstrapButton("撤销配对", secondary = true, enabled = !state.busy) { runtime.revoke(peer.fingerprint) }
        }
        lanSyncExcludedSummary(state.excluded)?.let { StatusText(it) }
        StatusText("保持两端同步页面打开。首次连接如 Windows 提示网络访问，仅允许可信的专用网络；不要关闭防火墙。连接失败可重新生成二维码或使用备用配对码。")
    }
    state.imagePreview?.let { preview ->
        DesktopTransientImagePreview(preview.id, { runtime.readImagePreview(preview.id) },
            previewNote = "冻结静态预览 · 原始图片完整保留", onClose = runtime::closeImagePreview)
    }
}

@Composable
private fun DesktopLanSyncDetail(detail: LanSyncDetailedUnit, singleSide: Boolean = false, singleLabel: String = "目标", runtime: LanSyncRuntime) {
    var expanded by remember(detail.header) { mutableStateOf(false) }
    BootstrapButton(if (expanded) "收起内容" else if (singleSide) "查看${singleLabel}内容" else "查看双方正文、版本与存储字段", secondary = true) { expanded = !expanded }
    if (!expanded) return
    StatusText("${if (singleSide) singleLabel else "电脑"}：${detail.computer.size} 条记录 / ${detail.computerMessages} 条实时消息 / ${detail.computerArchivedMessages} 条存档消息")
    if (!singleSide) {
        StatusText("手机：${detail.phone.size} 条记录 / ${detail.phoneMessages} 条实时消息 / ${detail.phoneArchivedMessages} 条存档消息")
        StatusText("比较起点仅有指纹，无法显示其历史正文。${detail.header.ancestorHash?.let { " 指纹：$it" } ?: " 此项没有共同起点。"}")
    }
    var recordIndex by remember(detail.header) { mutableStateOf(0) }
    var fieldIndex by remember(detail.header, recordIndex) { mutableStateOf(0) }
    var page by remember(detail.header, recordIndex, fieldIndex) { mutableStateOf(0) }
    val keys = detail.recordKeys
    val key = keys.getOrNull(recordIndex) ?: return
    val images = remember(detail.header, key) {
        listOfNotNull(detail.record(key, true)?.let { row -> Triple(if (singleSide) singleLabel else "电脑", row, LanSyncReviewImages.references(row)) },
            detail.record(key, false)?.takeUnless { singleSide }?.let { row -> Triple("手机", row, LanSyncReviewImages.references(row)) })
            .flatMap { (label, row, refs) -> refs.map { Triple(label, row, it) } }
    }
    var imagePage by remember(detail.header, key) { mutableStateOf(0) }
    images.drop(imagePage * 10).take(10).forEachIndexed { index, (label, row, image) ->
        BootstrapButton("查看$label 图片 ${imagePage * 10 + index + 1} · ${image.field}", secondary = true) { runtime.previewImage(row, image.reference) }
    }
    if (images.size > 10) ActionRow {
        BootstrapButton("上一页图片", secondary = true, enabled = imagePage > 0) { imagePage-- }
        StatusText("${imagePage + 1}/${(images.size + 9) / 10}")
        BootstrapButton("下一页图片", secondary = true, enabled = (imagePage + 1) * 10 < images.size) { imagePage++ }
    }
    val fields = detail.fields(key)
    val field = fields.getOrNull(fieldIndex) ?: return
    StatusText("记录 ${recordIndex + 1}/${keys.size} · $key")
    ActionRow {
        BootstrapButton("上一条记录", secondary = true, enabled = recordIndex > 0) { recordIndex-- }
        BootstrapButton("下一条记录", secondary = true, enabled = recordIndex + 1 < keys.size) { recordIndex++ }
    }
    StatusText("字段 ${fieldIndex + 1}/${fields.size} · $field")
    ActionRow {
        BootstrapButton("上一字段", secondary = true, enabled = fieldIndex > 0) { fieldIndex-- }
        BootstrapButton("下一字段", secondary = true, enabled = fieldIndex + 1 < fields.size) { fieldIndex++ }
    }
    val left = detail.fieldPage(key, field, true, page); val right = detail.fieldPage(key, field, false, page)
    val pages = maxOf(left.second, right.second)
    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText("${if (singleSide) singleLabel else "电脑"} · ${if (detail.record(key, true) == null) "此端无此记录" else "字段正文"}")
        StatusText(left.first)
        if (!singleSide) {
            StatusText("手机 · ${if (detail.record(key, false) == null) "此端无此记录" else "字段正文"}")
            StatusText(right.first)
        }
    } }
    if (pages > 1) ActionRow {
        BootstrapButton("上一页正文", secondary = true, enabled = page > 0) { page-- }
        StatusText("${page + 1}/$pages")
        BootstrapButton("下一页正文", secondary = true, enabled = page + 1 < pages) { page++ }
    }
}
