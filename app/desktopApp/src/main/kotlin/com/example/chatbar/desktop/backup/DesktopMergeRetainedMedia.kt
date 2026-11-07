package com.example.chatbar.desktop.backup

import kotlinx.serialization.json.*

/** References only the immutable PC unit replaced by an explicit TAKE_PHONE choice. */
internal class DesktopMergeRetainedMedia(
    private val scope: InspectionScope?,
    computer: DesktopMergePreview.Unit?,
    checkActive: () -> Unit,
) {
    private val records = computer?.records.orEmpty()
    private val paths = buildMap {
        records.keys.forEach { path ->
            checkActive()
            val type = DesktopBackupPolicy.entityType(path)
            if (type in setOf("chat_messages", "moment_posts", "novelai_generation_history")) {
                val value = records.getValue(path)
                key(type, value)?.let { check(put(it, path) == null) { "MERGE_RETAINED_ID_COLLISION" } }
            }
        }
    }

    fun fields(record: DesktopMergeRecord): JsonObject? {
        val selected = scope ?: return null
        val incoming = record.value
        val identity = key(record.type, incoming) ?: return null
        val old = paths[identity]?.let(records::getValue) ?: return null
        val fields = when (record.type) {
            "chat_messages" -> {
                val session = incoming["sessionId"]?.jsonPrimitive?.contentOrNull ?: return null
                val images = (selected.sessionChoices[session] ?: selected.sessionDefaultChoice)?.images
                    ?: (selected.sessionsWithImages == selected.sessions || selected.sessionsWithImages > 0 &&
                        (incoming["images"] as? JsonArray)?.isNotEmpty() == true)
                if (images) return null
                mapOf("images" to (old["images"] ?: JsonArray(emptyList())),
                    "generatedImageMetadata" to (old["generatedImageMetadata"] ?: JsonArray(emptyList())))
            }
            "moment_posts" -> {
                if (selected.momentsImages) return null
                buildMap {
                    put("imagePath", old["imagePath"] ?: JsonNull)
                    put("generatedImageMetadata", old["generatedImageMetadata"] ?: JsonNull)
                    // Versions are one media owner; retaining only the selected image loses its alternatives.
                    put(DesktopMomentBackupExtension.FIELD, old[DesktopMomentBackupExtension.FIELD] ?: JsonArray(emptyList()))
                }
            }
            "novelai_generation_history" -> {
                if (selected.studioHistoryImages) return null
                val images = old["images"] as? JsonArray ?: return null
                if (images.none { (it as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull?.let { path ->
                        path.isNotBlank() && !path.startsWith("chatbar-save-slot-omitted-image:") } == true }) return null
                mapOf("images" to images)
            }
            else -> return null
        }
        return JsonObject(fields)
    }

    private fun key(type: String, value: JsonObject): List<String?>? {
        val id = value["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (type) {
            "chat_messages" -> listOf(type, id, value["sessionId"]?.jsonPrimitive?.contentOrNull)
            "moment_posts" -> listOf(type, id, value["sessionId"]?.jsonPrimitive?.contentOrNull,
                value["characterCardId"]?.jsonPrimitive?.contentOrNull)
            "novelai_generation_history" -> listOf(type, id)
            else -> null
        }
    }
}
