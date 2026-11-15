package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder

private fun <T> JsonFileStorage.strictStudioSerializer(serializer: KSerializer<T>) = object : KSerializer<T> {
    private val strictJson = Json(json) { ignoreUnknownKeys = false }
    override val descriptor = serializer.descriptor
    override fun serialize(encoder: Encoder, value: T) = serializer.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): T {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Studio history requires JSON")
        return strictJson.decodeFromJsonElement(serializer, json.decodeJsonElement())
    }
}

suspend fun JsonFileStorage.loadStudioHistoryJournalStrict(): NovelAiStudioHistoryJournal? =
    loadSingleton("desktop_studio_edit_history", strictStudioSerializer(NovelAiStudioHistoryJournal.serializer()))

suspend fun JsonFileStorage.loadStudioDraftStrict(): NovelAiStudioDraft? =
    loadSingleton("novelai_studio_draft", strictStudioSerializer(NovelAiStudioDraft.serializer()))

/** Android retains an imported Desktop timeline; it never invents one for an ordinary Android draft. */
internal class ForeignStudioEditHistoryCustody(private val storage: JsonFileStorage) {
    private val journalSerializer = storage.strictStudioSerializer(NovelAiStudioHistoryJournal.serializer())
    private val draftSerializer = storage.strictStudioSerializer(NovelAiStudioDraft.serializer())
    private fun snapshot(draft: NovelAiStudioDraft) = draft.toEditHistorySnapshot(storage::retainStudioPayload)
    private fun size(entries: List<NovelAiStudioDraft>) = storage.json.encodeToString(
        NovelAiStudioHistoryTimeline.serializer(), NovelAiStudioHistoryTimeline(entries)).toByteArray(Charsets.UTF_8).size

    suspend fun prepareForLanSync() {
        val journal = storage.loadSingleton(ENTITY, journalSerializer) ?: return
        val draft = requireNotNull(storage.loadSingleton(DRAFT, draftSerializer)) { "SYNC_STUDIO_DRAFT_MISSING" }
        val resolved = journal.resolved(snapshot(draft))
        if (journal.pending != null) storage.saveSingleton(ENTITY,
            NovelAiStudioHistoryJournal(resolved), NovelAiStudioHistoryJournal.serializer())
    }

    suspend fun retainedHistoryDrafts(): List<NovelAiStudioDraft> {
        val journal = storage.loadSingleton(ENTITY, journalSerializer) ?: return emptyList()
        journal.committed.validate(); journal.pending?.validate()
        val current = requireNotNull(storage.loadSingleton(DRAFT, draftSerializer)) { "SYNC_STUDIO_DRAFT_MISSING" }
        journal.resolved(snapshot(current))
        return journal.committed.entries + journal.pending?.entries.orEmpty()
    }

    suspend fun currentEditorTimeline(): NovelAiStudioHistoryTimeline? {
        val journal = storage.loadSingleton(ENTITY, journalSerializer) ?: return null
        val current = requireNotNull(storage.loadSingleton(DRAFT, draftSerializer)) { "SYNC_STUDIO_DRAFT_MISSING" }
        return journal.resolved(snapshot(current))
    }

    /** An explicit snapshot preparation may persist live Android undo, without changing ordinary flush behavior. */
    suspend fun retainEditorTimeline(timeline: NovelAiStudioHistoryTimeline) {
        val persisted = storage.loadSingleton(DRAFT, draftSerializer)
        if (persisted == null) {
            require(timeline.entries.size == 1) { "SYNC_STUDIO_DRAFT_MISSING" }
            return
        }
        val normalized = timeline.copy(entries = timeline.entries.map(::snapshot))
        normalized.validate()
        require(normalized.current() == snapshot(persisted)) { "SYNC_STUDIO_HISTORY_MISMATCH" }
        val existing = currentEditorTimeline()
        if (existing == null && normalized.entries.size == 1) return
        require(size(normalized.entries) <= 8 * 1024 * 1024) { "SYNC_RECORD_LIMIT" }
        if (existing != normalized) storage.saveSingleton(ENTITY,
            NovelAiStudioHistoryJournal(normalized), NovelAiStudioHistoryJournal.serializer())
    }

    /** Called under the repository's ordinary admission and draft mutex, once per exact draft write. */
    suspend fun persist(draft: NovelAiStudioDraft, writeDraft: suspend () -> Unit) {
        val journal = storage.loadSingleton(ENTITY, journalSerializer)
        if (journal == null) {
            writeDraft()
        } else withContext(NonCancellable) {
            val persisted = requireNotNull(storage.loadSingleton(DRAFT, draftSerializer)) { "Studio history requires its persisted draft" }
            val before = journal.resolved(snapshot(persisted))
            val after = before.appendSnapshot(snapshot(draft), ::size)
            if (journal.pending == null && draft == persisted) return@withContext
            storage.saveSingleton(ENTITY, NovelAiStudioHistoryJournal(before, after), NovelAiStudioHistoryJournal.serializer())
            writeDraft()
            storage.saveSingleton(ENTITY, NovelAiStudioHistoryJournal(after), NovelAiStudioHistoryJournal.serializer())
        }
    }

    sealed interface Outcome {
        data object Unmanaged : Outcome
        data class Proved(val draft: NovelAiStudioDraft, val requestedWasPublished: Boolean) : Outcome
        data class Unproved(val failure: Exception) : Outcome
    }

    /** A reported write failure is not evidence that the draft write rolled back. */
    suspend fun outcomeAfterFailure(requested: NovelAiStudioDraft): Outcome = withContext(NonCancellable) {
        try {
            val journal = storage.loadSingleton(ENTITY, journalSerializer)
            if (journal == null) Outcome.Unmanaged else {
                val actual = requireNotNull(storage.loadSingleton(DRAFT, draftSerializer)) { "Studio history requires its persisted draft" }
                val actualSnapshot = snapshot(actual)
                journal.resolved(actualSnapshot)
                Outcome.Proved(actual, actualSnapshot == snapshot(requested))
            }
        } catch (failure: Exception) { Outcome.Unproved(failure) }
    }

    companion object {
        const val ENTITY = "desktop_studio_edit_history"
        private const val DRAFT = "novelai_studio_draft"
    }
}
