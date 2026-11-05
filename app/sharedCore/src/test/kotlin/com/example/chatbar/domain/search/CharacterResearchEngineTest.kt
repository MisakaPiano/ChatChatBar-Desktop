package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CharacterResearchEngineTest {
    private val card = CharacterCard("synthetic-card", "合成卡", createdAt = 1, updatedAt = 1)
    private val model = ModelConfig("synthetic-model", "合成模型", "https://example.test/v1", "fixture-key", "synthetic", createdAt = 1)
    private val noPlanner = object : CharacterResearchPlanProvider {
        override suspend fun plan(userInput: String, currentCard: CharacterCard, modelConfig: ModelConfig,
            maxQueries: Int, onStatus: (String) -> Unit, onRawText: (String) -> Unit): CharacterResearchPlanResult =
            error("No search planning is allowed for manual/document-only paths")
    }
    private val noBackend = object : SearchBackend {
        override suspend fun search(query: SearchBackendQuery): List<SearchHit> = error("No hidden search")
        override suspend fun extract(urls: List<String>, maxPages: Int): List<SearchExtract> = error("No hidden extract")
    }
    private val noSummarizer = object : ResearchBriefSummarizer {
        override suspend fun summarize(request: String, currentCard: CharacterCard, plan: CharacterResearchPlan,
            sources: List<ResearchSource>, modelConfig: ModelConfig, onStatus: (String) -> Unit,
            onRawText: (String) -> Unit): ResearchBriefResult = error("No summary without source")
    }

    @Test fun `selected document missing retriever and failed manual sources refuse instead of returning no research`() = runBlocking {
        val missingDocument = CharacterResearchEngine({ AppSettings() }, noPlanner, noBackend, noSummarizer)
        assertFailsWith<IllegalStateException> {
            missingDocument.research("合成要求", card, model,
                CharacterResearchOptions(CharacterResearchSourceMode.NONE),
                referenceDocuments = listOf(CharacterReferenceDocument("synthetic.txt", "合成参考正文")))
        }
        var calls = 0
        val manual = object : ManualWebPageRetriever {
            override suspend fun retrieve(urls: List<String>, onStatus: (String) -> Unit): ManualWebPageRetrievalResult {
                calls++; assertEquals(listOf("https://example.test/source"), urls)
                return ManualWebPageRetrievalResult(failures = listOf(ManualWebPageFailure(urls.single(), "synthetic unavailable")))
            }
        }
        val failedManual = CharacterResearchEngine({ AppSettings() }, noPlanner, noBackend, noSummarizer,
            manualWebPageRetriever = manual)
        assertFailsWith<IllegalStateException> {
            failedManual.research("合成要求", card, model,
                CharacterResearchOptions(CharacterResearchSourceMode.MANUAL_URLS, listOf("https://example.test/source")))
        }
        assertEquals(1, calls)
    }

    @Test fun `completed prepared brief is reused without reading sources again and checkpoint preserves it`() = runBlocking {
        val brief = ResearchBrief(facts = listOf("合成已整理事实"), notes = listOf("合成来源说明"))
        val plan = CharacterResearchPlan(true, listOf(CharacterResearchQuery("https://example.test/source")))
        var checkpoint: ResearchDebugSnapshot? = null
        val engine = CharacterResearchEngine({ AppSettings() }, noPlanner, noBackend, noSummarizer)
        val result = engine.research("合成要求", card, model,
            CharacterResearchOptions(CharacterResearchSourceMode.MANUAL_URLS, listOf("https://example.test/source")),
            resumeFrom = ResearchDebugSnapshot(plan = plan, brief = brief), onCheckpoint = { checkpoint = it })
        assertEquals(brief, result); assertEquals(brief, checkpoint?.brief)
        assertEquals(plan, checkpoint?.plan)
    }
}
