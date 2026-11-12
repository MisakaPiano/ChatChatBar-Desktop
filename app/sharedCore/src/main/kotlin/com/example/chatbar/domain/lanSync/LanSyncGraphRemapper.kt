package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import kotlinx.serialization.json.*

/** Pure mapping of authenticated credential-free records; file publication is deliberately separate. */
internal class LanSyncGraphRemapper(
    records: Collection<LanSyncRecord>,
    private val identity: LanSyncIdentity,
    private val checkActive: () -> Unit = {},
) {
    // Live memory and each historical archive are independent proof graphs.
    private val proofs = LanSyncMemoryProofs(records.filter { it.type != "save_slots" }, identity, checkActive)

    fun rewrite(record: LanSyncRecord, mapResource: (String, List<String>) -> String = { value, _ -> value }): JsonObject {
        val originalValue = record.value
        val scopedProofs = if (record.type == "save_slots")
            LanSyncMemoryProofs(listOf(record.copy(body = originalValue)), identity, checkActive) else proofs
        fun walk(value: JsonElement, path: List<String>): JsonElement {
            checkActive()
            require(path.size <= 128) { "MERGE_JSON_DEPTH" }
            // User extension objects are opaque, even when a key happens to look like an ID.
            if (identity.opaque(path)) return value
            return when (value) {
                is JsonObject -> {
                    val message = path.isEmpty() && record.type == "chat_messages" || path.lastOrNull() == "messages" && record.type == "save_slots"
                    val original = if (message && identity.value("chat_messages", value.getValue("id").jsonPrimitive.content) != value.getValue("id").jsonPrimitive.content) {
                        // Legacy IDs are derived from the OLD message ID. Pin exactly those identities
                        // before forking; deriving them after the ID change would orphan old voices.
                        val normalized = MessageAlternativeVersionPolicy.normalize(lanSyncJson.decodeFromJsonElement(ChatMessage.serializer(), value))
                        val versions = mapOf(
                            "alternativeVersionIds" to JsonArray(normalized.alternativeVersionIds.map(::JsonPrimitive)),
                            "currentAlternativeVersionId" to (normalized.currentAlternativeVersionId?.let(::JsonPrimitive) ?: JsonNull))
                        val modes = if ("narrativeMode" in value || "alternativeNarrativeModes" in value) mapOf(
                            "narrativeMode" to (normalized.narrativeMode?.name?.let(::JsonPrimitive) ?: JsonNull),
                            "alternativeNarrativeModes" to JsonArray(normalized.alternativeNarrativeModes.map { it?.name?.let(::JsonPrimitive) ?: JsonNull }))
                            else emptyMap()
                        JsonObject(value + versions + modes)
                    } else value
                    val result = linkedMapOf<String, JsonElement>()
                    original.forEach { (key, child) ->
                        val mappedKey = identity.key(key, path)
                        require(mappedKey !in result) { "MERGE_OBJECT_KEY_COLLISION" }
                        result[mappedKey] = walk(child, path + key)
                    }
                    val mapped = JsonObject(result)
                    if (LanSyncMemoryProofs.isNode(record, path)) scopedProofs.rewrite(value, mapped) else mapped
                }
                is JsonArray -> JsonArray(value.map { walk(it, path) })
                is JsonPrimitive -> if (value.isString) JsonPrimitive(identity.string(record, value.content, path)) else value
            }
        }
        // Resource recognition remains the existing official stream policy; arbitrary text is untouched.
        val rewritten = walk(originalValue, emptyList()).jsonObject
        return rewritten
    }
}
