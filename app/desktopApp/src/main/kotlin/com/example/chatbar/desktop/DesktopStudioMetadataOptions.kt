package com.example.chatbar.desktop

import androidx.compose.runtime.Composable
import com.example.chatbar.domain.image.*

/** Same selection used by the normal image-metadata import action; raw metadata stays unchanged. */
@Composable
internal fun DesktopStudioMetadataOptions(metadata: NovelAiStudioPngMetadata?, selection: NovelAiStudioMetadataSelection,
    onChange: (NovelAiStudioMetadataSelection) -> Unit) {
    StudioToggle("正面 Prompt", selection.positivePrompt) { onChange(selection.copy(positivePrompt = !selection.positivePrompt)) }
    if (selection.positivePrompt && metadata?.hasAmbiguousTransparentSuffix == true) {
        StatusText("正文末尾的透明标签也可能是手写内容，请选择保留正文，或交给开关管理。")
        CompactChoice("透明标签", NovelAiTransparentSuffixImport.entries, selection.transparentSuffix, { it.displayName }) {
            onChange(selection.copy(transparentSuffix = it))
        }
    }
    StudioToggle("负面 Prompt", selection.negativePrompt) { onChange(selection.copy(negativePrompt = !selection.negativePrompt)) }
    CompactChoice("角色 Prompt", NovelAiCharacterImportMode.entries, selection.characterPrompts, { it.displayName }) {
        onChange(selection.copy(characterPrompts = it))
    }
    StudioToggle("生成参数", selection.generationSettings) { onChange(selection.copy(generationSettings = !selection.generationSettings)) }
    StudioToggle("Seed", selection.seed) { onChange(selection.copy(seed = !selection.seed)) }
    StudioToggle("图像引导", selection.imageGuidance) { onChange(selection.copy(imageGuidance = !selection.imageGuidance)) }
}
