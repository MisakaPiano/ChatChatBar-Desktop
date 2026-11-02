package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable

@Serializable
enum class NovelAiImageModel(
    val apiId: String,
    val displayName: String,
    val maxCharacters: Int,
    val promptTokenLimit: Int,
    val tokenizerKind: NovelAiTokenizerKind
) {
    V4_5_FULL("nai-diffusion-4-5-full", "V4.5 Full", 6, 512, NovelAiTokenizerKind.T5),
    V5_FULL("nai-diffusion-5-full", "V5 Full", 22, 1471, NovelAiTokenizerKind.QWEN);

    val supportsVarietyPlus: Boolean get() = this == V4_5_FULL
    val samplers: List<NovelAiSampler> get() = when (this) {
        V4_5_FULL -> listOf(NovelAiSampler.EULER_ANCESTRAL, NovelAiSampler.EULER,
            NovelAiSampler.DPM_PLUS_PLUS_2S_ANCESTRAL, NovelAiSampler.DPM_PLUS_PLUS_2M,
            NovelAiSampler.DPM_PLUS_PLUS_SDE)
        V5_FULL -> listOf(NovelAiSampler.EULER_ANCESTRAL, NovelAiSampler.EULER,
            NovelAiSampler.DPM_PLUS_PLUS_2S_ANCESTRAL, NovelAiSampler.DPM_PLUS_PLUS_2M_SDE,
            NovelAiSampler.DPM_PLUS_PLUS_2M, NovelAiSampler.DPM_PLUS_PLUS_SDE)
    }
}

enum class NovelAiTokenizerKind { T5, QWEN }
