package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopCatalogUpdatePanel(controller: DesktopCatalogUpdates) {
    val state by controller.state.collectAsState()
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DesktopDataSectionHeading("Danbooru 词库")
        StatusText("检查不下载；确认更新后下载、校验并启用。词库与程序分别检查，结果互不覆盖。")
        StatusText(state.status)
        state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        ActionRow {
            BootstrapButton(if (state.error == null) "检查词库更新" else "重试词库检查", secondary = true, enabled = !state.busy) { controller.check() }
            if (state.update != null) BootstrapButton("下载并更新词库", enabled = !state.busy) { controller.install() }
            if (state.busy) BootstrapButton("停止词库操作", secondary = true) { controller.stop() }
        }
        if (state.error != null && state.details != null) {
            BootstrapButton(if (details) "收起词库错误详情" else "查看词库错误详情", secondary = true) { details = !details }
            if (details) StatusText(requireNotNull(state.details))
        }
    }
}
