@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DesktopProviderUsagePanel(owner: DesktopProviderUsageOwner) {
    val state by owner.state.collectAsState()
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<DesktopUsageSource?>(null) }
    var days by remember { mutableStateOf<Int?>(null) }
    var textOnly by remember { mutableStateOf(false) }
    var mediaKind by remember { mutableStateOf<DesktopUsageMediaKind?>(null) }
    var requestId by remember { mutableStateOf<String?>(null) }
    var dimension by remember { mutableStateOf(DesktopUsageDimension.MODEL_ACCOUNT) }
    var showDimensions by remember { mutableStateOf(false) }
    val query = remember(source, days, textOnly, mediaKind, requestId) { DesktopUsageQuery(
        fromInclusive = days?.let { System.currentTimeMillis() - it * 86_400_000L }, source = source,
        textOnly = textOnly, mediaKind = mediaKind, requestId = requestId) }
    var page by remember(query, dimension) { mutableStateOf(0) }
    LaunchedEffect(owner, query, dimension, page) { owner.refresh(query, dimension, page) }
    DesktopManagementHeading("模型请求用量")
    StatusText("记录已接入的文本／向量、正式语音与生成试听、Studio／聊天生图和角色候选生图请求。字符、音频时长和图片／音频字节为本地测量，不混入 Token，也不代表账单。图片处理、Vibe 编码和朋友圈生图仍待接入；未报告的服务端用量保持未知。")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectChip("全部类型", !textOnly && mediaKind == null) { textOnly = false; mediaKind = null }
        SelectChip("Token 请求", textOnly) { textOnly = true; mediaKind = null }
        SelectChip("Fish TTS", mediaKind == DesktopUsageMediaKind.FISH_TTS) { textOnly = false; mediaKind = DesktopUsageMediaKind.FISH_TTS }
        SelectChip("NovelAI 生图", mediaKind == DesktopUsageMediaKind.NAI_IMAGE_GENERATION) { textOnly = false; mediaKind = DesktopUsageMediaKind.NAI_IMAGE_GENERATION }
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
                DesktopUsageSource.VOICE_PREVIEW -> "音色试听"
                DesktopUsageSource.OTHER -> "其他"
            }
            SelectChip(label, source == value) { source = value }
        }
        BootstrapButton(if (state.loading) "读取中…" else "刷新统计", secondary = true, enabled = !state.loading) {
            scope.launch { owner.refresh(query, dimension, page) }
        }
    }
    state.totals?.let { totals ->
        StatusText("${totals.requests} 次请求 · ${totals.attempts} 次网络尝试（包含重试）")
        UsageCounters(totals)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BootstrapButton("分组：${dimension.label}", secondary = true) { showDimensions = !showDimensions }
        if (requestId != null) BootstrapButton("返回全部请求", secondary = true) {
            requestId = null; dimension = DesktopUsageDimension.REQUEST
        }
    }
    if (showDimensions) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DesktopUsageDimension.entries.forEach { value -> SelectChip(value.label, dimension == value) { dimension = value } }
    }
    if (!state.loading) {
        val breakdown = state.breakdown
        StatusText("${breakdown.groups} 个分组 · 第 ${breakdown.page + 1} / ${breakdown.pages} 页")
        if (dimension in setOf(DesktopUsageDimension.DAY, DesktopUsageDimension.WEEK, DesktopUsageDimension.MONTH))
            StatusText("按本机时区、网络尝试的开始时间分组；跨日期重试可能出现在多个时间段。")
        if (breakdown.pages > 1) ActionRow {
            BootstrapButton("上一页分组", secondary = true, enabled = breakdown.page > 0) { page = breakdown.page - 1 }
            BootstrapButton("下一页分组", secondary = true, enabled = breakdown.page + 1 < breakdown.pages) { page = breakdown.page + 1 }
        }
        breakdown.rows.forEach { group -> key(group.key) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusText(group.title)
                if (dimension == DesktopUsageDimension.MODEL_ACCOUNT) {
                    StatusText("模型 ID：${group.sample.modelId} · " + if (group.sample.media == null) "Token 请求" else "Fish TTS")
                    StatusText((group.sample.provider ?: "供应商未标注") + " · " +
                        (group.sample.accountId?.let { "账户 ID：$it" } ?: "账户未标注"))
                }
                StatusText("${group.totals.requests} 次请求 · ${group.totals.attempts} 次网络尝试")
                UsageCounters(group.totals)
                if (dimension == DesktopUsageDimension.REQUEST) BootstrapButton("查看各次尝试", secondary = true) {
                    requestId = group.sample.requestId; dimension = DesktopUsageDimension.ATTEMPT
                }
                if (dimension == DesktopUsageDimension.ATTEMPT) UsageRecordDetails(group.sample)
            }
        } }
        if (breakdown.groups == 0 && state.error == null) StatusText("此范围没有已保存的请求记录。")
    } else StatusText("正在读取分组…")
    if (state.pending > 0) StatusText("${state.pending} 条用量记录待保存")
    state.error?.let { error ->
        StatusText(error, DesktopBootstrapColors.destructive)
        BootstrapButton("重试保存统计", secondary = true, onClick = owner::retrySave)
    }
    StatusText("统计保存在当前电脑的资料目录中，不包含对话正文或凭据；现阶段不随整应用备份迁移，也不代表供应商账单。")
}

