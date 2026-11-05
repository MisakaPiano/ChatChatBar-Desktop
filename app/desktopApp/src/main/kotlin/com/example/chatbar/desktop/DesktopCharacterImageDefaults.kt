package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.image.NovelAiCharacterImageSettings
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiGenerationSettings
import com.example.chatbar.domain.image.NovelAiEffort

/** Same draft mutation owner as all other card fields; showing controls never materializes defaults. */
@Composable
internal fun DesktopCharacterImageDefaults(card: CharacterCard, globalImageModel: NovelAiImageModel?, edit: ((CharacterCard) -> CharacterCard) -> Unit) {
    val t = LocalDesktopUiStrings.current
    val model = card.defaultNovelAiImageModel ?: globalImageModel
    fun change(transform: (NovelAiCharacterImageSettings) -> NovelAiCharacterImageSettings) = edit { current ->
        current.copy(defaultImageGenerationSettings = transform(current.defaultImageGenerationSettings ?: NovelAiCharacterImageSettings()))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CompactChoice(t(DesktopUiText.CHARACTER_IMAGE_MODEL), listOf<NovelAiImageModel?>(null) + NovelAiImageModel.entries,
            card.defaultNovelAiImageModel, { it?.displayName ?: t(DesktopUiText.FOLLOW_DEFAULT) }) { model ->
            edit { it.copy(defaultNovelAiImageModel = model) }
        }
        StudioSwitch(t(DesktopUiText.CHARACTER_IMAGE_PARAMETERS), card.defaultImageGenerationSettings != null) {
            edit { it.copy(defaultImageGenerationSettings = if (it.defaultImageGenerationSettings == null)
                NovelAiCharacterImageSettings() else null) }
        }
        val parameters = card.defaultImageGenerationSettings ?: NovelAiCharacterImageSettings()
        val effective = model?.let { parameters.applyTo(NovelAiGenerationSettings(model = it)) }
        if (card.defaultImageGenerationSettings == null) StatusText(t(DesktopUiText.CHARACTER_IMAGE_DEFAULT_PARAMETERS))
        if (model == null) StatusText(t(DesktopUiText.CHARACTER_IMAGE_MODEL_PENDING)) else {
            StudioAction("使用 NovelAI 默认采样参数", icon = DesktopAppIcons.Reset) {
                change { it.withDefaultSampling(model) }
            }
            if (model == NovelAiImageModel.V5_FULL) {
                StudioChips("Effort", NovelAiEffort.entries, parameters.effort,
                    { if (it == NovelAiEffort.HIGH) "High" else "Medium" }) { value -> change { it.copy(effort = value) } }
                StudioSwitch("透明背景", parameters.transparentBackground) { change { it.copy(transparentBackground = !it.transparentBackground) } }
            }
            if (effective?.isMedium == true) StatusText("Medium 使用 14 Steps、Euler Ancestral 与固定负面词；High 参数和自定义负面词会保留。") else {
                CompactChoice("Sampler", model.samplers, parameters.sampler.takeIf { it in model.samplers } ?: model.samplers.first(),
                    { it.displayName }) { value -> change { it.copy(sampler = value) } }
                if (parameters.sampler !in model.samplers) StatusText(
                    "${parameters.sampler.displayName} → ${model.samplers.first().displayName} · ${t(DesktopUiText.CHARACTER_IMAGE_SAMPLER_UNAVAILABLE)}")
            }
        }
        if (effective?.isMedium != true) StudioSlider("Steps", parameters.steps.toFloat(), 1f, 50f, 1f, 0) { value -> change { it.copy(steps = value.toInt()) } }
        StudioSlider("CFG Scale", parameters.guidance, 1f, 10f, .1f, 1) { value -> change { it.copy(guidance = value) } }
        if (effective?.isMedium != true) StudioSlider("CFG Rescale", parameters.cfgRescale, 0f, 1f, .05f, 2) { value -> change { it.copy(cfgRescale = value) } }
        if (model?.supportsVarietyPlus == true) StudioSwitch("Variety+", parameters.varietyPlus) { change { it.copy(varietyPlus = !it.varietyPlus) } }
        if (card.defaultImageGenerationSettings != null) BootstrapButton(t(DesktopUiText.CHARACTER_IMAGE_RESET), secondary = true) {
            edit { it.copy(defaultImageGenerationSettings = null) }
        }
        StatusText(t(DesktopUiText.CHARACTER_IMAGE_DEFAULTS_NOTE))
    }
}
