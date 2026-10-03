package com.example.chatbar.domain.card

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.data.repository.ModelRepository
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTemplateTransferSharedTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun sharedPackageRoundTripValidationAndCredentialExclusion() = runTest {
        val root = Files.createTempDirectory("model-template-shared")
        val models = ModelRepository(JsonFileStorage(root))
        val service = ModelTemplateTransferService(models, json)
        val source = ModelConfig(id = "source", displayName = "Shared", baseUrl = "https://example.com/v1",
            apiKey = "FAKE-SECRET", modelName = "model", isMultimodal = true,
            visionModelId = "local-vision",
            templateType = ModelTemplate.OPENAI, customParams = mapOf("temperature" to ParamValue.NumberValue(0.5)),
            reasoningEffort = "high", enableThinking = true, maxOutputTokens = 2048,
            formatPromptPosition = FormatPromptPosition.END, createdAt = 1L)
        models.saveModel(source)

        val raw = service.exportJson(source.id)
        assertEquals(setOf("schemaVersion", "exportedAt", "displayName", "baseUrl", "modelName",
            "isMultimodal", "templateType", "customParams", "reasoningEffort", "enableThinking",
            "maxOutputTokens", "formatPromptPosition"), json.parseToJsonElement(raw).jsonObject.keys)
        assertFalse(raw.contains("FAKE-SECRET"))
        assertFalse(raw.contains("visionModelId"))
        assertFalse(raw.contains("\"id\""))
        val decoded = service.decode(raw)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(source.displayName, decoded.displayName)
        assertEquals(source.customParams, decoded.customParams)
        assertEquals(source.formatPromptPosition, decoded.formatPromptPosition)
        assertFailsWith<IllegalArgumentException> { service.decode(raw.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
        assertFailsWith<IllegalArgumentException> { decoded.copy(displayName = " ").validateForImport() }
        assertFailsWith<IllegalArgumentException> { decoded.copy(modelName = " ").validateForImport() }

        val imported = service.importNew(decoded)
        assertNotEquals(source.id, imported.id)
        assertEquals("Shared (Imported Template)", imported.displayName)
        assertEquals("", imported.apiKey)
        assertNull(imported.visionModelId)
        assertEquals(source.baseUrl, imported.baseUrl)
        assertEquals(source.modelName, imported.modelName)
        assertEquals(source.isMultimodal, imported.isMultimodal)
        assertEquals(source.templateType, imported.templateType)
        assertNotEquals(source.createdAt, imported.createdAt)
        assertEquals(source.customParams, imported.customParams)
        assertEquals(source.reasoningEffort, imported.reasoningEffort)
        assertEquals(source.enableThinking, imported.enableThinking)
        assertEquals(source.maxOutputTokens, imported.maxOutputTokens)
        assertEquals(source.formatPromptPosition, imported.formatPromptPosition)
        assertTrue(models.getAllModels().any { it.id == imported.id })
    }
}
