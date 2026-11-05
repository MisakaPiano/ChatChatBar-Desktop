package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import kotlinx.serialization.json.*

/** Pure mapping of authenticated credential-free records; file publication is deliberately separate. */
internal class DesktopMergeGraphRemapper(
    records: Collection<DesktopMergeRecord>,
    private val identity: DesktopMergeIdentity,
    private val checkActive: () -> Unit = {},
) {
    // Live memory and each historical archive are independent proof graphs.
    private val proofs = DesktopMergeMemoryProofs(records.filter { it.type != "save_slots" }, identity, checkActive)

    fun rewrite(record: DesktopMergeRecord, mapResource: (String, List<String>) -> String = { value, _ -> value }): JsonObject {
        val originalValue = record.value
        val scopedProofs = if (record.type == "save_slots")
            DesktopMergeMemoryProofs(listOf(DesktopMergeRecord(record.relative, originalValue)), identity, checkActive) else proofs
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
                        val normalized = MessageAlternativeVersionPolicy.normalize(backupJson.decodeFromJsonElement(ChatMessage.serializer(), value))
                        JsonObject(value + mapOf(
                            "alternativeVersionIds" to JsonArray(normalized.alternativeVersionIds.map(::JsonPrimitive)),
                            "currentAlternativeVersionId" to (normalized.currentAlternativeVersionId?.let(::JsonPrimitive) ?: JsonNull)))
                    } else value
                    val result = linkedMapOf<String, JsonElement>()
                    original.forEach { (key, child) ->
                        val mappedKey = identity.key(key, path)
                        require(mappedKey !in result) { "MERGE_OBJECT_KEY_COLLISION" }
                        result[mappedKey] = walk(child, path + key)
                    }
                    val mapped = JsonObject(result)
                    if (DesktopMergeMemoryProofs.isNode(record, path)) scopedProofs.rewrite(value, mapped) else mapped
                }
                is JsonArray -> JsonArray(value.map { walk(it, path) })
                is JsonPrimitive -> if (value.isString) JsonPrimitive(identity.string(record, value.content, path)) else value
            }
        }
        // Resource recognition remains the existing official stream policy; arbitrary text is untouched.
        val rewritten = walk(originalValue, emptyList()).jsonObject
        return backupJson.parseToJsonElement(DesktopBackupPolicy.mapResources(rewritten, mapResource)
            .toString(Charsets.UTF_8)).jsonObject
    }
}
