package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.desktop.DesktopMomentImageVersions
import kotlinx.serialization.json.*

/** Keep the pinned official snapshot strict; validate the explicitly named Desktop extension separately. */
internal object DesktopMomentBackupExtension {
    const val FIELD = "desktopImageVersions"
    const val TASK_APPROVAL_FIELD = "requiresLocalScheduleApproval"
    fun taskSnapshot(value: JsonObject): MomentTaskSnapshot {
        if (TASK_APPROVAL_FIELD in value) {
            val marker = value[TASK_APPROVAL_FIELD] as? JsonPrimitive
            require(marker != null && !marker.isString && marker.booleanOrNull != null) { "MOMENT_TASK_APPROVAL_TYPE" }
        }
        if (TASK_APPROVAL_FIELD in value) backupJson.decodeFromJsonElement(MomentTask.serializer(), value)
        return backupJson.decodeFromJsonElement(MomentTaskSnapshot.serializer(), JsonObject(value - TASK_APPROVAL_FIELD))
    }
    fun snapshot(value: JsonObject): MomentPostSnapshot {
        val official = JsonObject(value - FIELD)
        val snapshot = backupJson.decodeFromJsonElement(MomentPostSnapshot.serializer(), official)
        if (FIELD in value) {
            val desktop = backupJson.decodeFromJsonElement(MomentPost.serializer(), value)
            DesktopMomentImageVersions.retained(desktop)
        }
        return snapshot
    }
    fun liveRecord(value: JsonObject): JsonObject {
        snapshot(value)
        return if (FIELD in value) value else JsonObject(value + (FIELD to JsonArray(emptyList())))
    }
    fun versions(value: JsonObject) = (value[FIELD] as? JsonArray)?.map {
        backupJson.decodeFromJsonElement(com.example.chatbar.data.local.entity.MomentImageVersion.serializer(), it)
    }.orEmpty()
    fun references(value: JsonObject): List<Pair<String, String?>> {
        val post = snapshot(value)
        return listOf("senderAvatar" to post.senderAvatar, "imagePath" to post.imagePath,
            "generatedImageMetadata.imagePath" to post.generatedImageMetadata?.imagePath) +
            versions(value).flatMapIndexed { index, version -> listOf("$FIELD.$index.imagePath" to version.imagePath,
                "$FIELD.$index.generatedImageMetadata.imagePath" to version.generatedImageMetadata?.imagePath) }
    }
}
