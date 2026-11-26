package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
data class ChatDraft(
    val sessionId: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val images: List<String> = emptyList(),
) {
    // Keep the original constructor and its default bridge for frozen runtime consumers.
    constructor(sessionId: String, content: String, updatedAt: Long = System.currentTimeMillis()) :
        this(sessionId, content, updatedAt, emptyList())

    fun copy(sessionId: String = this.sessionId, content: String = this.content, updatedAt: Long = this.updatedAt): ChatDraft =
        ChatDraft(sessionId, content, updatedAt, images)
}
