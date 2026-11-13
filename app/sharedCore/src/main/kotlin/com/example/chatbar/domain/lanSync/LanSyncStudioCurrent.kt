package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.image.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/** One choice owns the current editor and every durable undo/checkpoint associated with it. */
internal object LanSyncStudioCurrent {
    const val DRAFT = "novelai_studio_draft"
    const val UNDO = "novelai_studio_history_undo"
    const val CHECKPOINT = "novelai_studio_guidance_checkpoint"
    const val HISTORY = "desktop_studio_edit_history"
    const val UNIT = "studio-current/singleton"
    val types = setOf(DRAFT, UNDO, CHECKPOINT, HISTORY)

    /** Revisions drive local editor acknowledgements; AI account/model IDs are device-owned. */
    fun normalize(type: String, body: JsonObject): JsonObject {
        fun <T> canonical(serializer: KSerializer<T>) = lanSyncJson.encodeToJsonElement(serializer,
            lanSyncJson.decodeFromJsonElement(serializer, body)).jsonObject
        val full = when (type) {
            DRAFT -> canonical(NovelAiStudioDraft.serializer())
            UNDO -> canonical(NovelAiStudioUndoDraft.serializer())
            CHECKPOINT -> canonical(NovelAiGuidanceEditorCheckpoint.serializer())
            HISTORY -> canonical(NovelAiStudioHistoryJournal.serializer())
            else -> body
        }
        fun draft(value: JsonObject) = JsonObject(value + mapOf(
            "aiDesignModelId" to JsonNull, "contentRevision" to JsonPrimitive(0L),
            "promptContentRevision" to JsonPrimitive(0L), "updatedAt" to JsonPrimitive(0L)))
        fun timeline(value: JsonElement): JsonElement {
            if (value !is JsonObject) return value
            val entries = value["entries"] as? JsonArray ?: return value
            return JsonObject(value + ("entries" to JsonArray(entries.map { draft(it.jsonObject) })))
        }
        return when (type) {
            DRAFT -> draft(full)
            UNDO -> JsonObject(full.mapValues { (key, value) -> if (key == "draft" && value is JsonObject) draft(value) else value })
            HISTORY -> JsonObject(full.mapValues { (key, value) -> if (key in setOf("committed", "pending")) timeline(value) else value })
            else -> full
        }
    }

    fun restoreLocal(type: String, body: JsonObject, localDraft: JsonObject?): JsonObject {
        val localModel = localDraft?.get("aiDesignModelId") ?: JsonNull
        fun visit(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, child) ->
                if (key == "aiDesignModelId") localModel else visit(child)
            })
            is JsonArray -> JsonArray(value.map(::visit))
            else -> value
        }
        return if (type in types) visit(body).jsonObject else body
    }

    fun validateGraph(records: List<LanSyncRecord>, requireWire: Boolean = true) {
        val group = records.filter { it.type in types }.associateBy { it.type }
        if (group.isEmpty()) return
        val current = requireNotNull(group[DRAFT]) { "SYNC_STUDIO_DRAFT_MISSING" }
        if (requireWire) group.values.forEach { require(it.body == normalize(it.type, it.body)) { "SYNC_STUDIO_LOCAL_FIELDS_ON_WIRE" } }
        group[HISTORY]?.let {
            val journal = lanSyncJson.decodeFromJsonElement(NovelAiStudioHistoryJournal.serializer(), it.body)
            require(journal.pending == null) { "SYNC_STUDIO_PENDING_ON_WIRE" }
            journal.committed.validate()
            // Compare normalized resource aliases as well as every actual draft field.
            val entry = requireNotNull(journal.committed.current())
            val body = lanSyncJson.encodeToJsonElement(NovelAiStudioDraft.serializer(), entry).jsonObject
            require(current.copy(body = normalize(DRAFT, current.body)).comparisonBody() ==
                current.copy(body = normalize(DRAFT, body)).comparisonBody()) { "SYNC_STUDIO_HISTORY_MISMATCH" }
        }
    }
}
