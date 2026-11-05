package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard

/** Explicit application provenance, never decoded from a user-supplied card Package. */
data class CharacterCardCommunitySource(val itemId: String, val updatedAt: String, val sha256: String, val title: String) {
    init {
        require(itemId.isNotBlank() && updatedAt.isNotBlank() && title.isNotBlank())
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
    }
    internal fun applyTo(card: CharacterCard): CharacterCard = card.copy(
        communityItemId = itemId, communityItemUpdatedAt = updatedAt,
        communityItemSha256 = sha256, communityItemTitle = title,
    )
}

/** Optional Desktop pending-decision guard; null expectedItemId means an ordinary local target. */
data class CharacterCommunityOverwriteAuthority(val expectedItemId: String?)
