package com.example.chatbar.desktop.backup

import kotlinx.serialization.Serializable
import com.example.chatbar.data.local.entity.GeneratedImageMetadata

// Private extraction DTOs pinned to official b4fa75d2; never registered or persisted.
@Serializable
internal data class MomentPostSnapshot(
    val id: String,
    val characterCardId: String,
    val sessionId: String,
    val senderCharacterId: String? = null,
    val senderName: String,
    val senderAvatar: String? = null,
    val text: String,
    val imagePath: String? = null,
    val imagePrompt: String = "",
    val generatedImageMetadata: GeneratedImageMetadata? = null,
    val imageBrief: String = "",
    val isPrivate: Boolean = false,
    val baseLikeCount: Int = 0,
    val userLiked: Boolean = false,
    val isPlaceholder: Boolean = false,
    val failureReason: String? = null,
    val generationCheckpoint: String = "",
    val generationReason: String = "",
    val scheduledAt: Long,
    val generatedAt: Long,
    val createdAt: Long = generatedAt,
    val updatedAt: Long = generatedAt
)

@Serializable
internal data class MomentTaskSnapshot(
    val id: String,
    val characterCardId: String,
    val sessionId: String,
    val scheduledAt: Long,
    val status: MomentSnapshotStatus = MomentSnapshotStatus.PENDING,
    val postId: String? = null,
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

@Serializable internal enum class MomentSnapshotStatus { PENDING, RUNNING, COMPLETED, SKIPPED, FAILED }
