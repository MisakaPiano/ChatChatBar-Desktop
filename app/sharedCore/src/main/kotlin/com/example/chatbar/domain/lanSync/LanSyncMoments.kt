package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.MomentPost
import kotlinx.serialization.json.decodeFromJsonElement

/** Front-end history has its own graph; a missing historical chat/card is not a chat orphan. */
internal object LanSyncMoments {
    val types = setOf("moment_posts", "moment_tasks")

    fun units(records: List<LanSyncRecord>): Map<String, List<LanSyncRecord>> {
        // A task remains the anchor when PENDING gains its reserved/final post. Historical
        // duplicate task links share that component rather than duplicating the post record.
        val linked = records.filter { it.type == "moment_tasks" && !it.text("postId").isNullOrBlank() }
            .groupBy { requireNotNull(it.text("postId")) }
        val owner = linked.mapValues { (_, tasks) -> tasks.minOf { it.id } }
        return records.groupBy { record -> when (record.type) {
            "moment_tasks" -> "moment-task/${owner[record.text("postId")] ?: record.id}"
            "moment_posts" -> owner[record.id]?.let { "moment-task/$it" } ?: "moment-post/${record.id}"
            else -> record.unit
        } }
    }

    fun validate(record: LanSyncRecord) {
        require(!record.text("characterCardId").isNullOrBlank() && !record.text("sessionId").isNullOrBlank()) { "SYNC_MOMENT_BINDING" }
        if (record.type != "moment_posts") return
        val post = lanSyncJson.decodeFromJsonElement<MomentPost>(record.body)
        val versions = post.desktopImageVersions
        require(versions.size <= 4096 && versions.map { it.id }.distinct().size == versions.size &&
            versions.map { it.imagePath }.distinct().size == versions.size) { "SYNC_MOMENT_VERSIONS" }
        versions.forEach { version ->
            require(version.id.isNotBlank() && version.id.length <= 128 && version.imagePath.isNotBlank() &&
                version.imagePath.length <= 1024 && version.generatedImageMetadata?.let { it.imagePath == version.imagePath } != false) { "SYNC_MOMENT_VERSIONS" }
        }
        val selected = post.imagePath?.takeIf { it.isNotBlank() } ?: return
        val match = versions.firstOrNull { it.imagePath == selected }
        if (match == null) require(versions.size < 4096) { "SYNC_MOMENT_VERSIONS" }
        else require(match.imagePrompt == post.imagePrompt && match.generatedImageMetadata == post.generatedImageMetadata &&
            match.imageBrief == post.imageBrief) { "SYNC_MOMENT_VERSIONS" }
    }
}
