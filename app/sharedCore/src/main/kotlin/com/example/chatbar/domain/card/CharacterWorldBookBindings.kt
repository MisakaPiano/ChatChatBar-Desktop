package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.WorldBook

/** The legacy and current Character WorldBook references have one effective selection. */
object CharacterWorldBookBindings {
    fun effectiveIds(card: CharacterCard): List<String> =
        (card.worldBookIds + listOfNotNull(card.boundWorldBookId, card.characterBook?.id))
            .filter(String::isNotBlank)
            .distinct()

    fun independentEmbedded(card: CharacterCard): WorldBook? = card.characterBook?.copy(
        name = card.characterBook.name.ifBlank { "${card.name} 世界书" },
        sourcePresetKey = card.sourcePresetKey,
        sourcePresetVersion = card.sourcePresetVersion,
        updatedAt = System.currentTimeMillis(),
    )
}
