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
import com.example.chatbar.ChatBarApp
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.ui.kit.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class LanSyncActivity : ComponentActivity() {
    private val runtime get() = ChatBarApp.instance.lanSyncOwner.runtime
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChatBarTheme { LanSyncScreen(runtime) { finish() } } }
    }
    override fun onStart() { super.onStart(); runtime.startPhone() }
    override fun onStop() { runtime.stop(); super.onStop() }
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
                state.reviewDetails.firstOrNull { it.header.unit == conflict.unit }?.let { LanSyncDetail(it) }
                listOf(LanSyncChoice.KEEP_COMPUTER to "保留电脑", LanSyncChoice.KEEP_PHONE to "保留手机", LanSyncChoice.KEEP_BOTH to "保留两份")
                    .filter { it.first != LanSyncChoice.KEEP_BOTH || conflict.canKeepBoth }.forEach { (choice, label) ->
                        CbButton((if (selected[conflict.unit] == choice) "✓ " else "") + label,
                            { selected[conflict.unit] = choice }, variant = ButtonVariant.Secondary)
                    }
            }
            CbButton("按以上选择继续", { runtime.resolveConflicts(selected.toMap(), state.reviewBinding) },
                enabled = state.conflicts.all { it.unit in selected })
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
}

@Composable
private fun LanSyncDetail(detail: LanSyncDetailedUnit) {
    var expanded by remember(detail.header) { mutableStateOf(false) }
    CbButton(if (expanded) "收起双方内容" else "查看双方正文、版本与存储字段", { expanded = !expanded }, variant = ButtonVariant.Secondary)
    if (!expanded) return
    CbText("电脑：${detail.computer.size} 条记录 / ${detail.computerMessages} 条实时消息 / ${detail.computerArchivedMessages} 条存档消息")
    CbText("手机：${detail.phone.size} 条记录 / ${detail.phoneMessages} 条实时消息 / ${detail.phoneArchivedMessages} 条存档消息")
    CbText("比较起点仅有指纹，无法显示其历史正文。")
    var recordIndex by remember(detail.header) { mutableStateOf(0) }
    var fieldIndex by remember(detail.header, recordIndex) { mutableStateOf(0) }
    var page by remember(detail.header, recordIndex, fieldIndex) { mutableStateOf(0) }
    val keys = detail.recordKeys
    val key = keys.getOrNull(recordIndex) ?: return
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
        CbText("电脑"); CbText(left.first); CbText("手机"); CbText(right.first)
    } }
    if (pages > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbButton("上一页正文", { page-- }, enabled = page > 0, variant = ButtonVariant.Secondary)
        CbText("${page + 1}/$pages")
        CbButton("下一页正文", { page++ }, enabled = page + 1 < pages, variant = ButtonVariant.Secondary)
    }
}
