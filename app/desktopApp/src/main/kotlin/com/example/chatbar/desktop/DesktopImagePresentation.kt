package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*

/** Formal ImageUseAsDialog / HistoryUseAsDialog presentation, shared by Desktop surfaces. */
internal fun desktopImageUseTargets(model: NovelAiImageModel) = NovelAiImageUseTarget.entries.filter {
    model == NovelAiImageModel.V4_5_FULL || it == NovelAiImageUseTarget.IMAGE_TO_IMAGE || it == NovelAiImageUseTarget.INPAINT
}

internal fun desktopHistoryApplyAvailable(recipe: NovelAiGenerationRecipe, mode: NovelAiHistoryApplyMode) =
    mode != NovelAiHistoryApplyMode.FULL || !recipe.imageGuidance.hasMissingHistorySource()

internal fun desktopGuidanceLabel(guidance: NovelAiImageGuidanceDraft, model: NovelAiImageModel): String =
    guidance.summary(model).takeIf(String::isNotBlank)?.let { "图像引导 · $it / 编辑" } ?: "图像引导"
