package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable

/** Portable metadata for the existing Desktop undo owner; this does not start or replay an edit. */
@Serializable
data class NovelAiStudioHistoryTimeline(
    val entries: List<NovelAiStudioDraft> = emptyList(),
    val cursor: Int = 0,
) {
    fun current() = entries.getOrNull(cursor)
    fun validate() {
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES && cursor in entries.indices)
    }
    fun appendSnapshot(snapshot: NovelAiStudioDraft, encodedSize: (List<NovelAiStudioDraft>) -> Int): NovelAiStudioHistoryTimeline {
        validate()
        if (current() == snapshot) return this
        var next = entries.take(cursor + 1) + snapshot
        val budget = maxOf(MIN_BYTE_BUDGET, encodedSize(listOf(requireNotNull(current()), snapshot)))
        while (next.size > 2 && (next.size > MAX_ENTRIES || encodedSize(next) > budget)) next = next.drop(1)
        return NovelAiStudioHistoryTimeline(next, next.lastIndex)
    }
    companion object {
        const val MAX_ENTRIES = 101
        const val MIN_BYTE_BUDGET = 3 * 1024 * 1024
    }
}

fun NovelAiStudioDraft.toEditHistorySnapshot(retainPayload: (String) -> String): NovelAiStudioDraft =
    copy(contentRevision = 0, promptContentRevision = 0, updatedAt = 0,
        imageGuidance = imageGuidance.copy(vibes = imageGuidance.vibes.map { vibe ->
            vibe.copy(encodedVibe = vibe.encodedVibe?.let { if (it.startsWith(NovelAiInlinePayloadStore.PREFIX)) it else retainPayload(it) })
        }))

/** Both alternatives remain referenced until the original owner's persisted draft proves one. */
@Serializable
data class NovelAiStudioHistoryJournal(
    val committed: NovelAiStudioHistoryTimeline = NovelAiStudioHistoryTimeline(),
    val pending: NovelAiStudioHistoryTimeline? = null,
) {
    fun resolved(current: NovelAiStudioDraft): NovelAiStudioHistoryTimeline {
        committed.validate(); pending?.validate()
        return when (current) {
            pending?.current() -> requireNotNull(pending)
            committed.current() -> committed
            else -> error("Studio history and draft require recovery")
        }
    }
}
