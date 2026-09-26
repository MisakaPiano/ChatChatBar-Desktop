package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
enum class FormatPromptPosition {
    START,
    END,
    BOTH;

    val includesStart: Boolean
        get() = this == START || this == BOTH

    val includesEnd: Boolean
        get() = this == END || this == BOTH
}
