package com.example.chatbar.desktop.backup

import kotlinx.serialization.json.*

/** References only the immutable PC unit replaced by an explicit TAKE_PHONE choice. */
internal class DesktopMergeRetainedMedia(
    private val scope: InspectionScope?,
    computer: DesktopMergePreview.Unit?,
    private val checkActive: () -> Unit,
) {
    class SessionRecords(val records: Map<String, JsonObject>, val voiceSources: List<DesktopMergeVoiceSourcePolicy.Source>)
    private val records = computer?.records.orEmpty()
    private val paths = buildMap {
        records.keys.forEach { path ->
            checkActive()
            val type = DesktopBackupPolicy.entityType(path)
            if (type in setOf("chat_messages", "moment_posts", "novelai_generation_history", "desktop_image_post_process_results")) {
                val value = records.getValue(path)
                key(type, value)?.let { check(put(it, path) == null) { "MERGE_RETAINED_ID_COLLISION" } }
            }
        }
    }

    fun retainedSessionRecords(incoming: DesktopMergePreview.Unit): SessionRecords {
        val selected = scope ?: return SessionRecords(emptyMap(), emptyList())
        val sessions = incoming.records.keys.filter { DesktopBackupPolicy.entityType(it) == "chat_sessions" }
            .mapNotNull { path -> checkActive(); incoming.records.getValue(path)["id"]?.jsonPrimitive?.contentOrNull }.toSet()
        if (sessions.isEmpty()) return SessionRecords(emptyMap(), emptyList())
        val incomingMessages = incoming.records.keys.filter { DesktopBackupPolicy.entityType(it) == "chat_messages" }
            .mapNotNull { path -> checkActive(); key("chat_messages", incoming.records.getValue(path)) }.toSet()
        fun incomingFamily(types: Set<String>): Set<String> = incoming.records.keys.filter { DesktopBackupPolicy.entityType(it) in types }
            .mapNotNull { path -> checkActive(); incoming.records.getValue(path)["sessionId"]?.jsonPrimitive?.contentOrNull }.toSet()
        val archiveSessions = incomingFamily(setOf("save_slots"))
        val voiceSessions = incomingFamily(setOf("generated_voice_messages", "voice_anchor_states"))
        val retained = paths.filter { (identity, path) ->
            checkActive()
            if (identity.first() != "chat_messages" || identity in incomingMessages) false else {
                val value = records.getValue(path)
                val session = value["sessionId"]?.jsonPrimitive?.contentOrNull
                val imagesSelected = (selected.sessionChoices[session] ?: selected.sessionDefaultChoice)?.images
                    ?: (selected.sessionsWithImages == selected.sessions)
                session in sessions && !imagesSelected && (value["images"] as? JsonArray)?.isNotEmpty() == true
            }
        }.values.toMutableSet()
        records.keys.forEach { path ->
            checkActive()
            if (DesktopBackupPolicy.entityType(path) == "save_slots") {
                val value = records.getValue(path)
                val session = value["sessionId"]?.jsonPrimitive?.contentOrNull
                val archivesSelected = (selected.sessionChoices[session] ?: selected.sessionDefaultChoice)?.archives
                    ?: (session in archiveSessions)
                if (session in sessions && !archivesSelected) retained += path
            }
        }
        fun ownerRecords(source: Map<String, JsonObject>) = DesktopMergeRecord.from(source).filter { it.type == "chat_messages" }
            .associateBy { record -> checkActive(); val value = record.value
                value["sessionId"]?.jsonPrimitive?.contentOrNull to value["id"]?.jsonPrimitive?.contentOrNull }
        val owners = ownerRecords(records)
        val incomingOwners = ownerRecords(incoming.records)
        val voiceSources = mutableListOf<DesktopMergeVoiceSourcePolicy.Source>()
        for (record in DesktopMergeRecord.from(records).filter { it.type in setOf("generated_voice_messages", "voice_anchor_states") }) {
            checkActive()
            val session = record.text("sessionId")
            val voiceSelected = (selected.sessionChoices[session] ?: selected.sessionDefaultChoice)?.voice
                ?: (session in voiceSessions)
            if (session !in sessions || voiceSelected) continue
            val owner = requireNotNull(owners[session to record.text("messageId")]) { "MERGE_RETAINED_VOICE_OWNER_MISSING" }
            val incomingOwner = incomingOwners[session to record.text("messageId")]
            val target = incomingOwner ?: owner.also { retained += it.relative }
            if (DesktopMergeVoiceSourcePolicy.canRetainLive(record.type, record.value, owner.value, target.value)) retained += record.relative
            else voiceSources += DesktopMergeVoiceSourcePolicy.Source(record, owner, target,
                if (incomingOwner == null) DesktopMergeDestinationPlan.Side.COMPUTER else DesktopMergeDestinationPlan.Side.PHONE)
        }
        return SessionRecords(DesktopBackupRecords.view(retained) { records.getValue(it) }, voiceSources)
    }

    fun fields(record: DesktopMergeRecord): JsonObject? {
        val selected = scope ?: return null
        val incoming = record.value
        val identity = key(record.type, incoming) ?: return null
        val old = paths[identity]?.let(records::getValue) ?: return null
        val fields: Map<String, JsonElement> = when (record.type) {
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
            "desktop_image_post_process_results" -> {
                if (selected.studioHistoryImages) return null
                val image = old["imagePath"]?.jsonPrimitive?.contentOrNull ?: return null
                if (image.isBlank() || image.startsWith("chatbar-save-slot-omitted-image:")) return null
                mapOf("imagePath" to JsonPrimitive(image))
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
            "desktop_image_post_process_results" -> listOf(type, id,
                value["sourceSha256"]?.jsonPrimitive?.contentOrNull, value["imageSha256"]?.jsonPrimitive?.contentOrNull,
                value["operation"]?.jsonPrimitive?.contentOrNull)
            else -> null
        }
    }
}
