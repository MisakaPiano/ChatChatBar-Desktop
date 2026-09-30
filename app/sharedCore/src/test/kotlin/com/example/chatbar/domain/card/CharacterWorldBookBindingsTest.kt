package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterWorldBookBindingsTest {
    @Test
    fun `effective IDs include current bound and embedded references without duplicates`() {
        val embedded = WorldBook.create("").copy(entries = listOf(WorldBookEntry.create(content = "lore")))
        val card = CharacterCard.create("Source", "Hi").copy(
            worldBookIds = listOf("current", embedded.id),
            boundWorldBookId = "current",
            characterBook = embedded,
            sourcePresetKey = "preset",
            sourcePresetVersion = 2,
        )

        assertEquals(listOf("current", embedded.id), CharacterWorldBookBindings.effectiveIds(card))
        val materialized = CharacterWorldBookBindings.independentEmbedded(card)!!
        assertEquals(embedded.id, materialized.id)
        assertEquals(embedded.entries, materialized.entries)
        assertEquals("Source 世界书", materialized.name)
        assertEquals("preset", materialized.sourcePresetKey)
        assertEquals(2, materialized.sourcePresetVersion)
    }
}
