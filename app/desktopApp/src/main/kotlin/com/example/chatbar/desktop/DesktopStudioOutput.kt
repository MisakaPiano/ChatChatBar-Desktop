package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.imageprompt.NovelAiAccountUiState
import kotlinx.coroutines.launch

/** Formal input validity plus Desktop readiness/credential presence; account usage is cost data only. */
internal fun desktopCanGenerate(state: DesktopNovelAiStudioState, draft: NovelAiStudioDraft, busy: Boolean): Boolean =
    state.ready && state.credentialConfigured && !busy && !state.applyingHistory && draft.basePrompt.isNotBlank() &&
        draft.activeSettings.validationError(draft.activeCharacters.size) == null && draft.imageGuidance.validationError(draft.selectedModel) == null

internal fun desktopGenerateLabel(busy: Boolean, credentialConfigured: Boolean, cost: NovelAiGenerationCost?, progress: String = ""): String = when {
    busy -> "停止当前任务" + progress.takeIf { it.matches(Regex("\\d+/\\d+.*")) }?.let { " · $it" }.orEmpty()
    !credentialConfigured -> "未配置 Token"
    cost == null -> "生成配置不可用"
    cost.anlas > 0 -> "生成消耗 ${cost.anlas} Anlas" +
        (if (cost.encodingAnlas > 0) "（含编码 ${cost.encodingAnlas}）" else "") +
        (if (cost.extraVibeAnlas > 0) "（含额外 Vibe ${cost.extraVibeAnlas}）" else "")
    cost.kind == NovelAiGenerationChargeKind.FREE || cost.kind == NovelAiGenerationChargeKind.V5_ALLOWANCE -> "生成免费"
    else -> "生成消耗 ${cost.anlas} Anlas"
}

internal data class DesktopTokenProgress(val count: Int, val limit: Int) {
    val fraction get() = if (limit > 0) (count.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val exceeded get() = count > limit
    val warning get() = exceeded || (limit > 0 && count.toFloat() / limit >= .9f)
}

@Composable
internal fun StudioTokenBar(label: String, count: Int, limit: Int, modifier: Modifier = Modifier) {
    val progress = DesktopTokenProgress(count, limit)
    val color = if (progress.exceeded) DesktopBootstrapColors.destructive else if (progress.warning) DesktopBootstrapColors.warning else DesktopBootstrapColors.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        StatusText("$label $count/$limit" + if (progress.exceeded) " · 超出上限" else if (progress.warning) " · 接近上限" else "", color)
        Box(Modifier.fillMaxWidth().height(5.dp).background(DesktopBootstrapColors.muted)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.fraction, 0f..1f) }) {
            Box(Modifier.fillMaxWidth(progress.fraction).fillMaxHeight().background(color))
        }
    }
}

@Composable
internal fun StudioAccountCluster(model: NovelAiImageModel, account: NovelAiAccountUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        StudioActions {
            StatusText(model.displayName)
            StatusText(account.displayAnlas?.let { "Anlas $it" } ?: "Anlas —")
            account.approximateV5Images?.let { StatusText("V5 约 $it 张") }
            if (account.loading) StatusText("正在刷新账户…")
        }
        account.error?.let { StatusText(it, DesktopBootstrapColors.warning) }
        if (model == NovelAiImageModel.V5_FULL) {
            val usage = account.effectiveUsage.takeIf { account.error == null }
            val percent = usage?.v5AllowancePercent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
            val exhausted = usage?.v5AllowanceExhausted == true
            StatusText(if (percent == null) "V5 Opus · 额度未知" else "V5 Opus ${percent.toInt()}% · 约 ${account.approximateV5Images ?: "—"} 张" + if (exhausted) " · 已用尽" else "")
            Box(Modifier.fillMaxWidth().height(8.dp).border(1.dp, DesktopBootstrapColors.border)
                .background(DesktopBootstrapColors.muted).semantics {
                    contentDescription = if (percent == null) "V5 额度未知" else "V5 额度 ${percent.toInt()}%"
                    if (percent != null) progressBarRangeInfo = ProgressBarRangeInfo((percent / 100).toFloat(), 0f..1f)
                }) {
                if (percent != null) Box(Modifier.fillMaxWidth(if (exhausted) 0f else (percent / 100).toFloat()).fillMaxHeight()
                    .background(if (exhausted) DesktopBootstrapColors.warning else DesktopBootstrapColors.primary))
            }
        }
    }
}

