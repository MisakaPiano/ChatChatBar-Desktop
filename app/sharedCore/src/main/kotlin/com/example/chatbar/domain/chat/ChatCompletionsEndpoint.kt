package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.model.GeminiOpenAiEndpoint

/** Common URL owner for main chat, auxiliary requests, Moments and connection probes. */
internal fun ModelConfig.chatCompletionsUrl(): String {
    val requestBase = GeminiOpenAiEndpoint.resolve(baseUrl) ?: baseUrl.trimEnd('/')
    return "$requestBase/chat/completions"
}
