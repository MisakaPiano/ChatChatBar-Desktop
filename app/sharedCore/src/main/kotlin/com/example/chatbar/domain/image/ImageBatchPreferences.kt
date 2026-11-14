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

/** Old total-target drafts are projected into a bounded exact-batch review, never auto-submitted. */
fun NovelAiStudioDraft.batchConfiguration(): ImageBatchConfiguration {
    val count = activeSettings.count.coerceIn(1, 4)
    val batches = if (continuousModeEnabled && batchCount == 1)
        ((continuousTargetCount.coerceAtLeast(1).toLong() + count - 1) / count).coerceIn(1, (100 / count).toLong()).toInt()
        else batchCount
    return ImageBatchConfiguration(activeSettings.count, batches, batchPacing, allowBatchAnlasSpending)
}
