package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import com.example.chatbar.domain.image.NovelAiPostProcessResultRecord
import kotlinx.serialization.json.decodeFromJsonElement

object LanSyncPostProcess {
    const val TYPE = "desktop_image_post_process_results"

    fun record(record: LanSyncRecord): NovelAiPostProcessResultRecord? = if (record.type == TYPE)
        lanSyncJson.decodeFromJsonElement<NovelAiPostProcessResultRecord>(record.body) else null

    internal fun validate(record: LanSyncRecord) {
        val result = record(record) ?: return
        result.validateMetadata()
        val path = result.imagePath
        if (path.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX))
            require(path.removePrefix(OMITTED_SAVE_SLOT_IMAGE_PREFIX).matches(Regex("[a-f0-9]{64}"))) { "SYNC_POSTPROCESS_IMAGE" }
        else if (path.startsWith("ccb-lan-v1/")) {
            val parts = path.split('/')
            require(parts.size == 5 && parts[1] == "images" && parts[3] == result.imageSha256) { "SYNC_POSTPROCESS_IMAGE" }
        }
    }

    internal fun validateImageHash(record: LanSyncRecord, field: String, hash: String) {
        if (field == "imagePath") record(record)?.let {
            require(it.imageSha256 == hash) { "SYNC_POSTPROCESS_IMAGE" }
        }
    }
}
