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
    var textOnly by remember { mutableStateOf(false) }
    var mediaKind by remember { mutableStateOf<DesktopUsageMediaKind?>(null) }
    val query = remember(source, days, textOnly, mediaKind) { DesktopUsageQuery(
        fromInclusive = days?.let { System.currentTimeMillis() - it * 86_400_000L }, source = source, textOnly = textOnly, mediaKind = mediaKind) }
    LaunchedEffect(owner, query) { owner.refresh(query) }
    DesktopManagementHeading("模型请求用量")
    StatusText("记录已接入的文本／向量请求和正式 Fish TTS 网络尝试。TTS 发送字符、有效音频时长和字节为独立本地测量，不混入 Token，也不代表供应商计费。实际生图、独立试听等其他媒体调用仍待接入；未报告的服务端用量保持未知。")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectChip("全部类型", !textOnly && mediaKind == null) { textOnly = false; mediaKind = null }
        SelectChip("Token 请求", textOnly) { textOnly = true; mediaKind = null }
        SelectChip("Fish TTS", mediaKind == DesktopUsageMediaKind.FISH_TTS) { textOnly = false; mediaKind = DesktopUsageMediaKind.FISH_TTS }
    }
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
    if (totals.prompt.reportedAttempts + totals.prompt.unreportedAttempts > 0) {
        StatusText(text("输入Token", totals.prompt) + " · " + text("输出Token", totals.completion))
        StatusText(text("缓存命中", totals.cacheHit) + " · " + text("缓存写入", totals.cacheWrite) + " · " + text("缓存未命中", totals.cacheMiss))
    }
    totals.media.forEach { media ->
        fun local(label: String, value: DesktopUsageCounterTotal) = "$label：${value.value ?: "未取得"}" +
            if (value.unreportedAttempts > 0) "（${value.unreportedAttempts}次未取得）" else ""
        StatusText("Fish TTS · ${media.attempts} 次网络尝试；以下为本地测量")
        StatusText(local("发送字符（Unicode 码点，含重试）", media.inputTextCodePoints))
        StatusText(local("有效音频时长（毫秒）", media.outputAudioDurationMs) + " · " + local("有效音频字节", media.outputAudioBytes))
        StatusText("供应商计费量：未取得（UNREPORTED）；本地测量不能等同账单。")
    }
}
