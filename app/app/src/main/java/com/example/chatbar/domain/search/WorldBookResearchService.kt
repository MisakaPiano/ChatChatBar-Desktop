package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.withAiTaskRun
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

import com.example.chatbar.domain.worldbook.AndroidWorldBookAiGateway
@OptIn(ExperimentalSerializationApi::class)
class WorldBookResearchService(
    settingsProvider: suspend () -> AppSettings,
    chatService: StreamingChatService,
    backend: SearchBackend,
    referenceRetriever: RagCharacterReferenceDocumentRetriever,
    manualRetriever: ManualWebPageRetriever,
    json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true
        encodeDefaults = true; allowTrailingComma = true }
) : WorldBookResearchPort {
    private val engine = WorldBookResearchEngine(settingsProvider,
        AndroidWorldBookAiGateway(chatService), backend, referenceRetriever, manualRetriever, json)
    override suspend fun research(
        request: String,
        book: WorldBook,
        targets: List<WorldBookEntry>,
        model: ModelConfig,
        options: CharacterResearchOptions,
        referenceDocument: CharacterReferenceDocument?,
        session: WorldBookResearchSession?,
        resumeFrom: ResearchDebugSnapshot?,
        onSession: (WorldBookResearchSession) -> Unit,
        onDebug: (ResearchDebugSnapshot) -> Unit,
        onVisibleOutput: (String, String, String) -> Unit,
        onStatus: (String) -> Unit
    ): WorldBookResearchResult = withAiTaskRun(Dispatchers.IO) {
        engine.research(
            request = request,
            book = book,
            targets = targets,
            model = model,
            options = options,
            referenceDocument = referenceDocument,
            session = session,
            resumeFrom = resumeFrom,
            onSession = onSession,
            onDebug = onDebug,
            onVisibleOutput = onVisibleOutput,
            onStatus = onStatus
        )
    }
}
