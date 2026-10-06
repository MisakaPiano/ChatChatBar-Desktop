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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val assets = DesktopNovelAiCatalogAssets(root.resolve("auxiliary/novelai"), openAsset)
    val tags = DesktopNovelAiTagCatalog(assets)
    private val dictionary = NovelAiPromptWordDictionary.fromSource(DesktopNovelAiDictionary(assets))
    val suggestions = NovelAiTagSuggestionService(tags, dictionary, scope)
    val translations = NovelAiPromptTranslationService(dictionary, tags)
    private val tokenCounter = NovelAiPromptTokenCounter(openAsset)
    private val transport = OpenAiStreamingTransport(allowCleartextHttp)
    private val text = DesktopNovelAiTextTransport(transport)
    private val understanding = ImageUnderstandingService(resolver, DesktopAuxiliaryImageUnderstanding(transport)::describe)
    private val designer by lazy {
        val loaded = NovelAiCodexCatalogParser(Json { ignoreUnknownKeys = true }).parse(
            openAsset(NOVEL_AI_CODEX_ASSET_PATH).bufferedReader().use { it.readText() })
        check(loaded.fatalError == null && loaded.errors.isEmpty()) { "NovelAI 法典加载失败" }
        NovelAiPromptDesigner(text,
            NovelAiTagResearchService(LlmNovelAiTagSearchPlanner(text), tags, NovelAiCodexSearchEngine(loaded.catalog)),
            NovelAiPromptPostProcessor(loaded.catalog.rewriteRules), { understanding })
    }
    suspend fun promptDesigner(): NovelAiPromptDesigner = withContext(Dispatchers.IO) { designer }
    suspend fun countTokens(plan: NovelAiPromptPlan, model: NovelAiImageModel): NovelAiPromptTokenUsage =
        withContext(Dispatchers.IO) { tokenCounter.count(plan, model) }
    suspend fun closeAndDrain() { scope.coroutineContext[Job]?.cancelAndJoin() }
}
