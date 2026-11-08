package com.example.chatbar.domain.model

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PRESET_MODEL_ID_PREFIX
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.PresetEmbeddingModel
import com.example.chatbar.data.local.entity.PresetModelCatalog
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EffectiveModelResolverTest {
    private lateinit var root: Path
    private lateinit var models: ModelRepository
    private lateinit var settings: SettingsRepository
    private lateinit var resolver: EffectiveModelResolver

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("effective-model-resolver-")
        val storage = JsonFileStorage(root)
        models = ModelRepository(storage)
        settings = SettingsRepository(storage)
        resolver = EffectiveModelResolver(models, settings, catalogSource())
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `repository chat models take precedence and requested then default then first fallback is preserved`() = runTest {
        val first = model("first", "Alpha", apiKey = "first-key")
        val default = model("default", "Beta", apiKey = "default-key")
        models.saveModel(default)
        models.saveModel(first)
        val appSettings = AppSettings(defaultModelId = default.id, siliconFlowApiKey = "global-key")

        assertEquals(listOf("first", "default"), resolver.availableChatModels(appSettings).map(ModelConfig::id))
        assertEquals(first, resolver.resolveChatModel(first.id, appSettings))
        assertEquals(default, resolver.resolveChatModel("missing", appSettings))
        assertEquals(first, resolver.defaultChatModel(appSettings.copy(defaultModelId = "missing")))
    }

    @Test
    fun `preset fallback filters placeholders and keeps logical vision relationship version and provider capabilities`() = runTest {
        val appSettings = AppSettings(
            presetDefaultModelKey = "chat",
            siliconFlowApiKey = " global-key "
        )

        val available = resolver.availableChatModels(appSettings)
        val chat = assertNotNull(resolver.resolveChatModel(null, appSettings))

        assertEquals(listOf("preset:chat"), available.map(ModelConfig::id))
        assertEquals(PRESET_MODEL_ID_PREFIX + "chat", chat.id)
        assertEquals(PRESET_MODEL_ID_PREFIX + "vision", chat.visionModelId)
        assertEquals("global-key", chat.apiKey)
        assertEquals(7, chat.sourcePresetVersion)
        assertTrue(chat.supportsJsonMode)
        assertTrue(chat.supportsDisableThinking)
    }

    @Test
    fun `image format repair and auxiliary resolution retain distinct fallback contracts`() = runTest {
        val chat = model("chat", "Chat", apiKey = "chat-key")
        val image = model("image", "Image", apiKey = "image-key")
        val hidden = model("hidden", "Hidden", apiKey = "hidden-key", selectableForChat = false)
        models.saveModel(chat)
        models.saveModel(image)
        models.saveModel(hidden)
        val appSettings = AppSettings(defaultModelId = chat.id, defaultImageModelId = image.id)

        assertEquals(image, resolver.resolveImageModel("missing", appSettings))
        assertEquals(chat, resolver.resolveImageModel(null, appSettings.copy(defaultImageModelId = null)))
        assertEquals(chat, resolver.resolveFormatRepairModel("", appSettings))
        assertNull(resolver.resolveFormatRepairModel("missing", appSettings))
        assertEquals(hidden, resolver.resolveAuxiliaryTextModelExact(hidden.id, appSettings))
        assertNull(resolver.resolveAuxiliaryTextModelExact("missing", appSettings))
        assertEquals("preset:hidden", resolver.auxiliaryChatModel("preset:hidden", appSettings)?.id)
    }

    @Test
    fun `repository embedding takes precedence and preset embedding is the fallback`() = runTest {
        val appSettings = AppSettings(siliconFlowApiKey = "global-key")
        val preset = resolver.embeddingModel(appSettings)
        assertEquals("preset:embedding", preset?.id)
        assertEquals("global-key", preset?.apiKey)

        val repositoryEmbedding = EmbeddingConfig(
            id = "ignored-on-save",
            displayName = "Repository Embedding",
            baseUrl = "https://repository.test/v1",
            apiKey = "repository-key",
            modelName = "provider/repository-embedding"
        )
        models.saveEmbeddingModel(repositoryEmbedding)

        assertEquals(repositoryEmbedding.copy(id = "default"), resolver.embeddingModel(appSettings))
    }

    @Test
    fun `effective authentication preserves own key https inheritance and opted-in cleartext no-inheritance`() {
        val httpsSettings = AppSettings(siliconFlowApiKey = " global-key ", allowCleartextModelApi = true)
        assertEquals("own-key", resolveEffectiveModelApiKey(" own-key ", "https://example.test/v1", httpsSettings))
        assertEquals("global-key", resolveEffectiveModelApiKey("", "https://example.test/v1", httpsSettings))
        assertEquals("", resolveEffectiveModelApiKey("", "http://127.0.0.1:8080/v1", httpsSettings))
        assertTrue(isModelAuthenticationConfigured("http://127.0.0.1:8080/v1", "", true))
        assertFalse(isModelAuthenticationConfigured("http://127.0.0.1:8080/v1", "", false))
    }

    @Test
    fun `configuration status keeps baseline errors and warnings`() {
        assertEquals(
            ModelConfigurationStatus(
                isUsable = false,
                errors = listOf("未配置可用默认对话模型"),
                warnings = listOf("向量模型未配置，RAG 将不可用")
            ),
            modelConfigurationStatus(default = null, embedding = null)
        )
        assertEquals(
            listOf("默认对话模型/API Key 未配置"),
            modelConfigurationStatus(default = model("chat", "Chat", apiKey = ""), embedding = null).errors
        )
    }

    @Test
    fun `live configuration observes model defaults capability and embedding changes while captured configuration stays frozen`() = runBlocking {
        settings.saveAppSettings(AppSettings(defaultModelId = "first", siliconFlowApiKey = "synthetic-global"))
        val first = model("first", "First", apiKey = "")
        models.saveModel(first)
        val observed = MutableStateFlow<EffectiveModelConfiguration?>(null)
        val observer = launch(Dispatchers.Default) { resolver.observeConfiguration().collect { observed.value = it } }
        suspend fun await(predicate: (EffectiveModelConfiguration) -> Boolean) =
            withTimeout(5000) { observed.filterNotNull().first(predicate) }
        try {
            val frozen = await { it.defaultChatModel?.id == first.id }
            assertEquals("synthetic-global", frozen.defaultChatModel?.apiKey)
            val updated = first.copy(displayName = "First edited", modelName = "changed", isMultimodal = true)
            models.saveModel(updated)
            val changed = await { it.multimodalModels.any { m -> m.id == first.id } }
            assertEquals("changed", changed.defaultChatModel?.modelName)
            val hidden = model("hidden", "Hidden vision", apiKey = "own-key", selectableForChat = false).copy(isMultimodal = true)
            models.saveModel(hidden)
            val withHidden = await { it.auxiliaryTextModels.any { m -> m.id == hidden.id } }
            assertTrue(withHidden.chatModels.none { it.id == hidden.id })
            assertTrue(withHidden.multimodalModels.any { it.id == hidden.id })
            val second = model("second", "Second", apiKey = "second-key")
            models.saveModel(second)
            settings.updateAppSettings { it.copy(defaultModelId = second.id, defaultImageModelId = first.id) }
            val selected = await { it.defaultChatModel?.id == second.id }
            assertEquals(first.id, selected.imageDesignModel(null)?.id)
            assertNull(selected.imageDesignModel("removed-id"))
            assertEquals(first.id, selected.imageModel("removed-id")?.id)
            models.saveEmbeddingModel(EmbeddingConfig(id = "ignored", displayName = "Embedding", baseUrl = "https://embedding.invalid/v1", apiKey = "embed-key", modelName = "embed"))
            await { it.embeddingModel?.modelName == "embed" }
            models.deleteModel(first.id)
            val removed = await { it.auxiliaryTextModels.none { m -> m.id == first.id } }
            assertNull(removed.imageDesignModel(first.id))
            assertEquals(second.id, removed.defaultImageModel?.id)
            assertEquals("provider/first", frozen.defaultChatModel?.modelName)
            assertFalse(frozen.defaultChatModel!!.isMultimodal)
            assertEquals("first", frozen.settings.defaultModelId)
        } finally { observer.cancelAndJoin() }
    }

    private fun catalogSource(): PresetModelCatalogSource = object : PresetModelCatalogSource {
        override val catalog = PresetModelCatalog(
            provider = "SILICONFLOW",
            baseUrl = "https://preset.test/v1",
            chatModels = listOf(
                PresetChatModel(
                    modelKey = "chat",
                    displayName = "Preset Chat",
                    modelName = "provider/chat",
                    visionModelKey = "vision"
                ),
                PresetChatModel(
                    modelKey = "vision",
                    displayName = "Preset Vision",
                    modelName = "provider/vision",
                    selectableForChat = false,
                    isMultimodal = true
                ),
                PresetChatModel(
                    modelKey = "hidden",
                    displayName = "Preset Hidden",
                    modelName = "provider/hidden",
                    selectableForChat = false
                ),
                PresetChatModel(
                    modelKey = "blank",
                    displayName = "Blank",
                    modelName = ""
                ),
                PresetChatModel(
                    modelKey = "todo",
                    displayName = "TODO",
                    modelName = "TODO_FILL_MODEL_ID"
                )
            ),
            embeddingModel = PresetEmbeddingModel(
                modelKey = "embedding",
                displayName = "Preset Embedding",
                modelName = "provider/embedding"
            )
        )
        override val modelCatalogVersion: Int = 7
    }

    private fun model(
        id: String,
        displayName: String,
        apiKey: String,
        selectableForChat: Boolean = true
    ) = ModelConfig(
        id = id,
        displayName = displayName,
        baseUrl = "https://repository.test/v1",
        apiKey = apiKey,
        modelName = "provider/$id",
        selectableForChat = selectableForChat,
        createdAt = 1L
    )
}
