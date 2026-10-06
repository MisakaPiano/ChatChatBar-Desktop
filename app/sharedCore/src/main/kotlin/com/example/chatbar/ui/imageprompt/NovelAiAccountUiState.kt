package com.example.chatbar.ui.imageprompt

import com.example.chatbar.domain.image.NovelAiAccountUsage

data class NovelAiAccountUiState(
    val usage: NovelAiAccountUsage? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val localAnlasSpent: Long = 0L,
    val anlasBaseline: Long? = null,
    val localV5AllowanceSpent: Int = 0,
    val allowanceBaselineImages: Int? = null
) {
    val displayAnlas: Long?
        get() = usage?.anlas?.minus(localAnlasSpent)?.coerceAtLeast(0L)

    val approximateV5Images: Int?
        get() = usage?.approximateV5Images?.let { (it - localV5AllowanceSpent).coerceAtLeast(0) }

    val effectiveUsage: NovelAiAccountUsage?
        get() = usage?.copy(
            anlas = displayAnlas ?: usage.anlas,
            v5AllowanceExhausted = usage.v5AllowanceExhausted || approximateV5Images == 0
        )

    fun recordAnlasGeneration(cost: Long): NovelAiAccountUiState {
        val current = usage?.anlas ?: return this
        return copy(
            localAnlasSpent = localAnlasSpent + cost.coerceAtLeast(0L),
            anlasBaseline = anlasBaseline ?: current
        )
    }

    fun recordV5Generation(count: Int): NovelAiAccountUiState {
        val current = usage?.approximateV5Images ?: return this
        return copy(
            localV5AllowanceSpent = localV5AllowanceSpent + count.coerceAtLeast(0),
            allowanceBaselineImages = allowanceBaselineImages ?: current
        )
    }

    fun reconcile(serverUsage: NovelAiAccountUsage): NovelAiAccountUiState {
        val acknowledgedAnlas = anlasBaseline?.let { baseline ->
            (baseline - serverUsage.anlas).coerceAtLeast(0L)
        } ?: 0L
        val remainingLocalAnlas = (localAnlasSpent - acknowledgedAnlas).coerceAtLeast(0L)
        val serverImages = serverUsage.approximateV5Images
        val acknowledged = if (allowanceBaselineImages != null && serverImages != null) {
            (allowanceBaselineImages - serverImages).coerceAtLeast(0)
        } else 0
        val remainingLocal = (localV5AllowanceSpent - acknowledged).coerceAtLeast(0)
        return copy(
            usage = serverUsage,
            loading = false,
            error = null,
            localAnlasSpent = remainingLocalAnlas,
            anlasBaseline = serverUsage.anlas.takeIf { remainingLocalAnlas > 0L },
            localV5AllowanceSpent = remainingLocal,
            allowanceBaselineImages = serverImages.takeIf { remainingLocal > 0 }
        )
    }
}
