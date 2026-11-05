package com.example.chatbar.domain.worldbook

import com.example.chatbar.domain.search.ResearchDebugSnapshot
import com.example.chatbar.domain.search.WorldBookResearchSession
import kotlinx.serialization.Serializable

const val WORLD_BOOK_AI_BATCH_SIZE = 5
const val WORLD_BOOK_AI_CREATE_LIMIT = 50

data class WorldBookEntryPlanCandidate(
    val candidateId: String,
    val name: String,
    val keys: List<String>
)

data class WorldBookContentCandidate(
    val targetId: String,
    val name: String,
    val keys: List<String>,
    val content: String
)

data class WorldBookAiRawOutput(
    val key: String,
    val title: String,
    val text: String
)

data class FillConstraintResult(
    val candidates: List<WorldBookContentCandidate>,
    val rejections: List<String>
)

data class CreateContinuationDecision(
    val shouldContinue: Boolean,
    val warning: String = ""
)

data class WorldBookCreateCheckpoint(
    val candidates: List<WorldBookEntryPlanCandidate> = emptyList(),
    val batchNumber: Int = 1,
    val researchDebug: ResearchDebugSnapshot? = null,
    val researchSession: WorldBookResearchSession? = null,
    val rawOutputs: List<WorldBookAiRawOutput> = emptyList(),
    val warning: String = ""
)

data class WorldBookFillCheckpoint(
    val candidates: List<WorldBookContentCandidate> = emptyList(),
    val nextTargetIndex: Int = 0,
    val batchNumber: Int = 1,
    val activeResearchDebug: ResearchDebugSnapshot? = null,
    val researchSession: WorldBookResearchSession? = null,
    val rawOutputs: List<WorldBookAiRawOutput> = emptyList()
)

data class WorldBookCreateResult(
    val candidates: List<WorldBookEntryPlanCandidate>,
    val checkpoint: WorldBookCreateCheckpoint,
    val warning: String = ""
)

data class WorldBookFillResult(
    val candidates: List<WorldBookContentCandidate>,
    val checkpoint: WorldBookFillCheckpoint
)


@Serializable
data class CreateEntryResponse(
    val name: String = "",
    val keys: List<String> = emptyList()
)

@Serializable
data class FillEntryResponse(
    val targetId: String = "",
    val content: String = ""
)
