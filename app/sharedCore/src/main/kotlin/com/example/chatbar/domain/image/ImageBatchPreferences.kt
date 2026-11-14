package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable

/** Saved future-request preferences, never running work or payment approval. */
@Serializable
data class ImageBatchPreferences(
    val enabled: Boolean = false,
    val imagesPerBatch: Int = 1,
    val numberOfBatches: Int = 1,
    val pacing: ImageBatchPacing = ImageBatchPacing(),
    val allowAnlasSpending: Boolean = false
) {
    fun configuration(useSavedBatch: Boolean = enabled) = ImageBatchConfiguration(
        requestCount = if (useSavedBatch) imagesPerBatch else 1,
        numberOfBatches = if (useSavedBatch) numberOfBatches else 1,
        pacing = pacing,
        allowAnlasSpending = allowAnlasSpending
    )
    fun validationError(): String? = configuration(useSavedBatch = true).validationError()
}
