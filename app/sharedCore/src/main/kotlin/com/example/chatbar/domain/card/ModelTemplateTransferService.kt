package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.data.repository.ModelRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ModelTemplatePackage(
    val schemaVersion: Int = MODEL_TEMPLATE_PACKAGE_SCHEMA_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val isMultimodal: Boolean,
    val templateType: ModelTemplate,
    val customParams: Map<String, ParamValue>,
    val reasoningEffort: String? = null,
    val enableThinking: Boolean? = null,
    val maxOutputTokens: Int? = null,
    val formatPromptPosition: FormatPromptPosition = FormatPromptPosition.BOTH
)

const val MODEL_TEMPLATE_PACKAGE_SCHEMA_VERSION = 1

fun ModelTemplatePackage.validateForImport() {
    require(schemaVersion == MODEL_TEMPLATE_PACKAGE_SCHEMA_VERSION) {
        "不支持的模型模板 schemaVersion：$schemaVersion"
    }
    require(displayName.isNotBlank()) { "模型模板名称不能为空" }
    require(modelName.isNotBlank()) { "模型名称不能为空" }
}

/** Opt-in Desktop evidence survives a cancelled dispatcher return after a durable save. */
class ModelTemplateImportObservation {
    @Volatile var expected: ModelConfig? = null
        internal set
    @Volatile var committed: Boolean = false
        internal set
}

class ModelTemplateTransferService(
    private val repository: ModelRepository,
    private val json: Json
) {
    suspend fun exportJson(id: String): String = withContext(Dispatchers.IO) {
        val model = repository.getModel(id) ?: error("Model not found")
        json.encodeToString(
            ModelTemplatePackage.serializer(),
            ModelTemplatePackage(
                displayName = model.displayName,
                baseUrl = model.baseUrl,
                modelName = model.modelName,
                isMultimodal = model.isMultimodal,
                templateType = model.templateType,
                customParams = model.customParams,
                reasoningEffort = model.reasoningEffort,
                enableThinking = model.enableThinking,
                maxOutputTokens = model.maxOutputTokens,
                formatPromptPosition = model.formatPromptPosition
            )
        )
    }

    fun decode(rawJson: String): ModelTemplatePackage =
        json.decodeFromString(ModelTemplatePackage.serializer(), rawJson).also {
            it.validateForImport()
        }

    suspend fun importNew(packageData: ModelTemplatePackage): ModelConfig = withContext(Dispatchers.IO) {
        packageData.validateForImport()
        modelFromPackage(packageData).also { repository.saveModel(it) }
    }

    /** Desktop-only opt-in; normal Android callers retain importNew's exact behavior. */
    suspend fun importNewObserved(
        packageData: ModelTemplatePackage,
        observation: ModelTemplateImportObservation,
        onPrepared: (ModelConfig) -> Unit = {},
        onCommitted: () -> Unit = {},
    ): ModelConfig = withContext(Dispatchers.IO) {
        packageData.validateForImport()
        val model = modelFromPackage(packageData)
        observation.expected = model
        onPrepared(model)
        repository.saveModelObserved(model) {
            observation.committed = true
            onCommitted()
        }
        model
    }

    private fun modelFromPackage(packageData: ModelTemplatePackage): ModelConfig = ModelConfig(
        id = UUID.randomUUID().toString(),
        displayName = "${packageData.displayName} (Imported Template)",
        baseUrl = packageData.baseUrl,
        apiKey = "",
        modelName = packageData.modelName,
        isMultimodal = packageData.isMultimodal,
        visionModelId = null,
        templateType = packageData.templateType,
        customParams = packageData.customParams,
        reasoningEffort = packageData.reasoningEffort,
        enableThinking = packageData.enableThinking,
        maxOutputTokens = packageData.maxOutputTokens,
        formatPromptPosition = packageData.formatPromptPosition,
        createdAt = System.currentTimeMillis()
    )
}
