package com.example.chatbar.domain.lanSync

import java.nio.file.Path

data class LanSyncImageReference(val reference: String, val field: String, val hash: String)

data class LanSyncImagePreview(val id: String, val record: LanSyncRecord, val reference: String,
    val hash: String, val path: Path, val postProcessOutput: Boolean)

object LanSyncReviewImages {
    fun references(record: LanSyncRecord): List<LanSyncImageReference> = buildList {
        LanSyncWorkspace.mapResources(record.body) { reference, field ->
            if (reference.startsWith("ccb-lan-v1/images/")) {
                val parts = reference.split('/')
                require(parts.size == 5 && parts[2].matches(Regex("[a-f0-9-]{36}")) &&
                    parts[3].matches(Regex("[a-f0-9]{64}")) && parts[4].matches(Regex("[a-z0-9]{1,8}"))) { "SYNC_IMAGE_REFERENCE" }
                add(LanSyncImageReference(reference, field, parts[3]))
            }
            reference
        }
    }
}
