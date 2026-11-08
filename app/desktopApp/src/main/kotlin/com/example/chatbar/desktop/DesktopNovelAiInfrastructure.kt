package com.example.chatbar.desktop

import com.example.chatbar.domain.chat.ImageUnderstandingService
import com.example.chatbar.domain.chat.OpenAiStreamingTransport
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import java.io.InputStream
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

/** One application-scoped catalog/design graph for Studio, chat and image-workspace consumers. */
internal class DesktopNovelAiInfrastructure(
    root: Path,
    openAsset: (String) -> InputStream,
    resolver: EffectiveModelResolver,
    allowCleartextHttp: () -> Boolean,
    private val designerOverride: NovelAiPromptDesigner? = null,
    diagnostics: DesktopTransportDiagnosticsOwner? = null,
    experimentalApiModeEnabled: () -> Boolean = { false },
    operationGate: com.example.chatbar.data.operation.AppDataOperationGate = com.example.chatbar.data.operation.NoOpAppDataOperationGate,
    onCatalogUpdatesCreated: (DesktopCatalogUpdates) -> Unit = {},
    synchronousCatalogAccess: ((() -> Unit) -> Unit) = { it() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val assets = DesktopNovelAiCatalogAssets(root.resolve("auxiliary/novelai"), operationGate, synchronousCatalogAccess, openAsset)
    val tags = DesktopNovelAiTagCatalog(assets)
    private val catalogUpdatesOwner = lazy { DesktopCatalogUpdates(scope, tags).also(onCatalogUpdatesCreated) }
    val catalogUpdates by catalogUpdatesOwner
    private val dictionary = NovelAiPromptWordDictionary.fromSource(DesktopNovelAiDictionary(assets))
    val suggestions = NovelAiTagSuggestionService(tags, dictionary, scope)
    val translations = NovelAiPromptTranslationService(dictionary, tags)
    private val tokenCounter = NovelAiPromptTokenCounter(openAsset)
    private val transport = OpenAiStreamingTransport(experimentalApiModeEnabled, allowCleartextHttp)
    private val text = DesktopNovelAiTextTransport(transport, diagnostics)
    private val understanding = ImageUnderstandingService(resolver, DesktopAuxiliaryImageUnderstanding(transport, diagnostics)::describe)
    private val designer by lazy {
        val loaded = NovelAiCodexCatalogParser(Json { ignoreUnknownKeys = true }).parse(
            openAsset(NOVEL_AI_CODEX_ASSET_PATH).bufferedReader().use { it.readText() })
        check(loaded.fatalError == null && loaded.errors.isEmpty()) { "NovelAI 法典加载失败" }
        NovelAiPromptDesigner(text,
            NovelAiTagResearchService(LlmNovelAiTagSearchPlanner(text), tags, NovelAiCodexSearchEngine(loaded.catalog)),
            NovelAiPromptPostProcessor(loaded.catalog.rewriteRules), { understanding })
    }
    internal fun promptDesignerOnIo(): NovelAiPromptDesigner = designerOverride ?: designer
    suspend fun promptDesigner(): NovelAiPromptDesigner = withContext(Dispatchers.IO) { promptDesignerOnIo() }
    suspend fun countTokens(plan: NovelAiPromptPlan, model: NovelAiImageModel): NovelAiPromptTokenUsage =
        withContext(Dispatchers.IO) { tokenCounter.count(plan, model) }
    suspend fun closeAndDrain() {
        if (catalogUpdatesOwner.isInitialized()) catalogUpdates.closeAndDrain()
        scope.coroutineContext[Job]?.cancelAndJoin()
        transport.closeAndDrain()
    }
}
