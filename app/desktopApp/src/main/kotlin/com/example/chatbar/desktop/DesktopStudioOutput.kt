package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
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
        draft.activeSettings.sizeValidationError() == null && draft.imageGuidance.validationError(draft.selectedModel) == null

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
    }
}

/** Presentation projection of the existing history, never a second persistence owner. */
internal fun desktopStudioFilmstrip(current: List<String>, history: List<NovelAiGenerationHistoryEntry>): List<String> =
    (current + history.flatMap { it.images.map { image -> image.path } }).distinct()

internal fun desktopResultHeight(width: Float, availableHeight: Float): Float =
    minOf(width * 1.1f, availableHeight * .58f).coerceIn(240f, 880f)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun StudioFilmstrip(paths: List<String>, selected: String?, read: (String) -> ByteArray, onSelect: (String) -> Unit) {
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyRow(Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Scroll) { event ->
        val delta = event.changes.sumOf { (it.scrollDelta.x + it.scrollDelta.y).toDouble() }.toFloat()
        if (delta != 0f) { scope.launch { scroll.scrollBy(delta * 48) }; event.changes.forEach { it.consume() } }
    }, state = scroll, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(paths, key = { it }) { path ->
            DesktopOwnedImage(path, read, Modifier.size(88.dp).border(if (path == selected) 3.dp else 1.dp,
                if (path == selected) DesktopBootstrapColors.primary else DesktopBootstrapColors.border)
                .semantics { this.selected = path == selected; contentDescription = "选择结果缩略图 ${paths.indexOf(path) + 1}" }
                .clickable { onSelect(path) })
        }
    }
}
