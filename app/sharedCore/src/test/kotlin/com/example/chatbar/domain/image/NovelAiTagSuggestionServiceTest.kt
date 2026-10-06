package com.example.chatbar.domain.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NovelAiTagSuggestionServiceTest {
    private fun tag(name: String, count: Long = 0, dictionary: Boolean = false) =
        NovelAiTagCandidate(name, "测试", count, NovelAiTagCategory.GENERAL, fromDictionary = dictionary)

    @Test fun publishesEachSourceWhileBothSourcesAreStillSearching() = runBlocking {
        val firstVisible = CompletableDeferred<Unit>()
        val finished = mutableSetOf<String>()
        var sawOnlyDictionary = false
        val finalVisible = withTimeout(2_000) {
            streamTagSuggestions("v1", tags = { found, _ ->
                try {
                    firstVisible.await()
                    found(tag("blue_eyes", 100))
                    awaitCancellation()
                } finally { finished += "tags" }
            }, words = { found, _ ->
                try {
                    found(tag("blue", dictionary = true))
                    awaitCancellation()
                } finally { finished += "words" }
            }).first { update ->
                assertTrue(update.loading)
                assertTrue(finished.isEmpty())
                if (update.candidates.size == 1) {
                    sawOnlyDictionary = true
                    assertEquals("blue", update.candidates.single().name)
                    firstVisible.complete(Unit)
                }
                update.candidates.size == 2
            }
        }
        assertTrue(sawOnlyDictionary)
        assertEquals(listOf("blue_eyes", "blue"), finalVisible.candidates.map { it.name })
        assertEquals(setOf("tags", "words"), finished)
    }

    @Test fun reordersNewMatchesAndReplacesDictionaryDuplicates() {
        val state = TagSuggestionAccumulator()
        state.found(tag("blue_eyes", dictionary = true))
        state.found(tag("zebra", dictionary = true))
        state.found(tag("low", 1))
        state.found(tag("blue_eyes", 80))
        state.found(tag("high", 100))
        state.found(tag("blue_eyes", dictionary = true))
        assertEquals(listOf("high", "blue_eyes", "low", "zebra"), state.snapshot("v").candidates.map { it.name })
        assertFalse(state.snapshot("v").candidates[1].fromDictionary)
    }

    @Test fun emptyAndFailedSourcesFinishWithoutHidingSuccessfulMatches() = runBlocking {
        val results = streamTagSuggestions("v", tags = { _, _ -> error("fixture failure") }, words = { found, _ ->
            found(tag("word", dictionary = true))
        }).toList()
        assertFalse(results.last().loading)
        assertEquals("word", results.last().candidates.single().name)
        assertTrue(results.last().error!!.contains("fixture failure"))
        assertTrue(streamTagSuggestions("v", { _, _ -> }, { _, _ -> }).toList().last().candidates.isEmpty())
    }

    @Test fun cacheSeparatesVersionsAndStoresEmptyResults() {
        val cache = TagCompletionCache()
        cache.put("v1", "蓝", emptyList())
        assertEquals(emptyList<NovelAiTagCandidate>(), cache.get("v1", "蓝"))
        assertNull(cache.get("v2", "蓝"))
        repeat(129) { cache.put("v1", "q$it", listOf(tag("n$it"))) }
        assertNull(cache.get("v1", "蓝"))
        assertEquals("n128", cache.get("v1", "q128")!!.single().name)
    }

    @Test fun decodesPreorderedRanksWithoutMaterializingFullPostingList() {
        val reader = RankedPostingReader(byteArrayOf(0, 3, 0x81.toByte(), 1, 1))
        assertEquals(listOf(0L, 3L, 132L, 133L), buildList { while (reader.hasNext()) add(reader.next()) })
        val grams = completionGrams("蓝眼")
        assertTrue(grams.containsAll(completionGrams("蓝")))
        assertEquals(3, grams.size)
        assertNotEquals(completionGrams("蓝眼"), completionGrams("眼蓝"))
    }
}
