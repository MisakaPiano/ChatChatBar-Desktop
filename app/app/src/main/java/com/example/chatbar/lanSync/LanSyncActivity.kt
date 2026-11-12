package com.example.chatbar.lanSync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
        confirm?.let { offer ->
            CbText("请确认这是你的电脑：${offer.host}，设备身份 ${offer.fingerprint.take(16)}。确认后会记住此设备，后续无需重新扫码。")
            CbButton("确认可信设备并配对", { runtime.pair(code); confirm = null; code = "" }, enabled = !state.busy)
        }
        state.peers.forEach { peer ->
            CbText("已记住：${peer.name} · ${peer.fingerprint.take(16)}")
            CbButton("撤销配对", { runtime.revoke(peer.fingerprint) }, enabled = !state.busy, variant = ButtonVariant.Secondary)
        }
        if (state.excluded.isNotEmpty()) CbText("本次未参与：" + state.excluded.sorted().joinToString("、"))
        CbText("保持此页面与电脑同步页面打开；首次冲突请在电脑逐项选择。系统询问相机或局域网权限时，只为此功能授权。不要关闭防火墙。")
        CbButton("返回", close, enabled = !state.busy, variant = ButtonVariant.Secondary)
    }
}
