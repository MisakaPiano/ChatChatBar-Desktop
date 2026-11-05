package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.prompt.*
import com.example.chatbar.domain.rag.*
import com.example.chatbar.domain.worldbook.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WorldBookResearchEngineTest {
    private val model = ModelConfig("model", "合成", "https://example.test/v1", "fixture", "synthetic", createdAt = 1)
    private val book = WorldBook("book", "合成世界书")
    private val noBackend = object : SearchBackend {
        override suspend fun search(query: SearchBackendQuery): List<SearchHit> = error("Unexpected search")
        override suspend fun extract(urls: List<String>, maxPages: Int): List<SearchExtract> = error("Unexpected extract")
    }

    @Test fun `manual source session is fetched once and completed research is reused without extra calls`() = runBlocking {
        val embedding = EmbeddingService()
        try {
            var fetched = 0; var summarized = 0
            val manual = object : ManualWebPageRetriever {
                override suspend fun retrieve(urls: List<String>, onStatus: (String) -> Unit): ManualWebPageRetrievalResult {
                    fetched++; assertEquals(listOf("https://example.test/source"), urls)
                    return ManualWebPageRetrievalResult(hits = listOf(SearchHit("合成来源", urls.single(), "合成城市建立于山谷。")))
                }
            }
            val gateway = WorldBookAiGateway { op, stage, _, actual, delta, _ ->
                assertEquals(WorldBookAiOperation.BRIEF, op); assertEquals(WorldBookAiStage.SUMMARIZE, stage)
                assertEquals(model, actual); summarized++
                """{"facts":["合成城市建立于山谷。"],"notes":[]}""".also(delta)
            }
            val engine = WorldBookResearchEngine({ AppSettings() }, gateway, noBackend,
                RagCharacterReferenceDocumentRetriever(ChunkingEngine(), embedding, VectorSearchEngine()) { null }, manual)
            val options = CharacterResearchOptions(CharacterResearchSourceMode.MANUAL_URLS, listOf("https://example.test/source"))
            val first = engine.research("合成要求", book, emptyList(), model, options, null)
            assertNotNull(first.brief); assertEquals(1, fetched); assertEquals(1, summarized)
            val reused = engine.research("合成要求", book, emptyList(), model, options, null,
                session = first.session, resumeFrom = first.debug)
            assertEquals(first.brief, reused.brief); assertEquals(1, fetched); assertEquals(1, summarized)
            val newTarget = listOf(WorldBookEntry("new", "新主题"))
            engine.research("合成要求", book, newTarget, model, options, null, session = first.session)
            assertEquals(1, fetched); assertEquals(2, summarized)
            assertTrue(book.entries.isEmpty())
        } finally { embedding.closeAndDrain() }
    }

    @Test fun `research refusal is terminal and cannot become fallback source success`() = runBlocking {
        val embedding = EmbeddingService()
        try {
            val refusal = AiTaskRefusalException(AiTaskFailureKind.REFUSAL)
            var searches = 0
            val backend = object : SearchBackend {
                override suspend fun search(query: SearchBackendQuery): List<SearchHit> { searches++; return emptyList() }
                override suspend fun extract(urls: List<String>, maxPages: Int): List<SearchExtract> = error("Unexpected extract")
            }
            val engine = WorldBookResearchEngine({ AppSettings() }, WorldBookAiGateway { _, _, _, _, _, _ -> throw refusal }, backend,
                RagCharacterReferenceDocumentRetriever(ChunkingEngine(), embedding, VectorSearchEngine()) { null },
                object : ManualWebPageRetriever {
                    override suspend fun retrieve(urls: List<String>, onStatus: (String) -> Unit) = error("Unexpected manual source")
                })
            val actual = runCatching { engine.research("合成要求", book, emptyList(), model,
                CharacterResearchOptions(CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH), null) }.exceptionOrNull()
            assertSame(refusal, actual); assertEquals(0, searches)
        } finally { embedding.closeAndDrain() }
    }
}
