package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import org.junit.Assert.*
import org.junit.Test

class MemoryIdentityContractTest {
    private fun entry(id: String, order: Long, deleted: Boolean = false) =
        MemoryTimelineEntry(id, order, 99, deleted)

    @Test fun `display T is derived after tombstones and duplicates without changing stable identity`() {
        val source = listOf(entry("c", 30), entry("b", 20, true), entry("a", 10), entry("c", 30))
        val normalized = MemoryTimelinePolicy.normalize(source)
        assertEquals(listOf("a", "c"), normalized.map { it.sourceTurnId })
        assertEquals(listOf(10L, 30L), normalized.map { it.sourceOrder })
        assertEquals(listOf(0L, 1L), normalized.map { it.displayT })
        assertEquals(listOf(99L, 99L, 99L, 99L), source.map { it.displayT })
        assertEquals(normalized, MemoryTimelinePolicy.normalize(normalized))
    }

    @Test fun `coverage cannot invent unknown duplicate reversed or gapped source turns`() {
        val timeline = MemoryTimelinePolicy.normalize(listOf(entry("a", 10), entry("b", 20), entry("c", 30)))
        assertTrue(MemoryTimelinePolicy.isContinuous(listOf("a", "b"), timeline))
        for (invalid in listOf(emptyList(), listOf("missing"), listOf("a", "a"), listOf("b", "a"), listOf("a", "c"))) {
            assertFalse(invalid.toString(), MemoryTimelinePolicy.isContinuous(invalid, timeline))
        }
        val gap = MemoryGap("gap", listOf("b"), reason = MemoryGapReason.DISABLED, createdAt = 0)
        assertFalse(MemoryTimelinePolicy.isContinuous(listOf("a", "b"), timeline, listOf(gap)))
        assertNull(MemoryTimelinePolicy.range(listOf("missing"), timeline))
    }

    @Test fun `verified node order uses current identity rather than legacy persisted T`() {
        val timeline = MemoryTimelinePolicy.normalize(listOf(entry("a", 10), entry("b", 20)))
        val first = MemoryNode("first", "session", MemoryTier.EPISODE, listOf("a"), createdAt = 10, startT = 90, endT = 90)
        val second = MemoryNode("second", "session", MemoryTier.EPISODE, listOf("b"), createdAt = 0, startT = 0, endT = 0)
        val unknown = first.copy(id = "unknown", sourceTurnIds = listOf("missing"), createdAt = -1)
        assertEquals(listOf(first, second, unknown), MemoryTimelinePolicy.sortNodes(listOf(unknown, second, first), timeline))
        assertEquals(MemoryDisplayRange(0, 0), MemoryTimelinePolicy.verifiedRange(first, timeline))
        assertNull(MemoryTimelinePolicy.verifiedRange(unknown, timeline))
    }

    @Test fun `ancestry proof requires child coverage and preserves ordered source evidence`() {
        val child = MemoryNode("child", "session", MemoryTier.EPISODE, listOf("a"), coverageHash = "proof", createdAt = 0)
        assertEquals("proof", MemoryHashes.parentCoverageUnits(listOf(child)).single().text)
        assertThrows(IllegalArgumentException::class.java) { MemoryHashes.parentCoverageUnits(listOf(child.copy(coverageHash = ""))) }
        val hashes = mapOf("a" to "ha", "b" to "hb")
        assertNotEquals(MemoryHashes.episodeCoverage(listOf("a", "b"), hashes, "summary"), MemoryHashes.episodeCoverage(listOf("b", "a"), hashes, "summary"))
        assertNotEquals(MemoryHashes.episodeCoverage(listOf("a"), hashes, "summary"), MemoryHashes.episodeCoverage(listOf("a"), hashes, "changed"))
    }
}
