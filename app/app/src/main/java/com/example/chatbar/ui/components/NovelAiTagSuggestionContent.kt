package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.chatbar.domain.image.NovelAiTagCandidate
import com.example.chatbar.ui.kit.*

@Composable
internal fun NovelAiTagSuggestionContent(
    candidates: List<NovelAiTagCandidate>,
    loading: Boolean,
    error: String?,
    queryKey: Any?,
    modifier: Modifier = Modifier,
    onInsertTag: (String) -> Unit
) {
    val listState = remember(queryKey) { LazyListState() }
    var browsing by remember(queryKey) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (scrolling, index, offset) ->
            if (scrolling) browsing = index != 0 || offset != 0
        }
    }
    SideEffect {
        // Dictionary may arrive first. Until the user scrolls, show rank zero
        // instead of retaining that dictionary item's key as tags arrive before it.
        if (!browsing && !listState.isScrollInProgress) listState.requestScrollToItem(0)
    }
    when {
        loading && candidates.isEmpty() -> CbText(
            "预测中…",
            modifier,
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
        error != null && candidates.isEmpty() -> CbText(
            error,
            modifier,
            color = ChatBarTheme.colors.destructive,
            style = ChatBarTheme.typography.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        candidates.isEmpty() -> CbText(
            "输入 Tag 获取预测",
            modifier,
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
        else -> LazyRow(
            state = listState,
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
        ) {
            error?.let { error ->
                item(key = "catalog-error") {
                    CbText(error, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
                }
            }
            items(
                candidates,
                key = { candidate -> candidate.name }
            ) { candidate ->
                CbButton(
                    text = buildString {
                        append(candidate.name)
                        if (candidate.translatedName.isNotBlank()) {
                            append(" · ${candidate.translatedName}")
                        }
                        append(if (candidate.fromDictionary) " · 内置词典" else " · ${candidate.count} 张")
                    },
                    onClick = { onInsertTag(candidate.name) },
                    size = ButtonSize.Xs,
                    variant = ButtonVariant.Outline
                )
            }
            if (loading) {
                item(key = "catalog-loading") {
                    CbText("预测中…", color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption)
                }
            }
        }
    }
}
