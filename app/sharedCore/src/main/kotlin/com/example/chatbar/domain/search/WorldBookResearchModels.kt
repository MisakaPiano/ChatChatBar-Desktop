package com.example.chatbar.domain.search

data class WorldBookResearchSession(
    val preparedDocument: PreparedReferenceDocumentIndex? = null,
    val manualSources: List<ResearchSource> = emptyList(),
    val encyclopediaSourcesByQuery: Map<String, List<ResearchSource>> = emptyMap(),
    val summarizedBriefs: Map<String, ResearchBrief> = emptyMap()
)

data class WorldBookResearchResult(
    val brief: ResearchBrief? = null,
    val debug: ResearchDebugSnapshot = ResearchDebugSnapshot(),
    val session: WorldBookResearchSession = WorldBookResearchSession()
)
