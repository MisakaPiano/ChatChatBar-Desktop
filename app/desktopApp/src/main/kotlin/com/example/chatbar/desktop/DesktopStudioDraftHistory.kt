package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.domain.image.NovelAiStudioDraft
import com.example.chatbar.domain.image.NovelAiInlinePayloadStore
import com.example.chatbar.domain.image.ownedAssetPaths
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private class DesktopStudioEditingContext(val boundary: DesktopStudioTextBoundary) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopStudioEditingContext>
}

@Serializable
internal data class DesktopStudioHistoryTimeline(
    val entries: List<NovelAiStudioDraft> = emptyList(), val cursor: Int = 0,
) {
    fun current() = entries.getOrNull(cursor)
}

/** Write-ahead intent keeps both alternatives referenced until the draft write is resolved. */
@Serializable
internal data class DesktopStudioHistoryJournal(
    val committed: DesktopStudioHistoryTimeline = DesktopStudioHistoryTimeline(),
    val pending: DesktopStudioHistoryTimeline? = null,
)

internal data class DesktopStudioHistoryAvailability(val undo: Boolean = false, val redo: Boolean = false,
    val replayRevision: Long = 0)

/** Desktop only. The shared/Android repository retains its original one-snapshot behavior. */
internal class DesktopStudioHistoryRepository(storage: JsonFileStorage,
    private val clock: () -> Long = System::nanoTime,
) : NovelAiStudioStateRepository(storage) {
    private val editHistoryMutex = Mutex()
    private suspend fun <T> withEditHistoryDataAccess(operation: suspend () -> T): T =
        withStudioDataAccess { editHistoryMutex.withLock { operation() } }
    private val available = MutableStateFlow(DesktopStudioHistoryAvailability())
    val historyAvailability = available.asStateFlow()
    private var timeline: DesktopStudioHistoryTimeline? = null
    private var lastTypingKey: String? = null
    private var lastTypingAt = 0L
    private val retiredResources = linkedSetOf<String>()
    suspend fun takeRetiredResources(): List<String> = editHistoryMutex.withLock {
        retiredResources.toList().also { retiredResources.clear() }
    }
    suspend fun updateFromField(boundary: DesktopStudioTextBoundary, transform: (NovelAiStudioDraft) -> NovelAiStudioDraft) =
        withContext(DesktopStudioEditingContext(boundary)) { updateDraft(transform = transform) }

    private fun snapshot(draft: NovelAiStudioDraft) = draft.copy(contentRevision = 0, promptContentRevision = 0,
        updatedAt = 0, imageGuidance = draft.imageGuidance.copy(vibes = draft.imageGuidance.vibes.map { vibe ->
            vibe.copy(encodedVibe = vibe.encodedVibe?.let { if (it.startsWith(NovelAiInlinePayloadStore.PREFIX)) it else storage.retainStudioPayload(it) })
        }))

    private suspend fun store(value: DesktopStudioHistoryJournal) =
        storage.saveSingleton(ENTITY, value, DesktopStudioHistoryJournal.serializer())

    private fun publish(value: DesktopStudioHistoryTimeline, replay: Boolean = false) {
        timeline = value
        available.value = DesktopStudioHistoryAvailability(value.cursor > 0, value.cursor < value.entries.lastIndex,
            available.value.replayRevision + if (replay) 1 else 0)
    }

    private suspend fun loaded(current: NovelAiStudioDraft): DesktopStudioHistoryTimeline {
        timeline?.let { return it }
        val journal = storage.loadSingleton(ENTITY, DesktopStudioHistoryJournal.serializer())
        val value = if (journal == null) {
            // Preserve the pre-upgrade durable single undo as the first available step.
            val legacy = loadUndoDraft()?.let(::snapshot)?.takeIf { it != current }
            DesktopStudioHistoryTimeline(listOfNotNull(legacy, current), if (legacy == null) 0 else 1)
        } else {
            fun validate(t: DesktopStudioHistoryTimeline) {
                require(t.entries.isNotEmpty() && t.entries.size <= MAX_ENTRIES && t.cursor in t.entries.indices)
            }
            validate(journal.committed); journal.pending?.let(::validate)
            when (current) {
                journal.pending?.current() -> requireNotNull(journal.pending)
                journal.committed.current() -> journal.committed
                else -> error("Studio history and draft require recovery")
            }
        }
        store(DesktopStudioHistoryJournal(value))
        publish(value)
        return value
    }

    suspend fun initializeHistory() = withEditHistoryDataAccess {
        // The initial default draft must travel with its timeline in a selected backup.
        loadDraft()
        super.updateDraft(resetPromptEditors = false) { it }
        loaded(snapshot(loadDraft())); Unit
    }

    override suspend fun updateDraft(resetPromptEditors: Boolean,
        transform: (NovelAiStudioDraft) -> NovelAiStudioDraft): NovelAiStudioDraft = withEditHistoryDataAccess {
        val before = loadDraft()
        val target = transform(before)
        val current = snapshot(before)
        val after = snapshot(target)
        val history = loaded(current)
        check(history.current() == current) { "Studio history changed outside its owner" }
        val compactTarget = target.copy(imageGuidance = after.imageGuidance)
        if (after == current) return@withEditHistoryDataAccess super.updateDraft(resetPromptEditors) { compactTarget }
        val now = clock()
        val boundary = currentCoroutineContext()[DesktopStudioEditingContext]?.boundary ?: DesktopStudioTextBoundary()
        val key = desktopStudioTypingKey(current, after)
        val merge = !resetPromptEditors && !boundary.separate && key != null && key == lastTypingKey && now - lastTypingAt in 0..750_000_000L &&
            history.cursor == history.entries.lastIndex && history.cursor > 0
        var entries = if (merge) history.entries.dropLast(1) + after else history.entries.take(history.cursor + 1) + after
        // Keep at least the current edit reversible even for an already valid unusually large
        // text draft; the history budget must not become a new input-size restriction.
        val budget = maxOf(MAX_BYTES, encodedSize(listOf(current, after)))
        while (entries.size > 2 && (entries.size > MAX_ENTRIES || encodedSize(entries) > budget)) entries = entries.drop(1)
        val next = DesktopStudioHistoryTimeline(entries, entries.lastIndex)
        val result = transition(history, next) { super.updateDraft(resetPromptEditors) { compactTarget } }
        retiredResources += history.entries.flatMap(::historyResources).toSet() - next.entries.flatMap(::historyResources).toSet()
        lastTypingKey = if (resetPromptEditors || boundary.discrete) null else key
        lastTypingAt = now
        result
    }

    private fun encodedSize(entries: List<NovelAiStudioDraft>) =
        storage.json.encodeToString(DesktopStudioHistoryTimeline.serializer(), DesktopStudioHistoryTimeline(entries)).toByteArray().size

    private fun historyResources(draft: NovelAiStudioDraft) = draft.imageGuidance.ownedAssetPaths() +
        draft.imageGuidance.vibes.mapNotNull { it.encodedVibe?.takeIf { value -> value.startsWith(NovelAiInlinePayloadStore.PREFIX) }
            ?.removePrefix(NovelAiInlinePayloadStore.PREFIX)?.let { hash -> "novelai-vibe-payloads/$hash" } }

    private suspend fun transition(before: DesktopStudioHistoryTimeline, after: DesktopStudioHistoryTimeline,
        write: suspend () -> NovelAiStudioDraft): NovelAiStudioDraft = withContext(NonCancellable) {
        store(DesktopStudioHistoryJournal(before, after))
        try {
            val result = write()
            store(DesktopStudioHistoryJournal(after)); publish(after)
            result
        } catch (failure: Throwable) {
            // Actual persisted outcome, never an assumed rollback or an erased failure.
            timeline = null
            try {
                val persisted = requireNotNull(storage.loadSingleton("novelai_studio_draft", NovelAiStudioDraft.serializer()))
                loaded(snapshot(persisted))
            } catch (recovery: Throwable) { failure.addSuppressed(recovery) }
            throw failure
        }
    }

    suspend fun replay(redo: Boolean): Boolean = withEditHistoryDataAccess {
        val before = loaded(snapshot(loadDraft()))
        val cursor = before.cursor + if (redo) 1 else -1
        if (cursor !in before.entries.indices) return@withEditHistoryDataAccess false
        val after = before.copy(cursor = cursor)
        transition(before, after) { super.updateDraft(resetPromptEditors = true) { after.current()!! } }
        lastTypingKey = null; publish(after, replay = true)
        true
    }

    companion object {
        const val ENTITY = "desktop_studio_edit_history"
        const val MAX_ENTRIES = 101
        private const val MAX_BYTES = 3 * 1024 * 1024
    }
}

