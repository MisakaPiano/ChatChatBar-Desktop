package com.example.chatbar.ui.imageprompt

import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiGenerationRecipe
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiHistorySelectionPolicyTest {
    @Test
    fun secondLongPressArmsRangeAndNextClickSelectsInclusiveRangeOnce() {
        val albums = albums(1, 1, 1, 1)
        val first = select(NovelAiHistorySelection(), albums, 0, longPress = true)
        assertEquals(listOf(albums[0].key), first.keys)
        assertEquals(null, first.rangeAnchorKey)
        val armed = select(first, albums, 0, longPress = true)
        assertEquals(albums[0].key, armed.rangeAnchorKey)
        val range = select(armed, albums, 2)
        assertEquals(albums.take(3).map { it.key }, range.keys)
        assertEquals(null, range.rangeAnchorKey)
        assertEquals(listOf(albums[0].key, albums[2].key), select(range, albums, 1).keys)
    }

    @Test
    fun reverseRangeKeepsPreviousSelectionOrderAndDoesNotDuplicateEndpoints() {
        val albums = albums(1, 1, 1, 1, 1)
        val armed = NovelAiHistorySelection(listOf(albums[4].key, albums[3].key), albums[3].key)
        val range = select(armed, albums, 1)
        assertEquals(listOf(4, 3, 1, 2).map { albums[it].key }, range.keys)
        assertEquals(null, range.rangeAnchorKey)
    }

    @Test
    fun foldedRangeSelectsAllMembersInVisibleAlbumOrderAndSupportsPartialSelection() {
        val albums = albums(2, 3, 2, 1)
        val partial = NovelAiHistorySelection(listOf(albums[0].key))
        val filled = select(partial, albums, 0, longPress = true)
        assertEquals(albums[0].images.map { it.key }, filled.keys)
        assertEquals(null, filled.rangeAnchorKey)
        val range = select(select(filled, albums, 0, longPress = true), albums, 2)
        assertEquals(albums.take(3).flatMap { it.images }.map { it.key }, range.keys)
        assertEquals(albums.drop(1).take(2).flatMap { it.images }.map { it.key }, select(range, albums, 0).keys)
    }

    @Test
    fun sameEndpointKeepsSelectionAndMissingTargetDoesNotChangeIt() {
        val albums = albums(1, 1)
        val armed = NovelAiHistorySelection(listOf(albums[0].key), albums[0].key)
        assertEquals(NovelAiHistorySelection(armed.keys), select(armed, albums, 0))
        assertEquals(armed, NovelAiHistorySelectionPolicy.selectAlbum(armed, albums, "missing", false))
    }

    @Test
    fun rangeUsesOnlyCurrentFilteredLevelAndNewLongPressReplacesPendingAnchor() {
        val all = albums(1, 1, 1, 1)
        val visible = listOf(all[0], all[3])
        val armed = NovelAiHistorySelection(listOf(all[0].key), all[0].key)
        assertEquals(visible.map { it.key }, select(armed, visible, 1).keys)
        val added = select(armed, all, 2, longPress = true)
        assertEquals(null, added.rangeAnchorKey)
        assertEquals(all[2].key, select(added, all, 2, longPress = true).rangeAnchorKey)
    }

    private fun select(
        state: NovelAiHistorySelection,
        albums: List<NovelAiHistoryAlbum>,
        index: Int,
        longPress: Boolean = false
    ) = NovelAiHistorySelectionPolicy.selectAlbum(state, albums, albums[index].key, longPress)

    private fun albums(vararg sizes: Int): List<NovelAiHistoryAlbum> = sizes.mapIndexed { index, size ->
        val entry = NovelAiGenerationHistoryEntry(
            id = "batch-$index",
            images = List(size) { NovelAiGenerationHistoryImage("image-$index-$it.png", it.toLong()) },
            recipe = NovelAiGenerationRecipe(),
            createdAt = index.toLong()
        )
        NovelAiHistoryAlbum(entry.images.mapIndexed { i, image -> NovelAiHistoryImageItem(entry, image, i) }, "")
    }

    @Test
    fun deselectCompactsOrderAndReselectAppends() {
        val selected = listOf("first", "second", "third")
        val compacted = NovelAiHistorySelectionPolicy.toggle(selected, "second")
        val reselected = NovelAiHistorySelectionPolicy.toggle(compacted, "second")

        assertEquals(listOf("first", "third"), compacted)
        assertEquals(listOf("first", "third", "second"), reselected)
    }

    @Test
    fun retainDropsImagesRemovedFromHistory() {
        assertEquals(
            listOf("second"),
            NovelAiHistorySelectionPolicy.retain(
                listOf("first", "second", "third"),
                setOf("second", "fourth")
            )
        )
    }
}
