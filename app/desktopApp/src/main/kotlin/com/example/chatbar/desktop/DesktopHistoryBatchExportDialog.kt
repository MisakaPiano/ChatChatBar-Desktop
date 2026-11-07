package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.NovelAiHistoryImageSelection
import kotlinx.coroutines.launch

@Composable
internal fun DesktopHistoryBatchExportDialog(controller: DesktopHistoryBatchExportController,
    selected: List<NovelAiHistoryImageSelection>, onClose: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var choices by remember(state.plan) { mutableStateOf(emptyMap<NovelAiHistoryImageSelection, DesktopHistoryBatchExport.Decision>()) }
    var applyRemaining by remember(state.plan) { mutableStateOf(false) }
    val plan = state.plan
    fun choose(item: DesktopHistoryBatchExport.Item, decision: DesktopHistoryBatchExport.Decision) {
        if (state.busy || plan == null) return
        val remaining = if (applyRemaining) plan.conflicts.dropWhile { it.key != item.key }.map { it.key } else listOf(item.key)
        choices = choices + remaining.associateWith { decision }
    }
    DesktopModalSurface { Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 780.dp).fillMaxWidth().fillMaxHeight(.9f)
            .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ManageHeading("批量保存图片 · ${selected.size} 张") }
                DesktopIconAction("关闭批量保存", DesktopAppIcons.Close, enabled = !state.busy) {
                    if (controller.close()) onClose()
                }
            }
            StatusText("按选择顺序命名，保存原始图片字节；不会修改生成历史或 Studio 草稿。")
            if (state.busy) StatusText("文件标题：${title.ifBlank { "按生成时间自动命名" }}")
            else StudioField("文件标题（可选，自动添加序号及扩展名）", title) {
                title = it
                controller.close() // Changing the name invalidates the old directory/name proof.
            }
            BootstrapButton(if (plan == null) "选择目录并预览" else "重新选择目录并预览", enabled = !state.busy) {
                scope.launch { controller.prepare(selected, title) }
            }
            if (plan != null) SelectionContainer { StatusText("保存目录：${plan.directory}") }
            if (plan != null && plan.conflicts.isNotEmpty()) {
                StudioToggle("将此选择应用到后续同名文件", applyRemaining) { if (!state.busy) applyRemaining = !applyRemaining }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan == null) item { StatusText("先预览名称和目录，再确认保存。仅元数据的记录没有原图，请先取消选择；其参数与 Seed 仍可使用。") }
                else itemsIndexed(plan.items, key = { _, item -> item.key }) { index, item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SelectionContainer { StatusText("${index + 1}. ${item.name}") }
                        if (item.existing.isEmpty()) StatusText("新文件") else {
                            StatusText("已有同名文件${if (item.existing.size > 1) "（${item.existing.size} 份；仅替换最近一份，其余保留）" else ""}")
                            StudioActions {
                                BootstrapButton(if (choices[item.key] == DesktopHistoryBatchExport.Decision.OVERWRITE) "✓ 覆盖" else "覆盖", enabled = !state.busy) { choose(item, DesktopHistoryBatchExport.Decision.OVERWRITE) }
                                BootstrapButton(if (choices[item.key] == DesktopHistoryBatchExport.Decision.SKIP) "✓ 跳过" else "跳过", enabled = !state.busy) { choose(item, DesktopHistoryBatchExport.Decision.SKIP) }
                            }
                        }
                    }
                }
            }
            SelectionContainer { StatusText(state.status, if (state.error) DesktopBootstrapColors.warning else DesktopBootstrapColors.mutedForeground) }
            StudioActions {
                if (state.busy) BootstrapButton("停止保存") { controller.stop() }
                else BootstrapButton("确认保存", enabled = plan != null && choices.keys == plan.conflicts.map { it.key }.toSet()) {
                    if (plan != null) scope.launch { controller.save(plan, choices.toMap()) }
                }
            }
        }
    } }
}
