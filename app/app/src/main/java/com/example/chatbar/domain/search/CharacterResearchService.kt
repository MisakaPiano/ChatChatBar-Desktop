package com.example.chatbar.domain.search

import com.example.chatbar.domain.prompt.withAiTaskRun
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.coroutines.Dispatchers

/** Android task/log ownership delegates unchanged research orchestration to the shared engine. */
class CharacterResearchService(
    settingsProvider: suspend () -> AppSettings,
    planner: CharacterResearchPlanProvider,
    backend: SearchBackend,
    summarizer: ResearchBriefSummarizer,
    referenceDocumentRetriever: CharacterReferenceDocumentRetriever? = null,
    manualWebPageRetriever: ManualWebPageRetriever? = null
) {
    private val engine = CharacterResearchEngine(settingsProvider, planner, backend, summarizer,
        referenceDocumentRetriever, manualWebPageRetriever)
    suspend fun research(
        userInput: String, currentCard: CharacterCard, modelConfig: ModelConfig,
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        referenceDocuments: List<CharacterReferenceDocument> = emptyList(),
        onDebug: (ResearchDebugSnapshot) -> Unit = {}, resumeFrom: ResearchDebugSnapshot? = null,
        onCheckpoint: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> },
        onStatus: (String) -> Unit = {}
    ): ResearchBrief? = withAiTaskRun(Dispatchers.IO) {
        runCatching {
            android.util.Log.d(
                "CharacterEditResume",
                "research entry: mode=${researchOptions.mode.name} " +
                    "resumeFrom=${resumeFrom != null} plan=${resumeFrom?.plan != null} " +
                    "sources=${resumeFrom?.sources?.size} brief=${resumeFrom?.brief?.hasContent()}"
            )
        }
        engine.research(userInput, currentCard, modelConfig, researchOptions, referenceDocuments,
            onDebug, resumeFrom, onCheckpoint, onVisibleOutput, onStatus)
    }
}
