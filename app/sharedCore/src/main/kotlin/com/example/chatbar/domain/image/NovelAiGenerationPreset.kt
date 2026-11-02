package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable

/** Explicit generation settings only. Account, card, model-endpoint and editor state are not transport fields. */
@Serializable
data class NovelAiPresetCharacter(
    val prompt: String,
    val negativePrompt: String,
    val center: DesignedCharacterCenter? = null,
    val enabled: Boolean = true,
)

@Serializable
data class NovelAiGenerationPreset(
    @kotlinx.serialization.Required val format: String = FORMAT,
    @kotlinx.serialization.Required val version: Int = 1,
    val stylePrompt: String,
    val basePrompt: String,
    val extraPrompt: String,
    val negativePrompt: String,
    val characters: List<NovelAiPresetCharacter>,
    val naturalLanguageMode: Boolean,
    val continuousModeEnabled: Boolean,
    val continuousTargetCount: Int,
    val selectedModel: NovelAiImageModel,
    val v45Settings: NovelAiGenerationSettings,
    val v5Settings: NovelAiGenerationSettings,
    val imageGuidance: NovelAiImageGuidanceDraft,
) {
    fun validate() {
        require(format == FORMAT && version == 1) { "生图预设格式或版本不支持" }
        require(continuousTargetCount > 0) { "连续生成目标图片数必须大于零" }
        require(v45Settings.model == NovelAiImageModel.V4_5_FULL && v5Settings.model == NovelAiImageModel.V5_FULL)
        require(v45Settings.validationError(0) == null && v5Settings.validationError(0) == null)
        val active = if (selectedModel == NovelAiImageModel.V4_5_FULL) v45Settings else v5Settings
        require(active.validationError(characters.count { it.enabled }) == null)
        require(characters.all { it.center == null || it.center.x.isFinite() && it.center.y.isFinite() &&
            it.center.x in 0f..1f && it.center.y in 0f..1f })
        require(imageGuidance.vibes.size <= NovelAiImageGuidanceDraft.MAX_VIBES)
        require(imageGuidance.validationError(selectedModel) == null)
        require(listOf(imageGuidance.preciseReference.strength, imageGuidance.preciseReference.fidelity,
            imageGuidance.imageToImageStrength, imageGuidance.imageToImageNoise, imageGuidance.inpaintStrength)
            .all { it.isFinite() && it in 0f..1f })
        require(imageGuidance.vibes.all { it.informationExtracted.isFinite() && it.informationExtracted in 0f..1f &&
            it.strength.isFinite() && it.strength in 0f..1f })
        require(imageGuidance.focusedInpaintRegion?.isValid != false)
        imageGuidance.presetAssets().forEach { asset ->
            require(asset.sha256.matches(Regex("[a-f0-9]{64}")) && asset.isUsable)
            require(asset.path == "resources/${asset.sha256}.image" && asset.id == asset.sha256)
        }
        imageGuidance.vibes.mapNotNull { it.encodedVibe }.forEach { value ->
            require(value.matches(Regex("resources/[a-f0-9]{64}\\.vibe"))) { "生图预设编码引用无效" }
        }
    }

    /** Caller has already checked and materialized every portable resource before accepting this replacement. */
    fun applyTo(target: NovelAiStudioDraft, materializedGuidance: NovelAiImageGuidanceDraft): NovelAiStudioDraft {
        validate()
        return target.copy(stylePrompt = stylePrompt, basePrompt = basePrompt, extraPrompt = extraPrompt,
            negativePrompt = negativePrompt,
            characters = characters.map { NovelAiCharacterPromptDraft(prompt = it.prompt, negativePrompt = it.negativePrompt,
                center = it.center, enabled = it.enabled) },
            naturalLanguageMode = naturalLanguageMode, selectedModel = selectedModel,
            continuousModeEnabled = continuousModeEnabled, continuousTargetCount = continuousTargetCount,
            v45Settings = v45Settings, v5Settings = v5Settings, imageGuidance = materializedGuidance,
            followDefaultNovelAiImageModel = false, importedCharacterCardId = null,
            importedCharacterPromptSources = emptyList(), conversionSnapshot = null)
    }

    companion object {
        const val FORMAT = "ccb-studio-generation-preset"
        fun capture(draft: NovelAiStudioDraft, imageResource: (NovelAiStudioAssetRef) -> NovelAiStudioAssetRef,
            encodingResource: (String) -> String): NovelAiGenerationPreset = NovelAiGenerationPreset(
            stylePrompt = draft.stylePrompt, basePrompt = draft.basePrompt, extraPrompt = draft.extraPrompt,
            negativePrompt = draft.negativePrompt,
            characters = draft.characters.map { NovelAiPresetCharacter(it.prompt, it.negativePrompt, it.center, it.enabled) },
            naturalLanguageMode = draft.naturalLanguageMode, selectedModel = draft.selectedModel,
            continuousModeEnabled = draft.continuousModeEnabled, continuousTargetCount = draft.continuousTargetCount,
            v45Settings = draft.v45Settings, v5Settings = draft.v5Settings,
            imageGuidance = draft.imageGuidance.mapPresetResources(imageResource, encodingResource))
            .also { it.validate() }
    }
}

fun NovelAiImageGuidanceDraft.presetAssets(): List<NovelAiStudioAssetRef> =
    listOfNotNull(baseImage, maskImage, preciseReference.asset) + vibes.mapNotNull { it.asset }

fun NovelAiImageGuidanceDraft.mapPresetResources(image: (NovelAiStudioAssetRef) -> NovelAiStudioAssetRef,
    encoding: (String) -> String): NovelAiImageGuidanceDraft = copy(
    baseImage = baseImage?.let(image), maskImage = maskImage?.let(image),
    preciseReference = preciseReference.copy(asset = preciseReference.asset?.let(image)),
    vibes = vibes.mapIndexed { index, vibe -> vibe.copy(id = "vibe-$index", asset = vibe.asset?.let(image),
        encodedVibe = vibe.encodedVibe?.takeIf(String::isNotBlank)?.let(encoding)) })
