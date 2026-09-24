package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class FishAudioVoiceBinding(
    val referenceId: String,
    val title: String,
    val authorId: String? = null,
    val authorName: String? = null,
    val coverImage: String? = null,
    val sampleAudio: String? = null,
    val sampleText: String? = null,
    val visibility: String? = null,
    val languages: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)
