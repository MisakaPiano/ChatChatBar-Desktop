package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig

/** Image description inherits the selected model, as ordinary auxiliary requests do. */
fun ModelConfig.forImageDescriptionRequest(): ModelConfig = this
