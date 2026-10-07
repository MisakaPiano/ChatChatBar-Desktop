package com.example.chatbar.domain.image

enum class ProcessImageKind {
    STATIC,
    GIF,
    CHATBAR_DISGUISE_APNG,
    OTHER_APNG
}

enum class ProcessedImageOperation {
    APNG_DISGUISE,
    APNG_RESTORE
}

data class ImportedProcessImage(
    val path: String,
    val displayName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val kind: ProcessImageKind = ProcessImageKind.STATIC
) {
    val isAnimatedGif: Boolean get() = kind == ProcessImageKind.GIF
    val isApng: Boolean get() = kind == ProcessImageKind.CHATBAR_DISGUISE_APNG || kind == ProcessImageKind.OTHER_APNG
    val canRestoreApng: Boolean get() = kind == ProcessImageKind.CHATBAR_DISGUISE_APNG
}

data class ProcessedImage(
    val path: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val isAnimated: Boolean = frameCount > 1,
    val operation: ProcessedImageOperation = ProcessedImageOperation.APNG_DISGUISE
)
