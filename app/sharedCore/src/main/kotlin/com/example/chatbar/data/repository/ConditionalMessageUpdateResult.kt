package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage

enum class MessageUpdateStage { READ_MESSAGE, READ_SESSION, PREPARE_INDEX, WRITE_MESSAGE, WRITE_INDEX, WRITE_PREVIEW }

/** In-memory commit evidence, not a persisted notice or a UI success state. */
sealed interface ConditionalMessageUpdateResult {
    data class Success(val message: ChatMessage, val changed: Boolean) : ConditionalMessageUpdateResult
    data class Conflict(val reason: String) : ConditionalMessageUpdateResult
    data class Missing(val stage: MessageUpdateStage) : ConditionalMessageUpdateResult
    data class Corrupt(val stage: MessageUpdateStage, val cause: Exception) : ConditionalMessageUpdateResult
    data class StorageFailure(val stage: MessageUpdateStage, val cause: Exception) : ConditionalMessageUpdateResult
    /** The write was attempted; callers must not assume rollback or complete index/preview publication. */
    data class CommitUncertain(
        val attempted: ChatMessage,
        val stage: MessageUpdateStage,
        val cause: Exception,
        val durableMessage: JsonFileStorage.EntityReadResult<ChatMessage>,
    ) : ConditionalMessageUpdateResult
}
