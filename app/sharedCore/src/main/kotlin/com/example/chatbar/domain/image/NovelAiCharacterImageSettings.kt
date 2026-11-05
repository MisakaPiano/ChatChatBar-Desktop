package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/** Selected official 1.4.8 card settings; absent new fields retain the legacy behavior. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class NovelAiCharacterImageSettings(
    val sampler: NovelAiSampler = NovelAiSampler.EULER_ANCESTRAL,
    val steps: Int = 28,
    val guidance: Float = 8f,
    val cfgRescale: Float = 0f,
    val varietyPlus: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val effort: NovelAiEffort = NovelAiEffort.HIGH,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val transparentBackground: Boolean = false
) {
    fun withDefaultSampling(model: NovelAiImageModel): NovelAiCharacterImageSettings =
        NovelAiGenerationDefaults.forModel(model).let { defaults ->
            copy(sampler = defaults.sampler, steps = defaults.steps, guidance = defaults.guidance, cfgRescale = defaults.cfgRescale)
        }

    fun applyTo(settings: NovelAiGenerationSettings): NovelAiGenerationSettings = settings.copy(
        sampler = sampler,
        steps = steps,
        guidance = guidance,
        cfgRescale = cfgRescale,
        varietyPlus = varietyPlus,
        effort = effort,
        transparentBackground = transparentBackground,
        varietyPlusSigma = null
    ).normalized()
}
