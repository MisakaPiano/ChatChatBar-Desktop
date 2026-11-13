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

/** Android retains an imported Desktop timeline; it never invents one for an ordinary Android draft. */
internal class ForeignStudioEditHistoryCustody(private val storage: JsonFileStorage) {
    private val strictJson = Json(storage.json) { ignoreUnknownKeys = false }
    private fun <T> strict(serializer: KSerializer<T>) = object : KSerializer<T> {
        override val descriptor = serializer.descriptor
        override fun serialize(encoder: Encoder, value: T) = serializer.serialize(encoder, value)
        override fun deserialize(decoder: Decoder): T {
            val json = decoder as? JsonDecoder ?: throw SerializationException("Studio history requires JSON")
            return strictJson.decodeFromJsonElement(serializer, json.decodeJsonElement())
        }
    }
    private val journalSerializer = strict(NovelAiStudioHistoryJournal.serializer())
    private val draftSerializer = strict(NovelAiStudioDraft.serializer())
    private fun snapshot(draft: NovelAiStudioDraft) = draft.toEditHistorySnapshot(storage::retainStudioPayload)
    private fun size(entries: List<NovelAiStudioDraft>) = storage.json.encodeToString(
        NovelAiStudioHistoryTimeline.serializer(), NovelAiStudioHistoryTimeline(entries)).toByteArray(Charsets.UTF_8).size

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
        data class Proved(val draft: NovelAiStudioDraft) : Outcome
        data class Unproved(val failure: Exception) : Outcome
    }

    /** A reported write failure is not evidence that the draft write rolled back. */
    suspend fun outcomeAfterFailure(): Outcome = withContext(NonCancellable) {
        try {
            val journal = storage.loadSingleton(ENTITY, journalSerializer)
            if (journal == null) Outcome.Unmanaged else {
                val actual = requireNotNull(storage.loadSingleton(DRAFT, draftSerializer)) { "Studio history requires its persisted draft" }
                journal.resolved(snapshot(actual))
                Outcome.Proved(actual)
            }
        } catch (failure: Exception) { Outcome.Unproved(failure) }
    }

    companion object {
        const val ENTITY = "desktop_studio_edit_history"
        private const val DRAFT = "novelai_studio_draft"
    }
}
