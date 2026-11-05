package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MomentSchedulePreparationTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 100 * day
    private fun card(id: String, enabled: Boolean = true) = CharacterCard(id, "合成卡$id", momentsEnabled = enabled, createdAt = 1, updatedAt = 1)
    private fun session(card: String, active: Long? = now) = ChatSession("session-$card", card, "合成会话", lastMessageTime = active, createdAt = 1, updatedAt = 1)
    private val settings = AppSettings(momentsEnabled = true, momentsMinDelayHours = 2, momentsMaxDelayHours = 2)

    @Test fun `future preparation enforces per card and global UTC day limits without processing due tasks`() = runBlocking {
        val cards = (1..24).map { card("$it") }; val sessions = cards.map { session(it.id) }
        val due = MomentTask("due", cards.first().id, sessions.first().id, now - hour, createdAt = 1)
        val tasks = mutableListOf(due)
        val planner = MomentSchedulePreparation({ cards }, { sessions }, { emptyList() }, { tasks.toList() }, { tasks += it })
        planner.ensureSchedules(now, settings)
        assertEquals(due, tasks.first())
        assertTrue(tasks.drop(1).isNotEmpty())
        assertTrue(tasks.drop(1).all { it.scheduledAt > now && it.scheduledAt <= now + 7 * day && it.status == MomentTaskStatus.PENDING })
        assertTrue(tasks.groupBy { it.scheduledAt / day }.values.all { it.size <= 18 })
        assertTrue(tasks.groupBy { it.characterCardId to it.scheduledAt / day }.values.all { it.size <= 4 })
        val before = tasks.toList(); planner.ensureSchedules(now, settings)
        assertEquals(before, tasks)
    }

    @Test fun `disabled or inactive cards leave all existing task and post history untouched`() = runBlocking {
        val cards = listOf(card("disabled", false), card("old"), card("no-session"))
        val sessions = listOf(session("disabled"), session("old", now - 49 * hour))
        val tasks = listOf(MomentTask("due", "disabled", "session-disabled", now - hour, status = MomentTaskStatus.FAILED, createdAt = 1))
        val posts = listOf(MomentPost("post", "disabled", "session-disabled", senderName = "合成作者", text = "历史正文", scheduledAt = now - hour, generatedAt = now - hour))
        var saved = 0
        MomentSchedulePreparation({ cards }, { sessions }, { posts }, { tasks }, { saved++ }).ensureSchedules(now, settings)
        assertEquals(0, saved); assertEquals(MomentTaskStatus.FAILED, tasks.single().status)
        assertEquals("历史正文", posts.single().text)
    }

    @Test fun `failed task persistence propagates and retry preserves previously committed preparation prefix`() = runBlocking {
        val tasks = mutableListOf<MomentTask>(); var calls = 0; var fail = true
        val planner = MomentSchedulePreparation({ listOf(card("card")) }, { listOf(session("card")) }, { emptyList() }, { tasks.toList() }, {
            calls++
            if (fail && calls == 2) error("synthetic storage failure")
            tasks += it
        })
        assertFailsWith<IllegalStateException> { planner.ensureSchedules(now, settings) }
        val committed = tasks.single(); assertEquals(MomentTaskStatus.PENDING, committed.status)
        fail = false; planner.ensureSchedules(now, settings)
        assertEquals(committed, tasks.first())
        assertEquals(1, tasks.count { it.id == committed.id })
        assertTrue(tasks.drop(1).all { it.scheduledAt > committed.scheduledAt })
    }
}