/** Presentation projection of the existing history, never a second persistence owner. */
internal fun desktopStudioFilmstrip(current: List<String>, history: List<NovelAiGenerationHistoryEntry>): List<String> =
    (current.asReversed() + history.sortedByDescending { it.createdAt }.flatMap { it.images.asReversed().map { image -> image.path } }).distinct()

/** Only arrival of a new generated path overrides an explicit older selection. */
internal fun desktopSelectNewResult(selected: String?, previous: List<String>, current: List<String>): String? =
    current.lastOrNull { it !in previous } ?: selected

internal fun desktopResultHeight(width: Float, availableHeight: Float): Float =
    minOf(width * 1.1f, availableHeight * .58f).coerceIn(240f, 880f)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun StudioFilmstrip(paths: List<String>, selected: String?, read: (String) -> ByteArray,
    scroll: LazyListState = rememberLazyListState(), onPreview: ((String) -> Unit)? = null, onSelect: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(selected, paths) {
        val index = paths.indexOf(selected)
        if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.scrollToItem(index)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Scroll) { event ->
            val delta = event.changes.sumOf { (it.scrollDelta.x + it.scrollDelta.y).toDouble() }.toFloat()
            if (delta != 0f) { scope.launch { scroll.scrollBy(delta * 48) }; event.changes.forEach { it.consume() } }
        }, state = scroll, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(paths, key = { it }) { path ->
                DesktopOwnedImage(path, read, Modifier.size(88.dp).border(if (path == selected) 3.dp else 1.dp,
                    if (path == selected) DesktopBootstrapColors.primary else DesktopBootstrapColors.border)
                    .semantics { this.selected = path == selected; contentDescription = "选择结果缩略图 ${paths.indexOf(path) + 1}" }
                    .then(if (onPreview == null) Modifier.clickable { onSelect(path) }
                        else Modifier.desktopViewerEntry(onClick = { onSelect(path) }, onOpen = { onPreview(path) })))
            }
        }
        HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.fillMaxWidth().height(8.dp)
            .semantics { contentDescription = "结果缩略图滚动条" }, style = ScrollbarStyle(
                minimalHeight = 24.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 0, unhoverColor = DesktopBootstrapColors.border,
                hoverColor = DesktopBootstrapColors.primary))
    }
}

/** Rail is the same path projection and callback as the horizontal strip, with its own viewport. */
@Composable
internal fun StudioVerticalFilmstrip(paths: List<String>, selected: String?, read: (String) -> ByteArray,
    scroll: LazyListState = rememberLazyListState(), onPreview: ((String) -> Unit)? = null, onSelect: (String) -> Unit) {
    LaunchedEffect(selected, paths) {
        val index = paths.indexOf(selected)
        if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.requestScrollToItem(index)
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp).semantics { contentDescription = "竖向结果缩略图" },
            state = scroll, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(paths, key = { it }) { path ->
                DesktopOwnedImage(path, read, Modifier.size(88.dp).border(if (path == selected) 3.dp else 1.dp,
                    if (path == selected) DesktopBootstrapColors.primary else DesktopBootstrapColors.border)
                    .semantics { this.selected = path == selected; contentDescription = "选择结果缩略图 ${paths.indexOf(path) + 1}" }
                    .then(if (onPreview == null) Modifier.clickable { onSelect(path) }
                        else Modifier.desktopViewerEntry(onClick = { onSelect(path) }, onOpen = { onPreview(path) })))
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp)
            .semantics { contentDescription = "竖向结果缩略图滚动条" }, style = ScrollbarStyle(
                minimalHeight = 24.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp), hoverDurationMillis = 0,
                unhoverColor = DesktopBootstrapColors.border, hoverColor = DesktopBootstrapColors.primary))
    }
}
