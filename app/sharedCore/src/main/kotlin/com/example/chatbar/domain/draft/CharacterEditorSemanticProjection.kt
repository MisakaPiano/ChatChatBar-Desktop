package com.example.chatbar.domain.draft

import com.example.chatbar.data.local.entity.CharacterCard

/** Character fields intentionally excluded from editor conflict/recovery identity. */
object CharacterEditorSemanticProjection {
    fun normalize(card: CharacterCard): CharacterCard = card.copy(
        ragIndexStatus = "",
        ragIndexDone = 0,
        ragIndexTotal = 0,
        ragIndexMessage = null,
        ragIndexedAt = null,
        customDocuments = card.customDocuments.map {
            it.copy(
                contentHash = null,
                indexedHash = null,
                ragStatus = "",
                ragChunkCount = 0,
                ragIndexedAt = null,
                ragError = null
            )
        },
        pendingSpeakerRenameTasks = emptyList(),
        updatedAt = 0L
    )
}