@Composable
private fun UsageRecordDetails(record: DesktopUsageRecord) {
    var expanded by remember(record.id) { mutableStateOf(false) }
    BootstrapButton(if (expanded) "收起记录详情" else "展开记录详情", secondary = true) { expanded = !expanded }
    if (expanded) SelectionContainer {
        val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusText("请求 ID：${record.requestId}")
            StatusText("尝试 ID：${record.id} · 第 ${record.attempt} 次")
            StatusText("模型：${record.modelName} · ID：${record.modelId}")
            StatusText("供应商：${record.provider ?: "未标注"} · 账户 ID：${record.accountId ?: "未标注"}")
            StatusText("来源：${record.source} · 会话 ID：${record.sessionId ?: "未关联"}")
            listOf("任务" to record.taskId, "角色卡" to record.cardId, "编辑草稿" to record.editorSessionId,
                "人物" to record.personId, "对象" to record.subjectId).forEach { (label, id) -> id?.let { StatusText("$label ID：$it") } }
            StatusText("开始：${formatter.format(Instant.ofEpochMilli(record.startedAt))}")
            StatusText("最后记录：${formatter.format(Instant.ofEpochMilli(record.updatedAt))} · 状态：${record.outcome}")
            if (record.outcome == DesktopUsageOutcome.RUNNING) StatusText("尚未记录终态；不据此判断现在仍在执行或供应商是否已计费。")
            record.media?.httpStatus?.let { StatusText("HTTP $it") }
        }
    }
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
        when (media.kind) {
            DesktopUsageMediaKind.FISH_TTS -> {
                StatusText("Fish TTS · ${media.attempts} 次网络尝试；以下为本地测量")
                StatusText(local("发送字符（Unicode 码点，含重试）", media.inputTextCodePoints))
                StatusText(local("有效音频时长（毫秒）", media.outputAudioDurationMs) + " · " + local("有效音频字节", media.outputAudioBytes))
            }
            DesktopUsageMediaKind.NAI_IMAGE_GENERATION -> {
                StatusText("NovelAI 生图 · ${media.attempts} 次网络尝试；以下为本地测量")
                StatusText(local("请求图片数（含重试）", media.requestedImages))
                StatusText(local("有效返回图片数", media.outputImages) + " · " + local("有效图片字节", media.outputImageBytes))
                StatusText("有效返回不代表已经保存或应用；保存结果请查看原任务。")
            }
        }
        StatusText("供应商计费量：未取得（UNREPORTED）；本地测量不能等同账单。")
    }
}
