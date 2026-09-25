package com.example.chatbar.domain.prompt

/** Character transfer 与 NovelAI image flow 共用的 Prompt-domain authority。 */
object CharacterNaiPromptDefaults {
    const val DEFAULT_CHARACTER_NAI_NEGATIVE_PROMPT = """
worst quality, bad quality, lowres, blurry, very displeasing, jpeg artifacts, chromatic aberration, film grain, halftone, unfinished,
deformed, distorted anatomy, bad proportions, bad hands, bad eyes, asymmetrical face, 3.8::extra fingers, fewer digits, artist collaboration::, extra hands, extra legs,
censored, watermark, user_interface, logo, signature, multiple views, turnaround, reference, 4koma, 2koma,
high contrast, overexposure, toon, oekaki, chibi, old,
3::dark areola, dark pussy::, dark penis
"""

    fun defaultCharacterNaiNegativePrompt(): String =
        DEFAULT_CHARACTER_NAI_NEGATIVE_PROMPT.trim()

    fun effectiveCharacterNaiNegativePrompt(value: String): String =
        value.trim().ifBlank { defaultCharacterNaiNegativePrompt() }
}
