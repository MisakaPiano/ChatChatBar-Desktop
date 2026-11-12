package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.lanSync.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.awt.image.BufferedImage

@Composable
internal fun DesktopLanSyncPanel(runtime: LanSyncRuntime) {
    val state by runtime.state.collectAsState()
    DisposableEffect(runtime) { runtime.startHost(); onDispose { runtime.stop() } }
    val selected = remember { mutableStateMapOf<String, LanSyncChoice>() }
    LaunchedEffect(state.conflicts) { selected.clear() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DesktopDataSectionHeading("局域网同步")
        StatusText(LanSyncScope.description)
        StatusText(state.status)
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
                ActionRow {
                    listOf(LanSyncChoice.KEEP_COMPUTER to "保留电脑", LanSyncChoice.KEEP_PHONE to "保留手机", LanSyncChoice.KEEP_BOTH to "保留两份").forEach { (choice, label) ->
                        BootstrapButton((if (selected[conflict.unit] == choice) "✓ " else "") + label, secondary = selected[conflict.unit] != choice) { selected[conflict.unit] = choice }
                    }
                }
            }
            BootstrapButton("按以上选择继续", enabled = state.conflicts.all { it.unit in selected }) { runtime.resolveConflicts(selected.toMap()) }
        }
        state.peers.forEach { peer ->
            StatusText("已记住：${peer.name} · ${peer.fingerprint.take(16)}")
            BootstrapButton("撤销配对", secondary = true, enabled = !state.busy) { runtime.revoke(peer.fingerprint) }
        }
        if (state.excluded.isNotEmpty()) StatusText("本次未参与：" + state.excluded.sorted().joinToString("、"))
        StatusText("保持两端同步页面打开。首次连接如 Windows 提示网络访问，仅允许可信的专用网络；不要关闭防火墙。连接失败可重新生成二维码或使用备用配对码。")
    }
}
