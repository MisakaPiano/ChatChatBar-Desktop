package com.example.chatbar.domain.image

import com.example.chatbar.domain.prompt.CharacterCardAiPromptAuthority

object CharacterAvatarImagePolicy {
    val imageSize = NovelAiImageSize(
        width = 512,
        height = 512,
        label = "Small Square"
    )

    fun promptPlan(
        stylePrompt: String,
        characterPrompt: String,
        negativePrompt: String
    ): NovelAiPromptPlan = NovelAiPromptPlan(
        baseCaption = CharacterCardAiPromptAuthority.novelAiCharacterAvatarPositivePrompt(
            stylePrompt,
            characterPrompt
        ),
        characterCaptions = emptyList(),
        sizePreset = NovelAiImageSizePreset.SQUARE,
        negativePrompt = CharacterCardAiPromptAuthority.novelAiCharacterAvatarNegativePrompt(negativePrompt)
    )
}
