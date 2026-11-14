package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage

data class ChatGalleryImage(val messageId: String, val imageIndex: Int, val path: String,
    val sourceCreatedAt: Long, val readable: Boolean) {
    val key: String get() = "$messageId::$imageIndex"
}
data class ChatImageGallerySnapshot(val images: List<ChatGalleryImage> = emptyList(),
    val omittedImages: Int = 0, val scannedMessages: Int = 0)

/** Attachments belong to a message, not each text alternative. Never deduplicate by file path. */
class ChatImageGalleryCollector(private val readable: (String) -> Boolean = { true }) {
    private val images = mutableListOf<ChatGalleryImage>()
    private var omitted = 0
    private var scanned = 0
    fun accept(message: ChatMessage) {
        scanned++
        message.images.forEachIndexed { index, path ->
            if (path.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) omitted++
            else images += ChatGalleryImage(message.id, index, path, message.createdAt, path.isNotBlank() && readable(path))
        }
    }
    fun snapshot() = ChatImageGallerySnapshot(images.toList(), omitted, scanned)
}
