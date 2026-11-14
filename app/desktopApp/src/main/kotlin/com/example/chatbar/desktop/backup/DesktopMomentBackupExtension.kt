package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.desktop.DesktopMomentImageVersions
import kotlinx.serialization.json.*

/** Keep the pinned base snapshot strict while accepting the portable candidate field and legacy alias. */
internal object DesktopMomentBackupExtension {
    const val FIELD = "imageVersions"
    const val LEGACY_FIELD = "desktopImageVersions"
    fun hasVersions(value: JsonObject) = FIELD in value || LEGACY_FIELD in value
    fun rawVersions(value: JsonObject): JsonElement? {
        require(!(FIELD in value && LEGACY_FIELD in value)) { "AMBIGUOUS_MOMENT_VERSION_FIELDS" }
        return value[FIELD] ?: value[LEGACY_FIELD]
    }
    fun canonical(value: JsonObject): JsonObject {
        val raw = rawVersions(value) ?: return value
        require(raw is JsonArray)
        return JsonObject((value - LEGACY_FIELD) + (FIELD to raw))
    }
    fun snapshot(value: JsonObject): MomentPostSnapshot {
        val canonical = canonical(value)
        val official = JsonObject(canonical - FIELD)
        val snapshot = backupJson.decodeFromJsonElement(MomentPostSnapshot.serializer(), official)
        if (FIELD in canonical) {
            val desktop = backupJson.decodeFromJsonElement(MomentPost.serializer(), canonical)
            DesktopMomentImageVersions.retained(desktop)
        }
        return snapshot
    }
    fun liveRecord(value: JsonObject): JsonObject {
        snapshot(value)
        val canonical = canonical(value)
        return if (FIELD in canonical) canonical else JsonObject(canonical + (FIELD to JsonArray(emptyList())))
    }
    fun versions(value: JsonObject) = (rawVersions(value) as? JsonArray)?.map {
        backupJson.decodeFromJsonElement(com.example.chatbar.data.local.entity.MomentImageVersion.serializer(), it)
    }.orEmpty()
    fun references(value: JsonObject): List<Pair<String, String?>> {
        val post = snapshot(value)
        return listOf("senderAvatar" to post.senderAvatar, "imagePath" to post.imagePath,
            "generatedImageMetadata.imagePath" to post.generatedImageMetadata?.imagePath) +
            versions(value).flatMapIndexed { index, version ->
                val field = if (LEGACY_FIELD in value) LEGACY_FIELD else FIELD
                listOf("$field.$index.imagePath" to version.imagePath,
                    "$field.$index.generatedImageMetadata.imagePath" to version.generatedImageMetadata?.imagePath) }
    }
}
