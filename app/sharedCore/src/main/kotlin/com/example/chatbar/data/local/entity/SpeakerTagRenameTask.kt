package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class SpeakerTagRename(
    val characterId: String,
    val oldName: String,
    val newName: String
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class SpeakerTagRenameTask(
    val id: String,
    val characterCardId: String,
    val expectedCardUpdatedAt: Long,
    val renames: List<SpeakerTagRename>,
    val createdAt: Long,
    val lastError: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val rewritePlan: List<SpeakerTagMessageRewrite>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val rewriteProtocolVersion: Int? = null,
)

/** Immutable metadata prepared before the first write; no duplicate message body is persisted. */
@Serializable
data class SpeakerTagMessageRewrite(val sessionId: String, val messageId: String,
    val beforeSha256: String, val afterSha256: String, val updatedAt: Long)
