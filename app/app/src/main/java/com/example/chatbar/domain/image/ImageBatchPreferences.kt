package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable

/** Saved preferences describe future requests, never a running task or payment confirmation. */
@Serializable
data class ImageBatchPreferences(
    val enabled: Boolean = false,
    val imagesPerBatch: Int = 1,
    val numberOfBatches: Int = 1,
    val pacing: ImageBatchPacing = ImageBatchPacing(),
    val allowAnlasSpending: Boolean = false
) {
    fun configuration(useSavedBatch: Boolean = enabled): ImageBatchConfiguration = ImageBatchConfiguration(
        requestCount = if (useSavedBatch) imagesPerBatch else 1,
        continuous = useSavedBatch && numberOfBatches > 1,
        target = if (useSavedBatch) imagesPerBatch * numberOfBatches else 1,
        pacing = pacing,
        budget = if (allowAnlasSpending) 100000 else 0,
        numberOfBatches = if (useSavedBatch) numberOfBatches else 1,
        allowAnlasSpending = allowAnlasSpending
    )

    fun validationError(): String? = configuration().validationError()
}

/** This is an estimate label, not a server receipt or a user-entered budget. */
fun imageGenerationCostLabel(prefix: String, estimatedAnlas: Long?): String = when {
    estimatedAnlas == null -> "$prefix · 费用未知"
    estimatedAnlas == 0L -> "$prefix · 估算免 Anlas"
    else -> "$prefix · 预计 $estimatedAnlas Anlas"
}

fun estimatedPaymentError(cost: Long?, allowAnlasSpending: Boolean): String? = when {
    cost == null || cost < 0 -> "费用未知，已停止；请检查账号状态"
    cost > 0 && !allowAnlasSpending -> "预计消耗 $cost Anlas；“允许消耗 Anlas”已关闭，未发起生成"
    else -> null
}
