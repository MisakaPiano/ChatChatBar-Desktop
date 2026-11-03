package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.MomentPost
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class MomentAlbumDateGranularity { DAY, MONTH, YEAR }

data class MomentAlbumDateFilter(
    val year: Int,
    val month: Int = 1,
    val day: Int = 1,
    val granularity: MomentAlbumDateGranularity = MomentAlbumDateGranularity.DAY
) {
    fun matches(date: LocalDate): Boolean = date.year == year && when (granularity) {
        MomentAlbumDateGranularity.YEAR -> true
        MomentAlbumDateGranularity.MONTH -> date.monthValue == month
        MomentAlbumDateGranularity.DAY -> date.monthValue == month && date.dayOfMonth == day
    }
}

data class MomentAlbumFilter(
    val query: String = "",
    val date: MomentAlbumDateFilter? = null,
    val groupByCard: Boolean = true,
    val characterCardId: String? = null
)

data class MomentAlbumGroup(
    val characterCardId: String,
    val name: String,
    val posts: List<MomentPost>
)

data class MomentAlbumState(
    val filter: MomentAlbumFilter = MomentAlbumFilter(),
    val posts: List<MomentPost> = emptyList(),
    val groups: List<MomentAlbumGroup> = emptyList(),
    val selectedCardName: String? = null,
    val dates: List<LocalDate> = emptyList()
)

object MomentAlbumPolicy {
    fun date(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    fun build(
        posts: List<MomentPost>,
        cardNames: Map<String, String>,
        filter: MomentAlbumFilter,
        zone: ZoneId = ZoneId.systemDefault()
    ): MomentAlbumState {
        val sorted = posts.sortedWith(compareByDescending<MomentPost> { it.generatedAt }.thenBy { it.id })
        val names = sorted.groupBy { it.characterCardId }.mapValues { (id, group) ->
            cardNames[id]?.takeIf(String::isNotBlank)
                ?: "${group.first().senderName.ifBlank { "未知角色" }}（角色卡已删除）"
        }
        val terms = filter.query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val matched = sorted.filter { post ->
            (filter.date?.matches(date(post.generatedAt, zone)) != false) &&
                terms.all { term ->
                    listOf(names[post.characterCardId].orEmpty(), post.senderName, post.text, post.imagePrompt, post.imageBrief)
                        .any { it.contains(term, ignoreCase = true) }
                }
        }
        val selectedId = filter.characterCardId.takeIf { filter.groupByCard }
        return MomentAlbumState(
            filter = filter,
            posts = matched.filter { selectedId == null || it.characterCardId == selectedId },
            groups = matched.groupBy { it.characterCardId }.map { (id, group) ->
                MomentAlbumGroup(id, names.getValue(id), group)
            },
            selectedCardName = selectedId?.let { names[it] ?: cardNames[it] ?: "角色历史" },
            dates = sorted.map { date(it.generatedAt, zone) }.distinct()
        )
    }

    /** Resolve against the current full timeline, never an album's filtered index. */
    fun timelineIndex(posts: List<MomentPost>, postId: String): Int = posts.indexOfFirst { it.id == postId }
}
