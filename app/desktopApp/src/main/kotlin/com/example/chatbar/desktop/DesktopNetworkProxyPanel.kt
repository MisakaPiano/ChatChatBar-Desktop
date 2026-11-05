package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.ApplicationProxyMode
import kotlinx.coroutines.launch

@Composable
internal fun DesktopNetworkProxyPanel(controller: DesktopNetworkProxyController) {
    val state by controller.state.collectAsState()
    var mode by remember(state.saved) { mutableStateOf(state.saved.mode) }
    var host by remember(state.saved) { mutableStateOf(state.saved.host) }
    var port by remember(state.saved) { mutableStateOf(state.saved.port.toString()) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ManageHeading("应用网络代理")
        ActionRow { ApplicationProxyMode.entries.forEach { value ->
            SelectChip(when (value) {
                ApplicationProxyMode.SYSTEM -> "跟随系统"
                ApplicationProxyMode.HTTP -> "HTTP 代理"
                ApplicationProxyMode.SOCKS -> "SOCKS 代理"
                ApplicationProxyMode.DIRECT -> "直连"
            }, mode == value) { mode = value }
        } }
        if (mode == ApplicationProxyMode.HTTP || mode == ApplicationProxyMode.SOCKS) {
            LabeledField("代理主机", host, onChange = { host = it })
            LabeledField("端口", port, onChange = { port = it })
        }
        StatusText("适用于模型、NovelAI 与 Fish 等应用请求。本机 localhost／127.*／::1 始终直连。设置随资料目录保存，正常更新会保留；保存后请正常退出并重开。")
        ActionRow {
            BootstrapButton("保存网络设置") { scope.launch { controller.save(mode, host, port) } }
            BootstrapButton(if (state.checkingRoute) "正在查看路由…" else "查看当前运行路由",
                enabled = !state.checkingRoute, secondary = true) { scope.launch { controller.inspectRoute() } }
        }
        if (state.restartRequired) StatusText("已保存，下次启动生效。当前播放和请求不受影响。", DesktopBootstrapColors.warning)
        state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        state.route?.let { StatusText(it) }
    }
}
