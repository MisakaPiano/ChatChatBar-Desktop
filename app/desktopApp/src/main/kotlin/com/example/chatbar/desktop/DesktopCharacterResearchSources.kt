package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.rag.ChunkingEngine
import com.example.chatbar.domain.rag.EmbeddingService
import com.example.chatbar.domain.rag.VectorSearchEngine
import com.example.chatbar.domain.search.*

internal typealias DesktopCharacterResearchPreparation = suspend (
    String, CharacterCard, ModelConfig, CharacterResearchOptions, List<CharacterReferenceDocument>,
    ResearchDebugSnapshot?, (ResearchDebugSnapshot) -> Unit, (String, String, String) -> Unit,
    (String) -> Unit, CharacterResearchAiGateway
) -> ResearchBrief?

/** Temporary sources are never added to the card's durable document collection. */
internal class DesktopCharacterResearchSources(
    private val settings: SettingsRepository, private val models: EffectiveModelResolver,
    allowCleartext: () -> Boolean,
) {
    private val backend = MediaWikiSearchBackend()
    private val manual = HttpManualWebPageRetriever()
    private val embedding = EmbeddingService(allowCleartext)
    private val documents = RagCharacterReferenceDocumentRetriever(ChunkingEngine(), embedding, VectorSearchEngine()) {
        models.embeddingModel(settings.getAppSettings())
    }

    suspend fun prepare(request: String, card: CharacterCard, model: ModelConfig, options: CharacterResearchOptions,
        reference: List<CharacterReferenceDocument>, resume: ResearchDebugSnapshot?, checkpoint: (ResearchDebugSnapshot) -> Unit,
        visible: (String, String, String) -> Unit, status: (String) -> Unit, gateway: CharacterResearchAiGateway): ResearchBrief? =
        CharacterResearchEngine(settings::getAppSettings, CharacterResearchPlannerCore(gateway), backend,
            CharacterResearchBriefCore(gateway), documents, manual).research(request, card, model, options, reference,
                resumeFrom = resume, onCheckpoint = checkpoint, onVisibleOutput = visible, onStatus = status)

    suspend fun closeAndDrain() {
        var failure: Throwable? = null
        for (close in listOf<suspend () -> Unit>({ backend.closeAndDrain() }, { manual.closeAndDrain() }, { embedding.closeAndDrain() })) {
            try { close() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
