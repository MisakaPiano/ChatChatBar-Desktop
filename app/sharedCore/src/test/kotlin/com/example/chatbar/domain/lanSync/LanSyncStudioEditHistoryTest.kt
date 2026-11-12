package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.image.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncStudioEditHistoryTest {
    @Test fun existingTimelineShapeAndPendingResolutionRetainBothAlternativesWithoutGuessing() {
        val first = NovelAiStudioDraft(basePrompt = "合成第一份原文", negativePrompt = "合成负面", updatedAt = 0)
        val second = first.copy(basePrompt = "合成第二份原文")
        val old = NovelAiStudioHistoryTimeline(listOf(first), 0)
        val next = NovelAiStudioHistoryTimeline(listOf(first, second), 1)
        val journal = NovelAiStudioHistoryJournal(old, next)
        val body = lanSyncJson.encodeToJsonElement(NovelAiStudioHistoryJournal.serializer(), journal).jsonObject
        assertEquals(setOf("committed", "pending"), body.keys)
        assertEquals(setOf("entries", "cursor"), body.getValue("committed").jsonObject.keys)
        val reopened = lanSyncJson.decodeFromJsonElement(NovelAiStudioHistoryJournal.serializer(), body)
        assertEquals(journal, reopened)
        assertEquals(old, reopened.resolved(first))
        assertEquals(next, reopened.resolved(second))
        assertFailsWith<IllegalStateException> { reopened.resolved(first.copy(basePrompt = "不能推测的第三份正文")) }
        assertFailsWith<IllegalArgumentException> { reopened.copy(committed = old.copy(cursor = 1)).resolved(second) }
        assertFailsWith<IllegalArgumentException> { reopened.copy(pending = next.copy(entries = emptyList())).resolved(first) }
        assertEquals(listOf(first, second), reopened.pending?.entries)
    }
}
