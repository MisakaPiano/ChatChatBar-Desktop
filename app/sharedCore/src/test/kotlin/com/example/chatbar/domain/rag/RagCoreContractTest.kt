package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagCoreContractTest {
    @Test fun `document heading path and source metadata preserve official chunk rendering`() {
        val chunks = ChunkingEngine().chunkDocument("# Outer\nalpha\n## Inner\nbeta", "doc", "fixture.md")
        assertEquals(listOf("【来源】fixture.md > Outer\nalpha", "【来源】fixture.md > Outer > Inner\nbeta"), chunks.map { it.first })
        assertEquals(listOf("doc", "doc"), chunks.map { it.second["documentId"] })
        assertEquals("fixture.md > Outer > Inner", chunks.last().second["sourceLabel"])
    }

    @Test fun `official hard split and overlap preserve trailing source characters`() {
        assertEquals(listOf("abcdef", "efghijkl", "klmnop"), ChunkingEngine().chunkText("abcdefghijklmnop", chunkSize = 6, overlap = 2))
        assertEquals(emptyList(), ChunkingEngine().chunkText("  "))
        assertEquals(listOf("short"), ChunkingEngine().chunkText(" short "))
    }

    @Test fun `cosine search respects threshold topK and stable equal-score input order`() {
        val engine = VectorSearchEngine()
        val chunks = listOf(chunk("first", listOf(1f, 0f)), chunk("second", listOf(2f, 0f)),
            chunk("orthogonal", listOf(0f, 1f)), chunk("opposite", listOf(-1f, 0f)))
        assertEquals(listOf("first", "second"), engine.search(listOf(1f, 0f), chunks, topK = 2, threshold = 0.5f).map { it.id })
        assertTrue(engine.search(listOf(1f, 0f), chunks, topK = 0).isEmpty())
        assertEquals(0f, engine.cosineSimilarity(listOf(1f), listOf(1f, 2f)))
        assertEquals(0f, engine.cosineSimilarity(listOf(0f, 0f), listOf(1f, 0f)))
        assertEquals(-1f, engine.cosineSimilarity(listOf(-1f, 0f), listOf(1f, 0f)))
    }

    @Test fun `retrieval intent is independent from persisted vector or memory entities`() {
        assertTrue(!RetrievalIntent().shouldRecall)
        assertTrue(RetrievalIntent(queries = listOf("fixture")).shouldRecall)
        assertTrue(!RagSourcePlan.create(10, 10, 50, 0, 0, 0).shouldRetrieve)
    }

    private fun chunk(id: String, vector: List<Float>) = VectorChunk(id, ChunkSourceType.DOCUMENT,
        "document", content = id, embedding = vector, createdAt = 1)
}