/** Only adjacent small text edits merge; paste, clear, parameters and mixed actions stay distinct. */
internal fun desktopStudioTypingKey(before: NovelAiStudioDraft, after: NovelAiStudioDraft): String? {
    fun small(a: String, b: String) = desktopStudioSmallTextEdit(a, b)
    val fields = listOf("style" to (before.stylePrompt to after.stylePrompt), "base" to (before.basePrompt to after.basePrompt),
        "extra" to (before.extraPrompt to after.extraPrompt), "negative" to (before.negativePrompt to after.negativePrompt),
        "requirement" to (before.extraRequirement to after.extraRequirement))
    for ((key, pair) in fields) if (small(pair.first, pair.second)) {
        val restored = when (key) {
            "style" -> after.copy(stylePrompt = before.stylePrompt)
            "base" -> after.copy(basePrompt = before.basePrompt)
            "extra" -> after.copy(extraPrompt = before.extraPrompt)
            "negative" -> after.copy(negativePrompt = before.negativePrompt)
            else -> after.copy(extraRequirement = before.extraRequirement)
        }
        if (restored == before) return key
    }
    if (before.characters.map { it.id } == after.characters.map { it.id }) {
        for (i in before.characters.indices) {
            val a = before.characters[i]; val b = after.characters[i]
            val field = when {
                small(a.prompt, b.prompt) && b.copy(prompt = a.prompt) == a -> "positive"
                small(a.negativePrompt, b.negativePrompt) && b.copy(negativePrompt = a.negativePrompt) == a -> "negative"
                else -> null
            }
            if (field != null && after.copy(characters = after.characters.toMutableList().also { it[i] = a }) == before)
                return "person:${a.id}:$field"
        }
    }
    return null
}

internal fun desktopStudioSmallTextEdit(before: String, after: String): Boolean {
    if (before == after) return false
    var start = 0
    while (start < minOf(before.length, after.length) && before[start] == after[start]) start++
    var endBefore = before.length; var endAfter = after.length
    while (endBefore > start && endAfter > start && before[endBefore - 1] == after[endAfter - 1]) { endBefore--; endAfter-- }
    return endBefore - start <= 2 && endAfter - start <= 2 &&
        '\n' !in before.substring(start, endBefore) && '\n' !in after.substring(start, endAfter)
}
