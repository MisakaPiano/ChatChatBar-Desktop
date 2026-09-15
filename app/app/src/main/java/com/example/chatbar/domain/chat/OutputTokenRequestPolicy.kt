package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig

/** Omit client output limits for tasks the user can stop manually. */
internal fun ModelConfig.withoutOutputTokenLimit(): ModelConfig = copy(
    maxOutputTokens = null,
    customParams = customParams - setOf("max_tokens", "max_completion_tokens")
)
