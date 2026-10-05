package com.example.chatbar.ui.moments

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.moment.MomentAlbumDateFilter
import com.example.chatbar.domain.moment.MomentAlbumDateGranularity
import com.example.chatbar.domain.moment.MomentAlbumFilter
import com.example.chatbar.domain.moment.MomentAlbumPolicy
import com.example.chatbar.domain.moment.MomentAlbumState
import com.example.chatbar.ui.components.EmptyState
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun MomentAlbumScreen(
    state: MomentAlbumState,
    onFilterChange: ((MomentAlbumFilter) -> MomentAlbumFilter) -> Unit,
    onBack: () -> Unit,
    onLocatePost: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSearch by remember { mutableStateOf(state.filter.query.isNotEmpty()) }
    var showDate by remember { mutableStateOf(false) }
    val filter = state.filter
    val folders = filter.groupByCard && filter.characterCardId == null
    val gridState = rememberLazyGridState()
    fun back() {
        if (filter.characterCardId != null) onFilterChange { it.copy(characterCardId = null) }
        else onBack()
    }
    BackHandler { back() }
    LaunchedEffect(filter) { gridState.scrollToItem(0) }
    CbScaffold(
        modifier = modifier,
        topBar = {
            CbTopBar(
                title = "朋友圈相册",
                navigation = { CbIconButton(AppIcons.ArrowBack, "返回", { back() }) },
                actions = {
                    CbIconButton(
                        AppIcons.Layers, if (filter.groupByCard) "展开全部朋友圈" else "按角色卡折叠",
                        { onFilterChange { it.copy(groupByCard = !it.groupByCard, characterCardId = null) } },
                        tint = if (filter.groupByCard) ChatBarTheme.colors.primary else ChatBarTheme.colors.foreground
                    )
                    CbIconButton(AppIcons.Calendar, "按日期筛选", { showDate = true },
                        tint = if (filter.date != null) ChatBarTheme.colors.primary else ChatBarTheme.colors.foreground)
                    CbIconButton(AppIcons.Search, "搜索朋友圈", { showSearch = !showSearch },
                        tint = if (filter.query.isNotBlank()) ChatBarTheme.colors.primary else ChatBarTheme.colors.foreground)
                }
            )
        }
    ) { bottomInset ->
        Column(Modifier.fillMaxSize().imePadding()) {
            if (showSearch) {
                Row(Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    CbInput(filter.query, { query -> onFilterChange { it.copy(query = query) } },
                        Modifier.weight(1f).semantics { contentDescription = "朋友圈搜索关键词" },
                        placeholder = "搜索文案、角色名或图片 Prompt")
                    CbIconButton(AppIcons.Close, "清除搜索", { onFilterChange { it.copy(query = "") } })
                }
            }
            Column(Modifier.fillMaxWidth().padding(ChatBarSpacing.md), verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)) {
                CbText(
                    state.selectedCardName ?: if (folders) "按角色卡折叠 · ${state.groups.size} 组" else "全部朋友圈",
                    style = ChatBarTheme.typography.label
                )
                CbText("${state.posts.size} 条 · 点按方块定位原朋友圈", style = ChatBarTheme.typography.caption,
                    color = ChatBarTheme.colors.mutedForeground)
                if (filter.date != null || filter.query.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CbText(listOfNotNull(filter.date?.label(), filter.query.takeIf(String::isNotBlank)?.let { "关键词：$it" }).joinToString(" · "),
                            Modifier.weight(1f), style = ChatBarTheme.typography.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        CbIconButton(AppIcons.Close, "清除筛选", { onFilterChange { it.copy(query = "", date = null) } })
                    }
                }
            }
            if (state.posts.isEmpty()) {
                EmptyState(icon = AppIcons.Image, title = "没有符合条件的朋友圈", description = "可清除筛选或返回其他角色分组。")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp), state = gridState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = ChatBarSpacing.sm, end = ChatBarSpacing.sm, bottom = bottomInset + 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
                ) {
                    if (folders) {
                        items(state.groups, key = { "card:${it.characterCardId}" }) { group ->
                            AlbumTile(
                                post = group.posts.first(), title = group.name,
                                subtitle = "${group.posts.size} 条", folder = true,
                                description = "角色相册 ${group.name}，${group.posts.size} 条",
                                onClick = { onFilterChange { it.copy(characterCardId = group.characterCardId) } }
                            )
                        }
                    } else {
                        items(state.posts, key = { "post:${it.id}" }) { post ->
                            AlbumTile(
                                post = post, title = post.senderName,
                                subtitle = MomentAlbumPolicy.date(post.generatedAt).toString(), folder = false,
                                description = "定位朋友圈 ${post.senderName}：${post.text.take(60).ifBlank { "生成失败" }}",
                                onClick = { onLocatePost(post.id) }
                            )
                        }
                    }
                }
            }
        }
    }
    if (showDate) MomentAlbumDateDialog(
        dates = state.dates, current = filter.date,
        onDismiss = { showDate = false },
        onApply = { date -> onFilterChange { it.copy(date = date) }; showDate = false }
    )
}

