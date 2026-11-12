package com.example.chatbar.domain.image

import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import java.util.UUID
import kotlinx.serialization.Serializable

/** Durable result metadata; sourceSha256 identifies the input without claiming ownership of its file. */
@Serializable
data class NovelAiPostProcessResultRecord(
    val id: String = UUID.randomUUID().toString(),
    val sourceSha256: String,
    val operation: String,
    val scale: String,
    val strength: Float,
    val noise: Float,
    val imagePath: String,
    val imageSha256: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun validateMetadata() {
        val sha = Regex("[a-f0-9]{64}")
        require(id.isNotBlank() && sourceSha256.matches(sha) && imageSha256.matches(sha) && createdAt >= 0)
        require(NovelAiPostProcessTab.entries.any { it.name == operation } && NovelAiEnhanceScale.entries.any { it.name == scale })
        require(strength.isFinite() && strength in 0f..1f && noise.isFinite() && noise in 0f..1f)
        require(imagePath.isNotBlank())
    }

    /** Existing Desktop/backup path contract; LAN materialization has a platform-specific path. */
    fun validateForImport() {
        validateMetadata()
        val path = imagePath.removePrefix("chatbar-backup-file:")
        require(if (path.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) path.removePrefix(OMITTED_SAVE_SLOT_IMAGE_PREFIX).matches(Regex("[a-f0-9]{64}"))
            else path.startsWith("images/") && path.count { it == '/' } == 1 && !path.contains('\\') && !path.substringAfter('/').let { it.isBlank() || it in setOf(".", "..") })
    }

    fun matches(tab: NovelAiPostProcessTab, options: NovelAiEnhanceOptions) = operation == tab.name &&
        (tab == NovelAiPostProcessTab.UPSCALE || scale == options.scale.name && strength == options.strength && noise == options.noise)

    companion object { const val MAX_IMAGE_BYTES = 100 * 1024 * 1024 }
}
