package com.example.chatbar.desktop.backup

import kotlinx.serialization.json.*

/** Already verified credential-free input; this metadata view never creates files or activation proof. */
internal class DesktopMergeRecord private constructor(val relative: String, private val load: () -> JsonObject) {
    constructor(relative: String, value: JsonObject) : this(relative, { value })
    val value: JsonObject get() = load()
    companion object {
        fun from(records: Map<String, JsonObject>): List<DesktopMergeRecord> =
            records.keys.map { path -> DesktopMergeRecord(path) { records.getValue(path) } }
    }
    val type = DesktopBackupPolicy.entityType(relative)
    val singleton = relative.count { it == '/' } == 1
    fun text(field: String): String? {
        var current: JsonElement? = value
        field.split('.').forEach { current = (current as? JsonObject)?.get(it) }
        val element = current ?: return null
        if (element == JsonNull) return null
        require(element is JsonPrimitive && element.isString) { "MERGE_REFERENCE_FIELD_TYPE" }
        return element.content
    }
    override fun toString() = "DesktopMergeRecord($type)"
}