@Composable
private fun AlbumTile(post: MomentPost, title: String, subtitle: String, folder: Boolean, description: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(ChatBarShape.md))
        .clickable(role = Role.Button, onClickLabel = if (folder) "打开角色相册" else "定位原朋友圈", onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(ChatBarTheme.colors.surfaceSubtle)) {
            if (!post.imagePath.isNullOrBlank()) {
                CbIcon(AppIcons.Image, contentDescription = null, modifier = Modifier.align(Alignment.Center).size(28.dp), tint = ChatBarTheme.colors.mutedForeground)
                AsyncImage(File(post.imagePath), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(Modifier.fillMaxSize().padding(ChatBarSpacing.sm), verticalArrangement = Arrangement.SpaceBetween) {
                    CbText(if (post.isPlaceholder) "生成失败" else "未生成图片", style = ChatBarTheme.typography.caption,
                        color = if (post.isPlaceholder) ChatBarTheme.colors.destructive else ChatBarTheme.colors.mutedForeground)
                    CbText(post.text.ifBlank { post.failureReason ?: "暂无文字" }, style = ChatBarTheme.typography.caption,
                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            if (folder) CbIcon(AppIcons.Layers, contentDescription = null, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                .background(ChatBarTheme.colors.surface).padding(4.dp).size(16.dp))
            if (post.isPrivate) CbIcon(AppIcons.Lock, contentDescription = "仅你可见", modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .background(ChatBarTheme.colors.surface).padding(4.dp).size(14.dp))
        }
        CbText(title, Modifier.padding(top = 4.dp), style = ChatBarTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        CbText(subtitle, style = ChatBarTheme.typography.caption, color = ChatBarTheme.colors.mutedForeground, maxLines = 1)
    }
}

private fun MomentAlbumDateFilter.label(): String = when (granularity) {
    MomentAlbumDateGranularity.YEAR -> "${year}年"
    MomentAlbumDateGranularity.MONTH -> "${year}年${month}月"
    MomentAlbumDateGranularity.DAY -> "${year}年${month}月${day}日"
}

@Composable
private fun MomentAlbumDateDialog(dates: List<LocalDate>, current: MomentAlbumDateFilter?, onDismiss: () -> Unit, onApply: (MomentAlbumDateFilter?) -> Unit) {
    val latest = dates.firstOrNull() ?: LocalDate.now()
    var choice by remember { mutableStateOf(current ?: MomentAlbumDateFilter(latest.year, latest.monthValue, latest.dayOfMonth)) }
    val years = remember(dates, choice.year) { (dates.map { it.year } + choice.year).distinct().sortedDescending() }
    val maxDay = YearMonth.of(choice.year, choice.month).lengthOfMonth()
    CbDialog(onDismissRequest = onDismiss, title = "日期筛选",
        confirm = { CbButton("应用", { onApply(choice.copy(day = choice.day.coerceAtMost(maxDay))) }) },
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)) {
                MomentAlbumDateGranularity.entries.forEach { granularity ->
                    CbChoiceChip(when (granularity) {
                        MomentAlbumDateGranularity.DAY -> "按日"
                        MomentAlbumDateGranularity.MONTH -> "按月"
                        MomentAlbumDateGranularity.YEAR -> "按年"
                    }, choice.granularity == granularity, { choice = choice.copy(granularity = granularity) }, Modifier.weight(1f))
                }
            }
            CbSelect(choice.year, years, { "${it}年" }, { choice = choice.copy(year = it) })
            if (choice.granularity != MomentAlbumDateGranularity.YEAR) CbSelect(choice.month, (1..12).toList(), { "${it}月" }, { choice = choice.copy(month = it) })
            if (choice.granularity == MomentAlbumDateGranularity.DAY) CbSelect(choice.day.coerceAtMost(maxDay), (1..maxDay).toList(), { "${it}日" }, { choice = choice.copy(day = it) })
            CbButton("全部日期", { onApply(null) }, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
        }
    }
}
