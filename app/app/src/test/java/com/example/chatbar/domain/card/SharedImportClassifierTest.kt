package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.ModelTemplate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImportClassifierTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun androidFacadeClassifiesAndDecodesModelTemplate() {
        val raw = modelTemplateJson()

        val inspected = SharedImportClassifier.inspect(raw.toByteArray())
        assertTrue(inspected is SharedImportInspection.ModelTemplate)
        assertEquals(SharedImportKind.MODEL_TEMPLATE, inspected.kind)
        assertEquals(
            "deepseek-chat",
            (inspected as SharedImportInspection.ModelTemplate).packageData.modelName,
        )

        val decoded = SharedImportClassifier.decodeAs(
            raw.toByteArray(),
            SharedImportKind.MODEL_TEMPLATE,
        )
        assertEquals(SharedImportKind.MODEL_TEMPLATE, decoded.kind)
    }

    @Test
    fun androidModelTemplateFacadeKeepsStrictValidation() {
        val invalid = modelTemplateJson(modelName = "").toByteArray()

        assertEquals(SharedImportKind.UNKNOWN, SharedImportClassifier.inspect(invalid).kind)
        val failure = runCatching {
            SharedImportClassifier.decodeAs(invalid, SharedImportKind.MODEL_TEMPLATE)
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("模型名称不能为空") == true)
    }

    private fun modelTemplateJson(modelName: String = "deepseek-chat"): String = json.encodeToString(
        ModelTemplatePackage.serializer(),
        ModelTemplatePackage(
            displayName = "DeepSeek",
            baseUrl = "https://example.com",
            modelName = modelName,
            isMultimodal = false,
            templateType = ModelTemplate.OPENAI,
            customParams = emptyMap(),
        ),
    )
}
