package com.example.chatbar.lanSync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.chatbar.ChatBarApp
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.ui.kit.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class LanSyncActivity : ComponentActivity() {
    private val owner get() = ChatBarApp.instance.lanSyncOwner
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChatBarTheme { LanSyncEntry(owner) { finish() } } }
    }
    override fun onStop() { owner.lockGroupManagement(); owner.stopLegacy(); super.onStop() }
}

@Composable
private fun LanSyncEntry(owner: AndroidLanSync, close: () -> Unit) {
    val coordinator = remember(owner) { owner.groupCoordinator }
    val state by coordinator.state.collectAsState()
    var legacySelected by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(coordinator) { coordinator.open() }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(coordinator::beginJoin)
    }
    val legacyAllowed = !owner.hasGroupEvidence() && state.stage == LanSyncGroupPanelStage.EMPTY
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CbButton("返回", close, variant = ButtonVariant.Secondary)
            if (legacySelected) CbButton("设备组同步", { legacySelected = false }, variant = ButtonVariant.Secondary)
            else if (legacyAllowed) CbButton("旧版双端同步", { legacySelected = true }, enabled = !state.busy, variant = ButtonVariant.Secondary)
        }
        Box(Modifier.weight(1f)) {
            if (legacySelected && legacyAllowed) {
                val runtime = remember(owner) { owner.runtime }
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                DisposableEffect(runtime, lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> runtime.startPhone()
                            Lifecycle.Event.ON_STOP -> runtime.stop()
                            else -> Unit
                        }
                    }
                    lifecycle.addObserver(observer)
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) runtime.startPhone()
                    onDispose { lifecycle.removeObserver(observer); runtime.stop() }
                }
                LanSyncScreen(runtime, close)
            } else LanSyncGroupPanel(state, coordinator) {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("扫描设备组邀请二维码").setBeepEnabled(false).setOrientationLocked(false))
            }
        }
    }
}

