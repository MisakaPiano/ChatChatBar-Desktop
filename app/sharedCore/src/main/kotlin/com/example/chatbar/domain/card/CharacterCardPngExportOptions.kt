package com.example.chatbar.domain.card

/** Character card PNG visual export options shared by platform renderers. */
data class CharacterCardPngExportOptions(
    val sizePx: Int = 1536,
    val gradientHeight: Float = 0.42f,
    val gradientStrength: Float = 0.72f,
    val logoScale: Float = 0.095f,
    val titleScale: Float = 0.052f,
    val cropCenterX: Float = 0.5f,
    val cropCenterY: Float = 0.5f,
    val cropZoom: Float = 1f,
) {
    fun normalized(): CharacterCardPngExportOptions = copy(
        sizePx = sizePx.coerceIn(1024, 2048),
        gradientHeight = gradientHeight.coerceIn(0.25f, 0.68f),
        gradientStrength = gradientStrength.coerceIn(0.45f, 0.9f),
        logoScale = logoScale.coerceIn(0.07f, 0.14f),
        titleScale = titleScale.coerceIn(0.04f, 0.08f),
        cropCenterX = cropCenterX.coerceIn(0f, 1f),
        cropCenterY = cropCenterY.coerceIn(0f, 1f),
        cropZoom = cropZoom.coerceIn(1f, 6f),
    )
}
