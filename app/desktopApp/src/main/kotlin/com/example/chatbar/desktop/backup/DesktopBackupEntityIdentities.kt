package com.example.chatbar.desktop.backup

import kotlinx.serialization.json.*

/** One archive/capture only; logical IDs are independent of physical storage filenames. */
internal class DesktopBackupEntityIdentities(private val checkActive: () -> Unit = {}) {
    private val seen = hashSetOf<Pair<String, String>>()

    fun observe(path: String, value: JsonObject) {
        val type = DesktopBackupPolicy.entityType(path)
        if (type !in DesktopBackupPolicy.schemas) return // Read-only unknown-store reporting is unchanged.
        if (type == "save_slots") validateInlineSlot(value)
        // Match the official stream's depth-one string `id` observer. Nested people and
        // singleton records without an id keep their own typed/schema validation.
        val id = (value["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        require(id.isNotBlank() && seen.add(type to id)) {
            "BACKUP_ENTITY_ID_INVALID_OR_DUPLICATE"
        }
    }

    private fun validateInlineSlot(slot: JsonObject) {
        // Each slot is an independent historical snapshot, so IDs may recur in other slots.
        for (collection in listOf("messages", "vectorChunks", "voiceMessages")) {
            val ids = hashSetOf<String>()
            for (entry in (slot[collection] as? JsonArray).orEmpty()) {
                val record = entry.jsonObject
                val id = record.getValue("id").jsonPrimitive.content
                require(id.isNotBlank() && ids.add(id)) { "BACKUP_INLINE_RECORD_ID_INVALID_OR_DUPLICATE" }
                if (collection == "messages") require(record.getValue("sessionId").jsonPrimitive.content.isNotBlank()) {
                    "BACKUP_INLINE_MESSAGE_SESSION_INVALID"
                }
            }
        }
        for (collection in listOf("imageResources", "audioResources")) {
            for (entry in (slot[collection] as? JsonObject).orEmpty().values) {
                require(entry.jsonObject.getValue("fileName").jsonPrimitive.content.isNotBlank()) {
                    "BACKUP_INLINE_RESOURCE_FILENAME_INVALID"
                }
            }
        }
        // Reuse the pinned stream's Base64 syntax/padding/length checks. Its validation
        // skeleton is discarded; neither the original JSON nor resource bytes are rewritten.
        if (listOf("imageResources", "audioResources").any { (slot[it] as? JsonObject)?.isNotEmpty() == true }) {
            BackupJsonStream(slot.toString().reader(), java.io.Writer.nullWriter(), validationSkeleton = true,
                checkActive = checkActive) { path, _ -> path }.copy()
        }
    }
}
