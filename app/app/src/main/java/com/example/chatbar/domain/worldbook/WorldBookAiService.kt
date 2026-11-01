package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.prompt.withAiTaskRun
import com.example.chatbar.domain.search.CharacterReferenceDocument
import com.example.chatbar.domain.search.CharacterResearchOptions
import com.example.chatbar.domain.search.ResearchDebugSnapshot
import com.example.chatbar.domain.search.WorldBookResearchService
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** Android owns task lifetime; both platforms execute the same batch/repair core. */
@OptIn(ExperimentalSerializationApi::class)
class WorldBookAiService(
    modelResolver: EffectiveModelResolver,
    chatService: StreamingChatService,
    researchService: WorldBookResearchService,
    json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true
        encodeDefaults = true; allowTrailingComma = true }
) {
    private val core = WorldBookAiCore(modelResolver::defaultChatModel,
        AndroidWorldBookAiGateway(chatService), researchService, json)
    suspend fun createEntriesStreaming(
        request: String,
        book: WorldBook,
        modelOverride: ModelConfig? = null,
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        referenceDocument: CharacterReferenceDocument? = null,
        resumeFrom: WorldBookCreateCheckpoint? = null,
        onCheckpoint: (WorldBookCreateCheckpoint) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onRawText: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> }
    ): WorldBookCreateResult = withAiTaskRun(Dispatchers.IO) {
        core.createEntriesStreaming(
            request = request,
            book = book,
            modelOverride = modelOverride,
            researchOptions = researchOptions,
            referenceDocument = referenceDocument,
            resumeFrom = resumeFrom,
            onCheckpoint = onCheckpoint,
            onStatus = onStatus,
            onRawText = onRawText,
            onResearchDebug = onResearchDebug,
            onVisibleOutput = onVisibleOutput
        )
    }

    suspend fun fillEmptyContentsStreaming(
        request: String,
        book: WorldBook,
        targets: List<WorldBookEntry>,
        modelOverride: ModelConfig? = null,
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        referenceDocument: CharacterReferenceDocument? = null,
        resumeFrom: WorldBookFillCheckpoint? = null,
        onCheckpoint: (WorldBookFillCheckpoint) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onRawText: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> }
    ): WorldBookFillResult = withAiTaskRun(Dispatchers.IO) {
        core.fillEmptyContentsStreaming(
            request = request,
            book = book,
            targets = targets,
            modelOverride = modelOverride,
            researchOptions = researchOptions,
            referenceDocument = referenceDocument,
            resumeFrom = resumeFrom,
            onCheckpoint = onCheckpoint,
            onStatus = onStatus,
            onRawText = onRawText,
            onResearchDebug = onResearchDebug,
            onVisibleOutput = onVisibleOutput
        )
    }

    companion object {
        internal fun constrainCreatedEntries(book: WorldBook, completed: List<WorldBookEntryPlanCandidate>,
            rawEntries: List<CreateEntryResponse>, remaining: Int,
            idFactory: () -> String = { UUID.randomUUID().toString() }): List<WorldBookEntryPlanCandidate> =
            WorldBookAiPolicy.constrainCreatedEntries(book, completed, rawEntries, remaining, idFactory)

        internal fun constrainFilledEntries(targets: List<WorldBookEntry>, rawEntries: List<FillEntryResponse>): FillConstraintResult =
            WorldBookAiPolicy.constrainFilledEntries(targets, rawEntries)

        internal fun decideCreateContinuation(responseHasMore: Boolean, newCandidateCount: Int,
            totalCandidateCount: Int): CreateContinuationDecision =
            WorldBookAiPolicy.decideCreateContinuation(responseHasMore, newCandidateCount, totalCandidateCount)

        fun applyCreatedEntries(current: List<WorldBookEntry>, candidates: List<WorldBookEntryPlanCandidate>,
            selectedIds: Set<String>, idFactory: () -> String = { UUID.randomUUID().toString() }): List<WorldBookEntry> =
            WorldBookAiPolicy.applyCreatedEntries(current, candidates, selectedIds, idFactory)

        fun applyFilledContents(current: List<WorldBookEntry>, candidates: List<WorldBookContentCandidate>,
            selectedIds: Set<String>): List<WorldBookEntry> =
            WorldBookAiPolicy.applyFilledContents(current, candidates, selectedIds)
    }
}
