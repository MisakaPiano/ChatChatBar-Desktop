package com.example.chatbar.domain.chat

const val OMITTED_SAVE_SLOT_IMAGE_PREFIX = "chatbar-save-slot-omitted-image:"

/** Formal chat request policy: only the first USER attachment reaches a multimodal model. */
object ChatImageRequestPolicy {
    fun firstUserImage(role: String, images: List<String>, multimodal: Boolean): String? =
        if (multimodal && role == "user") images.firstOrNull() else null
}