@Composable
private fun LanSyncScreen(runtime: LanSyncRuntime, close: () -> Unit) {
    val state by runtime.state.collectAsState()
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<LanSyncPairOffer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<String, LanSyncChoice>() }
    LaunchedEffect(state.reviewBinding) { selected.clear() }
    fun inspect(value: String) {
        code = value
        val parsed = runCatching { LanSyncPairOffer.parse(value) }
        confirm = parsed.getOrNull()
        error = when {
            parsed.exceptionOrNull()?.message == "SYNC_PROTOCOL" -> "配对码来自其他同步版本，请使用匹配的双端安装包"
            confirm == null -> "无法识别或配对码已过期，请在电脑重新生成"
            else -> null
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::inspect) }
    Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background).statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CbText("局域网同步")
        CbText(LanSyncScope.description)
        CbText(state.status)
        state.cleanupNotice?.let { CbText(it, color = ChatBarTheme.colors.warning) }
        state.imagePreviewError?.let { CbText(it, color = ChatBarTheme.colors.warning) }
        if (com.example.chatbar.BuildConfig.BUILD_TYPE == "lanPreview")
            CbButton("载入离线验收样例", runtime::createPreviewSample, enabled = !state.busy, variant = ButtonVariant.Secondary)
        CbButton("同步 / 接续", runtime::requestSync, enabled = !state.busy && state.peers.isNotEmpty())
        CbButton("扫描电脑二维码", { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("扫描自己电脑 CCB 中的配对二维码").setBeepEnabled(false).setOrientationLocked(false)) }, enabled = !state.busy)
        CbInput(code, { code = it; confirm = null }, placeholder = "扫码失败时，粘贴电脑的备用配对码", singleLine = false, minLines = 2)
        CbButton("检查备用配对码", { inspect(code) }, enabled = !state.busy && code.isNotBlank(), variant = ButtonVariant.Secondary)
        error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
        if (state.conflicts.isNotEmpty()) {
            CbText("可在手机或电脑核对并选择；先收到的完整选择生效。")
            state.conflicts.forEach { conflict ->
                CbText(conflict.title)
                state.reviewDetails.firstOrNull { it.header.unit == conflict.unit }?.let { LanSyncDetail(it, runtime = runtime) }
                listOf(LanSyncChoice.KEEP_COMPUTER to "保留电脑", LanSyncChoice.KEEP_PHONE to "保留手机", LanSyncChoice.KEEP_BOTH to "保留两份")
                    .filter { it.first != LanSyncChoice.KEEP_BOTH || conflict.canKeepBoth }.forEach { (choice, label) ->
                        CbButton((if (selected[conflict.unit] == choice) "✓ " else "") + label,
                            { selected[conflict.unit] = choice }, variant = ButtonVariant.Secondary)
                    }
            }
            CbButton("按以上选择继续", { runtime.resolveConflicts(selected.toMap(), state.reviewBinding) },
                enabled = state.conflicts.all { it.unit in selected })
        }
        if (state.pendingReviews.isNotEmpty()) {
            CbText("以下删除或精简尚未执行；各端现状保留，其余独立内容已同步。")
            state.pendingReviews.forEach { held ->
                val impact = remember(held.binding) { LanSyncImpactReview.reductionSummary(held.computer, held.phone, held.proposed) }
                CbText(held.unit + if (held.dependencyOf.isEmpty()) " · 待确认" else " · 随关联项暂缓")
                if (held.dependencyIssue) CbText("有关联会话继续依赖此内容，当前目标不能单独执行；请先核对或调整关联内容，再重新比较。", color = ChatBarTheme.colors.warning)
                CbText("两端合计将移除 ${impact.recordsRemoved} 条记录（其中 ${impact.liveMessagesRemoved} 条实时消息）、${impact.versionsRemoved} 个消息版本、${impact.attachmentReferencesRemoved} 条附件引用；覆盖 ${impact.recordsOverwritten} 条记录。")
                val detail = remember(held.binding) { LanSyncDetailedReview.detail(held.unit, held.computer, held.phone, held.ancestorHashes[held.unit]) }
                val header = detail.header
                LanSyncDetail(detail, runtime = runtime)
                CbText("确认后的目标共 ${held.proposed.size} 条记录；目标为空表示移除此组。")
                if (held.proposed.isNotEmpty()) LanSyncDetail(LanSyncDetailedUnit(
                    header.copy(unit = header.unit + "/target"), held.proposed, emptyList()), singleSide = true, runtime = runtime)
                if (held.dependencyOf.isEmpty()) CbButton("确认按此目标同步删除或精简",
                    { runtime.confirmReview(held.unit, held.binding) }, enabled = !state.busy && !held.dependencyIssue, variant = ButtonVariant.Secondary)
            }
        }
        CbButton("查看本机保全与恢复记录", runtime::refreshRecovery, enabled = !state.busy, variant = ButtonVariant.Secondary)
        var recoveryPage by remember(state.recoveryEntries) { mutableStateOf(0) }
        val recoveryPages = maxOf(1, (state.recoveryEntries.size + 9) / 10)
        state.recoveryEntries.drop(recoveryPage * 10).take(10).forEach { entry ->
            CbText("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.createdAt))} · ${entry.affectedUnits} 组受影响内容 · ${entry.recordCount} 条记录 / ${entry.messageCount} 条实时消息")
            CbText(if (entry.hasLocalOriginals) "本机原始内容与本机选择已保全" else "对端同步内容已保全；不含对端账户和本机模型选择")
            CbButton("浏览此保全记录", { runtime.openRecovery(entry.transaction) }, enabled = !state.busy, variant = ButtonVariant.Secondary)
        }
        if (recoveryPages > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CbButton("上一页记录", { recoveryPage-- }, enabled = !state.busy && recoveryPage > 0, variant = ButtonVariant.Secondary)
            CbText("${recoveryPage + 1}/$recoveryPages")
            CbButton("下一页记录", { recoveryPage++ }, enabled = !state.busy && recoveryPage + 1 < recoveryPages, variant = ButtonVariant.Secondary)
        }
        state.recoveryBrowse?.let { browse ->
            CbText("恢复只作用于所选内容组和必需的缺失依赖；其他本机内容保留。")
            browse.units.sorted().forEach { unit ->
                CbButton("预览恢复 · $unit", { runtime.previewRecovery(browse.entry.transaction, unit) }, enabled = !state.busy, variant = ButtonVariant.Secondary)
            }
        }
        state.recoveryPreview?.let { preview ->
            val header = LanSyncReviewHeader("recovery/${preview.id}", null, null, null,
                preview.currentRecords.map { it.key }.toSet(), preview.restoredRecords.map { it.key }.toSet())
            val impact = remember(preview.id) { LanSyncImpactReview.reductionSummary(preview.currentRecords, emptyList(), preview.restoredRecords) }
            CbText("本机将移除 ${impact.recordsRemoved} 条记录、${impact.versionsRemoved} 个消息版本和 ${impact.attachmentReferencesRemoved} 条附件引用，覆盖 ${impact.recordsOverwritten} 条记录；写入前会再次保全当前内容。")
            LanSyncDetail(LanSyncDetailedUnit(header, preview.currentRecords, emptyList()), singleSide = true, singleLabel = "当前本机", runtime = runtime)
            LanSyncDetail(LanSyncDetailedUnit(header.copy(unit = header.unit + "/target"), preview.restoredRecords, emptyList()), singleSide = true, singleLabel = "恢复目标", runtime = runtime)
            if (preview.restoredUnits.size > 1) CbText("同时恢复必需依赖：${preview.restoredUnits.joinToString("、")}")
            CbButton("恢复此组为本机新修订", { runtime.applyRecovery(preview.id) }, enabled = !state.busy && preview.hasChanges)
            CbButton("取消恢复预览", runtime::cancelRecoveryPreview, enabled = !state.busy, variant = ButtonVariant.Secondary)
        }
        confirm?.let { offer ->
            CbText("请确认这是你的电脑：${offer.host}，设备身份 ${offer.fingerprint.take(16)}。确认后会记住此设备，后续无需重新扫码。")
            CbButton("确认可信设备并配对", { runtime.pair(code); confirm = null; code = "" }, enabled = !state.busy)
        }
        state.peers.forEach { peer ->
            CbText("已记住：${peer.name} · ${peer.fingerprint.take(16)}")
            CbButton("撤销配对", { runtime.revoke(peer.fingerprint) }, enabled = !state.busy, variant = ButtonVariant.Secondary)
        }
        lanSyncExcludedSummary(state.excluded)?.let { CbText(it) }
        CbText("保持此页面与电脑同步页面打开；冲突可在任意一端逐项核对并选择。系统询问相机或局域网权限时，只为此功能授权。不要关闭防火墙。")
        CbButton("返回", close, enabled = !state.busy, variant = ButtonVariant.Secondary)
    }
    state.imagePreview?.let { LanSyncFrozenImageDialog(it, runtime::closeImagePreview) }
}

