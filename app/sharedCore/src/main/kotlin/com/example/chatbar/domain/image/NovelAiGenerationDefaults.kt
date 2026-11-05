package com.example.chatbar.domain.image

/** Explicit reset values from the official image frontend. Existing saved settings are never migrated. */
object NovelAiGenerationDefaults {
    fun forModel(model: NovelAiImageModel): NovelAiGenerationSettings = NovelAiGenerationSettings(
        model = model,
        steps = 23,
        guidance = when (model) {
            NovelAiImageModel.V4_5_FULL -> 5f
            NovelAiImageModel.V5_FULL -> 7f
        },
        sampler = NovelAiSampler.EULER_ANCESTRAL,
    )
}
