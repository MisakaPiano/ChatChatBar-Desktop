package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryAuthor
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryArchiveInjectionPolicyActionProjectionParityTest {
    @Test
    fun legacyReferencesStayWithheldAfterTheOnlyAffectedSourceChanges() {
        val json = Json { encodeDefaults = true }
        val timeline = listOf(timeline("turn-0", 0), timeline("turn-1", 1), timeline("turn-2", 2))
        val unaffected = node("earlier", MemoryTier.LEGACY_REFERENCE, listOf("turn-0"), "早期正文")
        val accumulated = node("summary", MemoryTier.LEGACY_REFERENCE, listOf("turn-2"), "累计旧正文")
        val opaque = node("opaque", MemoryTier.LEGACY_REFERENCE, emptyList(), "来源未知旧正文")

        assertFalse(json.encodeToString(MemoryNode.serializer(), unaffected).contains("actionProjectionWithheld"))
        val marked = MemoryArchiveInjectionPolicy.withholdUnverifiedActionReferences(
            listOf(unaffected, accumulated, opaque), timeline, setOf("turn-1"),
        )
        assertEquals(listOf(false, true, true), marked.map(MemoryNode::actionProjectionWithheld))

        val coldRefs = marked.map {
            json.decodeFromString(MemoryNode.serializer(), json.encodeToString(MemoryNode.serializer(), it))
        }
        val afterEdit = MemoryArchiveInjectionPolicy.withholdUnverifiedActionReferences(coldRefs, timeline, emptySet())
        assertEquals(listOf(unaffected.body, accumulated.body, opaque.body), afterEdit.map(MemoryNode::body))
        val rendered = MemoryArchiveInjectionPolicy.render(emptyList(), afterEdit, timeline)
        assertTrue(rendered.contains(unaffected.body))
        assertFalse(rendered.contains(accumulated.body))
        assertFalse(rendered.contains(opaque.body))
    }

    private fun timeline(sourceTurnId: String, displayT: Long) = MemoryTimelineEntry(
        sourceTurnId = sourceTurnId,
        sourceOrder = displayT,
        displayT = displayT
    )

    private fun node(
        id: String,
        tier: MemoryTier,
        sourceTurnIds: List<String>,
        content: String,
        createdAt: Long = sourceTurnIds.firstOrNull()?.substringAfterLast('-')?.toLongOrNull() ?: 0L
    ) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = tier,
        sourceTurnIds = sourceTurnIds,
        content = content,
        author = MemoryAuthor.AI,
        createdAt = createdAt
    )
}
