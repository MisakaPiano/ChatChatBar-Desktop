@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.moment.*
import java.time.LocalDate

/** Independent Moments album; its only navigation payload is the original post ID. */
@Composable internal fun DesktopMomentAlbum(posts: List<MomentPost>, cardNames: Map<String, String>,
    resources: DesktopCharacterResourceStore?, selection: DesktopMomentAlbumSelection,
    change: ((DesktopMomentAlbumSelection) -> DesktopMomentAlbumSelection) -> Unit,
    onClose: () -> Unit, onPost: (String) -> Unit) {
    val filter = selection.filter
    val granularity = selection.granularity
    val selectedDate = selection.selectedDate?.toString() ?: "全部日期"
    val gridState = rememberLazyGridState()
    LaunchedEffect(filter) { gridState.scrollToItem(0) }
    val state = remember(posts, cardNames, filter) { MomentAlbumPolicy.build(posts, cardNames, filter) }
    fun applyDate(text: String, kind: MomentAlbumDateGranularity? = null) {
        val date = runCatching { LocalDate.parse(text) }.getOrNull()
        change { current ->
            val nextKind = kind ?: current.granularity
            current.copy(selectedDate = date, granularity = nextKind,
                filter = current.filter.copy(date = date?.let { MomentAlbumDateFilter(it.year, it.monthValue, it.dayOfMonth, nextKind) }))
        }
    }
    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioActions {
            StudioAction(if (filter.characterCardId == null) "返回朋友圈" else "返回相册分组") {
                if (filter.characterCardId == null) onClose() else change { it.copy(filter = it.filter.copy(characterCardId = null)) }
            }
            ManageHeading(state.selectedCardName ?: "朋友圈相册")
        }
        StudioField("搜索相册", filter.query) { text -> change { it.copy(filter = it.filter.copy(query = text)) } }
        StudioActions {
            StudioToggle("按角色卡分组", filter.groupByCard) { change { it.copy(filter = it.filter.copy(groupByCard = !it.filter.groupByCard, characterCardId = null)) } }
            CompactChoice("日期", listOf("全部日期") + state.dates.map { it.toString() }, selectedDate, { it }) { applyDate(it) }
            CompactChoice("日期范围", MomentAlbumDateGranularity.entries, granularity, {
                when (it) { MomentAlbumDateGranularity.DAY -> "按日"; MomentAlbumDateGranularity.MONTH -> "按月"; MomentAlbumDateGranularity.YEAR -> "按年" }
            }) { applyDate(selectedDate, it) }
        }
        StatusText("${state.posts.size} 条 · ${state.groups.size} 组 · 点击动态返回原朋友圈")
        LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.weight(1f).fillMaxWidth(), state = gridState,
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filter.groupByCard && filter.characterCardId == null) {
                items(state.groups, key = { it.characterCardId }) { group ->
                    val cover = group.posts.firstOrNull { !it.imagePath.isNullOrBlank() } ?: group.posts.first()
                    AlbumTile(cover, group.name, "${group.posts.size} 条 · 分组" +
                        if (group.posts.any { it.isPrivate }) " · 含私密动态" else "", resources,
                        "相册分组 ${group.characterCardId}") { change { it.copy(filter = it.filter.copy(characterCardId = group.characterCardId)) } }
                }
            } else items(state.posts, key = { it.id }) { post ->
                AlbumTile(post, post.senderName, MomentAlbumPolicy.date(post.generatedAt).toString(), resources,
                    "返回朋友圈动态 ${post.id}") { onPost(post.id) }
            }
        }
    }
}

@Composable private fun AlbumTile(post: MomentPost, name: String, caption: String,
    resources: DesktopCharacterResourceStore?, description: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(DesktopBootstrapColors.card).clickable(onClick = onClick)
        .semantics { contentDescription = description }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val image = post.imagePath
        if (!image.isNullOrBlank() && resources != null)
            DesktopOwnedImage(image, resources::readBytes, Modifier.fillMaxWidth().aspectRatio(1f), crop = true)
        else Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) { StatusText(post.text.ifBlank { "暂无图片" }.take(120)) }
        StatusText(name, DesktopBootstrapColors.primary)
        StatusText(caption)
        if (post.isPrivate) StatusText("仅你可见")
        if (post.isPlaceholder) StatusText("生成未完成", DesktopBootstrapColors.warning)
    }
}
