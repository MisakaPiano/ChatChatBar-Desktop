package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.MomentPost
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MomentAlbumPolicyTest {
    private val zone = ZoneId.of("Asia/Hong_Kong")
    private fun post(id: String, card: String = "card", at: String = "2026-09-30T16:30:00Z", text: String = "海边") = MomentPost(
        id = id, characterCardId = card, sessionId = "session", senderName = "角色", text = text,
        scheduledAt = 0, generatedAt = Instant.parse(at).toEpochMilli()
    )

    @Test
    fun `groups by card identity and retains deleted cards and text-only posts`() {
        val posts = listOf(post("a", "one"), post("b", "two"), post("c", "gone").copy(isPlaceholder = true))
        val state = MomentAlbumPolicy.build(posts, mapOf("one" to "同名", "two" to "同名"), MomentAlbumFilter(), zone)
        assertEquals(3, state.groups.size)
        assertEquals(setOf("one", "two", "gone"), state.groups.map { it.characterCardId }.toSet())
        assertEquals(2, state.groups.count { it.name == "同名" })
        assertTrue(state.groups.last().name.contains("已删除"))
        assertEquals(posts, state.posts)
    }

    @Test
    fun `date filters use local generation date and intersect keyword fields`() {
        val posts = listOf(post("oct", text = "SUNSET 海边"), post("sep", at = "2026-09-30T15:59:59Z", text = "sunset 海边"))
        val date = MomentAlbumDateFilter(2026, 10, 1)
        val state = MomentAlbumPolicy.build(posts, mapOf("card" to "旅行者"), MomentAlbumFilter(query = "旅行者 sunset", date = date), zone)
        assertEquals(listOf("oct"), state.posts.map { it.id })
        assertEquals(1, state.groups.single().posts.size)
        assertEquals(2, state.dates.size)
        assertEquals("2026-10-01", state.dates.first().toString())
        assertTrue(MomentAlbumPolicy.build(posts, emptyMap(), MomentAlbumFilter(query = "sunset 不存在", date = date), zone).posts.isEmpty())
    }

    @Test
    fun `year month day filters handle leap days and ignore update timestamps`() {
        val posts = listOf(post("leap", at = "2024-02-29T04:00:00Z"), post("march", at = "2024-03-01T04:00:00Z"),
            post("old", at = "2023-02-28T04:00:00Z").copy(updatedAt = Instant.parse("2024-02-29T04:00:00Z").toEpochMilli()))
        fun ids(date: MomentAlbumDateFilter) = MomentAlbumPolicy.build(posts, emptyMap(), MomentAlbumFilter(date = date), zone).posts.map { it.id }
        assertEquals(listOf("leap"), ids(MomentAlbumDateFilter(2024, 2, 29)))
        assertEquals(listOf("leap"), ids(MomentAlbumDateFilter(2024, 2, granularity = MomentAlbumDateGranularity.MONTH)))
        assertEquals(listOf("march", "leap"), ids(MomentAlbumDateFilter(2024, granularity = MomentAlbumDateGranularity.YEAR)))
    }

    @Test
    fun `search includes saved sender prompt and brief and follows card renames`() {
        val posts = listOf(post("a").copy(imagePrompt = "blue SKY", imageBrief = "夜晚"))
        for (query in listOf("角色", "blue sky", "夜晚", "新名字")) {
            assertEquals(1, MomentAlbumPolicy.build(posts, mapOf("card" to "新名字"), MomentAlbumFilter(query = query), zone).posts.size)
        }
        assertTrue(MomentAlbumPolicy.build(posts, mapOf("card" to "新名字"), MomentAlbumFilter(query = "旧名字"), zone).posts.isEmpty())
    }

    @Test
    fun `selected folder intersects filters and flat mode restores all cards`() {
        val posts = listOf(post("a", "one"), post("b", "two"))
        val selected = MomentAlbumFilter(characterCardId = "two")
        assertEquals(listOf("b"), MomentAlbumPolicy.build(posts, emptyMap(), selected, zone).posts.map { it.id })
        assertEquals(2, MomentAlbumPolicy.build(posts, emptyMap(), selected.copy(groupByCard = false), zone).posts.size)
        assertTrue(MomentAlbumPolicy.build(posts, emptyMap(), selected.copy(query = "不存在"), zone).posts.isEmpty())
    }

    @Test
    fun `timeline jump resolves current full list by identity after insertions or deletions`() {
        val target = post("target", at = "2025-01-01T00:00:00Z")
        val newer = post("new")
        assertEquals(1, MomentAlbumPolicy.timelineIndex(listOf(newer, target), target.id))
        assertEquals(0, MomentAlbumPolicy.timelineIndex(listOf(target), target.id))
        assertEquals(-1, MomentAlbumPolicy.timelineIndex(listOf(newer), target.id))
    }
}
