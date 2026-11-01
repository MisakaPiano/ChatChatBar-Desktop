@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun DesktopProviderUsagePanel(owner: DesktopProviderUsageOwner) {
    val state by owner.state.collectAsState()
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<DesktopUsageSource?>(null) }
    var days by remember { mutableStateOf<Int?>(null) }
    val query = remember(source, days) { DesktopUsageQuery(
        fromInclusive = days?.let { System.currentTimeMillis() - it * 86_400_000L }, source = source) }
    LaunchedEffect(owner, query) { owner.refresh(query) }
    DesktopManagementHeading("模型请求用量")
    StatusText("记录已接入的聊天、卡片、世界书、朋友圈文本、图片设计及语音翻译／标签的模型请求；实际生图、音频合成和检索统计仍待接入，部分辅助操作归入其他。服务端未报告的用量保持未知，字符估算不计入此处。")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectChip("全部时间", days == null) { days = null }
        listOf(1, 7, 30).forEach { value -> SelectChip("最近${value}天", days == value) { days = value } }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectChip("全部来源", source == null) { source = null }
        DesktopUsageSource.entries.forEach { value ->
            val label = when (value) {
                DesktopUsageSource.CHAT -> "对话"
                DesktopUsageSource.CHARACTER_CARD -> "角色卡"
                DesktopUsageSource.FORMAT_CARD -> "格式卡"
                DesktopUsageSource.WORLD_BOOK -> "世界书"
                DesktopUsageSource.MOMENTS -> "朋友圈"
                DesktopUsageSource.OTHER -> "其他"
            }
            SelectChip(label, source == value) { source = value }
        }
        BootstrapButton(if (state.loading) "读取中…" else "刷新统计", secondary = true, enabled = !state.loading) {
            scope.launch { owner.refresh(query) }
        }
    }
    state.totals?.let { totals ->
        StatusText("${totals.requests} 次请求 · ${totals.attempts} 次网络尝试（包含重试）")
        UsageCounters(totals)
    }
    state.groups.take(30).forEach { group ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusText(group.modelName + (group.provider?.let { " · $it" } ?: " · 供应商未标注") +
                " · ${group.totals.attempts}次尝试")
            UsageCounters(group.totals)
        }
    }
    if (state.pending > 0) StatusText("${state.pending} 条用量记录待保存")
    state.error?.let { error ->
        StatusText(error, DesktopBootstrapColors.destructive)
        BootstrapButton("重试保存统计", secondary = true, onClick = owner::retrySave)
    }
    StatusText("统计保存在当前电脑的资料目录中，不包含对话正文或凭据；现阶段不随整应用备份迁移，也不代表供应商账单。")
}

@Composable
private fun UsageCounters(totals: DesktopUsageTotals) {
    fun text(label: String, counter: DesktopUsageCounterTotal): String = "$label：" +
        (counter.value?.toString() ?: "未报告") +
        if (counter.unreportedAttempts > 0) "（${counter.unreportedAttempts}次未报告）" else ""
    StatusText(text("输入Token", totals.prompt) + " · " + text("输出Token", totals.completion))
    StatusText(text("缓存命中", totals.cacheHit) + " · " + text("缓存写入", totals.cacheWrite) + " · " + text("缓存未命中", totals.cacheMiss))
}