@Composable
private fun LanSyncDetail(detail: LanSyncDetailedUnit, singleSide: Boolean = false, singleLabel: String = "目标", runtime: LanSyncRuntime) {
    var expanded by remember(detail.header) { mutableStateOf(false) }
    CbButton(if (expanded) "收起内容" else if (singleSide) "查看${singleLabel}内容" else "查看双方正文、版本与存储字段", { expanded = !expanded }, variant = ButtonVariant.Secondary)
    if (!expanded) return
    CbText("${if (singleSide) singleLabel else "电脑"}：${detail.computer.size} 条记录 / ${detail.computerMessages} 条实时消息 / ${detail.computerArchivedMessages} 条存档消息")
    if (!singleSide) {
        CbText("手机：${detail.phone.size} 条记录 / ${detail.phoneMessages} 条实时消息 / ${detail.phoneArchivedMessages} 条存档消息")
        CbText("比较起点仅有指纹，无法显示其历史正文。")
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
        CbButton("查看$label 图片 ${imagePage * 10 + index + 1} · ${image.field}", { runtime.previewImage(row, image.reference) }, variant = ButtonVariant.Secondary)
    }
    if (images.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页图片", { imagePage-- }, enabled = imagePage > 0, variant = ButtonVariant.Secondary)
        CbText("${imagePage + 1}/${(images.size + 9) / 10}")
        CbButton("下一页图片", { imagePage++ }, enabled = (imagePage + 1) * 10 < images.size, variant = ButtonVariant.Secondary)
    }
    val fields = detail.fields(key)
    val field = fields.getOrNull(fieldIndex) ?: return
    CbText("记录 ${recordIndex + 1}/${keys.size} · $key")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一记录", { recordIndex-- }, enabled = recordIndex > 0, variant = ButtonVariant.Secondary)
        CbButton("下一记录", { recordIndex++ }, enabled = recordIndex + 1 < keys.size, variant = ButtonVariant.Secondary)
    }
    CbText("字段 ${fieldIndex + 1}/${fields.size} · $field")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一字段", { fieldIndex-- }, enabled = fieldIndex > 0, variant = ButtonVariant.Secondary)
        CbButton("下一字段", { fieldIndex++ }, enabled = fieldIndex + 1 < fields.size, variant = ButtonVariant.Secondary)
    }
    val left = detail.fieldPage(key, field, true, page); val right = detail.fieldPage(key, field, false, page)
    val pages = maxOf(left.second, right.second)
    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CbText(if (singleSide) singleLabel else "电脑"); CbText(left.first)
        if (!singleSide) { CbText("手机"); CbText(right.first) }
    } }
    if (pages > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页正文", { page-- }, enabled = page > 0, variant = ButtonVariant.Secondary)
        CbText("${page + 1}/$pages")
        CbButton("下一页正文", { page++ }, enabled = page + 1 < pages, variant = ButtonVariant.Secondary)
    }
}
