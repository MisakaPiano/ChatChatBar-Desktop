package com.example.chatbar.desktop.backup

import kotlinx.serialization.json.*

/** Pure mapping of authenticated credential-free records; file publication is deliberately separate. */
internal class DesktopMergeGraphRemapper(
    records: Collection<DesktopMergeRecord>,
    private val identity: DesktopMergeIdentity,
    private val checkActive: () -> Unit = {},
) {
    private val proofs = DesktopMergeMemoryProofs(records, identity)

    fun rewrite(record: DesktopMergeRecord, mapResource: (String, List<String>) -> String = { value, _ -> value }): JsonObject {
        fun walk(value: JsonElement, path: List<String>): JsonElement {
            checkActive()
            require(path.size <= 128) { "MERGE_JSON_DEPTH" }
            // User extension objects are opaque, even when a key happens to look like an ID.
            if (identity.opaque(path)) return value
            return when (value) {
                is JsonObject -> {
                    val result = linkedMapOf<String, JsonElement>()
                    value.forEach { (key, child) ->
                        val mappedKey = identity.key(key, path)
                        require(mappedKey !in result) { "MERGE_OBJECT_KEY_COLLISION" }
                        result[mappedKey] = walk(child, path + key)
                    }
                    val mapped = JsonObject(result)
                    if (DesktopMergeMemoryProofs.isNode(record, path)) proofs.rewrite(value, mapped) else mapped
                }
                is JsonArray -> JsonArray(value.map { walk(it, path) })
                is JsonPrimitive -> if (value.isString) JsonPrimitive(identity.string(record, value.content, path)) else value
            }
        }
        // Resource recognition remains the existing official stream policy; arbitrary text is untouched.
        val rewritten = walk(record.value, emptyList()).jsonObject
        return backupJson.parseToJsonElement(DesktopBackupPolicy.mapResources(rewritten, mapResource)
            .toString(Charsets.UTF_8)).jsonObject
    }
}
