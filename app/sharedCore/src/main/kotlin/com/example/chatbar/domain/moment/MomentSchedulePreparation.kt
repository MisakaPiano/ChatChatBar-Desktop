package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask

/** Original future-record preparation only. Callers own enable/admission, serialization and due execution. */
class MomentSchedulePreparation(
    private val loadCards: suspend () -> List<CharacterCard>,
    private val loadSessions: suspend () -> List<ChatSession>,
    private val loadPosts: suspend () -> List<MomentPost>,
    private val loadTasks: suspend () -> List<MomentTask>,
    private val saveTask: suspend (MomentTask) -> Unit,
) {
    suspend fun ensureSchedules(now: Long, settings: AppSettings) {
        val horizon = now + MomentPolicy.SCHEDULE_HORIZON_MS
        val cards = loadCards()
            .filter { it.momentsEnabled }
            .associateBy { it.id }
        if (cards.isEmpty()) return

        val sessionsByCard = activeSessionsByCard(now)
        val allPosts = loadPosts()
        val allTasks = loadTasks()
        val scheduledTimesByCard = cards.keys.associateWith { cardId ->
            MomentPolicy.scheduledTimesForLimit(
                posts = allPosts.filter { it.characterCardId == cardId },
                tasks = allTasks.filter { it.characterCardId == cardId }
            ).toMutableList()
        }.toMutableMap()
        val globalScheduledTimes = MomentPolicy.scheduledTimesForLimit(allPosts, allTasks).toMutableList()

        cards.values.forEach { card ->
            val session = sessionsByCard[card.id] ?: return@forEach
            val cardTimes = scheduledTimesByCard.getValue(card.id)
            var cursor = (cardTimes.maxOrNull() ?: now).coerceAtLeast(now)
            var guard = 0
            while (cursor < horizon && guard++ < 128) {
                val scheduledAt = cursor + MomentPolicy.nextDelayMs(
                    seed = card.id,
                    cursorMs = cursor,
                    minDelayHours = settings.momentsMinDelayHours,
                    maxDelayHours = settings.momentsMaxDelayHours
                )
                cursor = scheduledAt
                if (scheduledAt > horizon) break
                val cardDayCount = MomentPolicy.countForDay(cardTimes, scheduledAt)
                val globalDayCount = MomentPolicy.countForDay(globalScheduledTimes, scheduledAt)
                if (
                    cardDayCount >= MomentPolicy.MAX_CARD_POSTS_PER_DAY ||
                    globalDayCount >= MomentPolicy.MAX_GLOBAL_POSTS_PER_DAY
                ) {
                    continue
                }
                val task = MomentTask.create(
                    characterCardId = card.id,
                    sessionId = session.id,
                    scheduledAt = scheduledAt,
                    now = now
                )
                saveTask(task)
                cardTimes += scheduledAt
                globalScheduledTimes += scheduledAt
            }
        }
    }

    private suspend fun activeSessionsByCard(now: Long): Map<String, ChatSession> =
        loadSessions()
            .asSequence()
            .filter { MomentPolicy.isRecentlyActive(it.lastMessageTime, now) }
            .groupBy { it.characterCardId }
            .mapValues { (_, sessions) -> sessions.maxBy { it.lastMessageTime ?: it.createdAt } }

}
