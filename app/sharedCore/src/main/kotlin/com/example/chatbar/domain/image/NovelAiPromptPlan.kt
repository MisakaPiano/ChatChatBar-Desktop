package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable
import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults

@Serializable
data class DesignedImagePrompt(
    val baseCaption: String = "",
    val scenePrompt: String = "",
    val sizePreset: String = NovelAiImageSizePreset.PORTRAIT.name,
    val characters: List<DesignedCharacterPrompt> = emptyList()
) {
    val effectiveBaseCaption: String get() = baseCaption.ifBlank { scenePrompt }
}

@Serializable
data class DesignedCharacterPrompt(
    val caption: String = "",
    val adjustment: String = "",
    val center: DesignedCharacterCenter? = null
) {
    val effectiveCaption: String get() = caption.ifBlank { adjustment }
}

@Serializable
data class DesignedCharacterCenter(
    val x: Float,
    val y: Float
)

@Serializable
data class NovelAiCharacterCaption(
    val prompt: String,
    val center: DesignedCharacterCenter,
    val negativePrompt: String = ""
)

@Serializable
data class NovelAiPromptPlan(
    val baseCaption: String,
    val characterCaptions: List<NovelAiCharacterCaption>,
    val designed: DesignedImagePrompt? = null,
    val sizePreset: NovelAiImageSizePreset = NovelAiImageSizePreset.PORTRAIT,
    val negativePrompt: String = CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt(),
    val stylePrompt: String = ""
) {
    val effectiveNegativePrompt: String
        get() = CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(negativePrompt)
}

object NovelAiPromptDelimiterPolicy {
    fun normalizeForRequest(prompt: NovelAiPromptPlan): NovelAiPromptPlan = prompt.copy(
        baseCaption = normalize(prompt.baseCaption),
        characterCaptions = prompt.characterCaptions.map { caption ->
            caption.copy(
                prompt = normalize(caption.prompt),
                negativePrompt = normalize(caption.negativePrompt)
            )
        },
        negativePrompt = normalize(prompt.negativePrompt)
    )

    fun normalize(text: String): String = text.replace('，', ',')
}

object NovelAiPromptComposition {
    fun fallbackCenter(index: Int, count: Int): DesignedCharacterCenter {
        if (count <= 1) return DesignedCharacterCenter(0.5f, 0.5f)
        return DesignedCharacterCenter(
            x = (index + 1f) / (count + 1f),
            y = 0.5f
        )
    }
    fun prependStylePrompt(stylePrompt: String, baseCaption: String): String {
        val style = stylePrompt.trim()
        val scene = baseCaption.trim()
        return when {
            style.isBlank() -> scene
            scene.isBlank() -> style
            style.endsWith(',') -> "$style $scene"
            else -> "$style, $scene"
        }
    }
}
