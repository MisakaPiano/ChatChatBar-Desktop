package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry

interface WorldBookResearchPort {
    suspend fun research(
        request: String,
        book: WorldBook,
        targets: List<WorldBookEntry>,
        model: ModelConfig,
        options: CharacterResearchOptions,
        referenceDocument: CharacterReferenceDocument?,
        session: WorldBookResearchSession? = null,
        resumeFrom: ResearchDebugSnapshot? = null,
        onSession: (WorldBookResearchSession) -> Unit = {},
        onDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> },
        onStatus: (String) -> Unit = {}
    ): WorldBookResearchResult
}
