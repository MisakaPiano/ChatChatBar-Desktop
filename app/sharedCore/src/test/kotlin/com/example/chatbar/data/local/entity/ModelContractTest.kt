package com.example.chatbar.data.local.entity

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy model payload keeps baseline defaults`() {
        val model = json.decodeFromString(
            ModelConfig.serializer(),
            """{"id":"m1","displayName":"M","baseUrl":"https://example.test/v1","apiKey":"","modelName":"provider/model","createdAt":1}"""
        )

        assertTrue(model.selectableForChat)
        assertFalse(model.isMultimodal)
        assertNull(model.visionModelId)
        assertEquals(ModelTemplate.OPENAI, model.templateType)
        assertEquals(emptyMap(), model.customParams)
        assertNull(model.reasoningEffort)
        assertNull(model.enableThinking)
        assertNull(model.maxOutputTokens)
        assertEquals(OutputTokenParameter.MAX_TOKENS, model.outputTokenParameter)
        assertEquals(FormatPromptPosition.BOTH, model.formatPromptPosition)
        assertFalse(model.supportsJsonMode)
        assertFalse(model.supportsDisableThinking)
        assertNull(model.sourcePresetKey)
        assertNull(model.sourcePresetVersion)
    }

    @Test
    fun `model values and custom parameter variants round trip`() {
        val original = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = "https://example.test/v1",
            apiKey = "fake-test-key",
            modelName = "provider/model",
            selectableForChat = false,
            isMultimodal = true,
            visionModelId = "vision",
            templateType = ModelTemplate.CUSTOM,
            customParams = linkedMapOf(
                "temperature" to ParamValue.NumberValue(0.7),
                "enable_thinking" to ParamValue.BooleanValue(true),
                "reasoning_effort" to ParamValue.StringValue("high")
            ),
            reasoningEffort = "medium",
            enableThinking = false,
            maxOutputTokens = 4096,
            outputTokenParameter = OutputTokenParameter.MAX_COMPLETION_TOKENS,
            formatPromptPosition = FormatPromptPosition.END,
            supportsJsonMode = true,
            supportsDisableThinking = true,
            sourcePresetKey = "preset-key",
            sourcePresetVersion = 3,
            createdAt = 123L
        )

        val encoded = json.encodeToString(ModelConfig.serializer(), original)
        val decoded = json.decodeFromString(ModelConfig.serializer(), encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("MAX_COMPLETION_TOKENS"))
        assertTrue(encoded.contains("preset-key"))
        assertTrue(encoded.contains("number"))
        assertTrue(encoded.contains("boolean"))
        assertTrue(encoded.contains("string"))
    }

    @Test
    fun `embedding payload defaults dimensions and round trips credentials`() {
        val legacy = json.decodeFromString(
            EmbeddingConfig.serializer(),
            """{"id":"embedding","displayName":"Embedding","baseUrl":"https://example.test/v1","apiKey":"fake-test-key","modelName":"provider/embedding"}"""
        )
        assertEquals(1536, legacy.dimensions)

        val configured = legacy.copy(dimensions = 3072)
        assertEquals(
            configured,
            json.decodeFromString(
                EmbeddingConfig.serializer(),
                json.encodeToString(EmbeddingConfig.serializer(), configured)
            )
        )
    }

    @Test
    fun `legacy preset model catalog keeps baseline defaults`() {
        val catalog = json.decodeFromString(
            PresetModelCatalog.serializer(),
            """{"chatModels":[{"modelKey":"chat","displayName":"Chat","modelName":"provider/model"}],"embeddingModel":{"modelKey":"embedding","displayName":"Embedding","modelName":"provider/embedding"}}"""
        )

        assertEquals(1, catalog.schemaVersion)
        assertEquals("SILICONFLOW", catalog.provider)
        assertEquals("https://api.siliconflow.cn/v1", catalog.baseUrl)
        val chat = catalog.chatModels.single()
        assertTrue(chat.selectableForChat)
        assertFalse(chat.isMultimodal)
        assertNull(chat.visionModelKey)
        assertEquals(ModelTemplate.OPENAI, chat.templateType)
        assertEquals(emptyMap(), chat.customParams)
        assertNull(chat.reasoningEffort)
        assertNull(chat.enableThinking)
        assertNull(chat.maxOutputTokens)
        assertEquals(1536, requireNotNull(catalog.embeddingModel).dimensions)
    }
}
