package com.example.chatbar.domain.model

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.ModelConfig

/** One immutable configuration for selectors, defaults and capability checks. */
data class EffectiveModelConfiguration(
    val settings: AppSettings,
    val chatModels: List<ModelConfig>,
    val auxiliaryTextModels: List<ModelConfig>,
    val multimodalModels: List<ModelConfig>,
    val embeddingModel: EmbeddingConfig?,
) {
    val defaultChatModel: ModelConfig? get() = selectDefaultChatModel(settings, chatModels)
    val defaultImageModel: ModelConfig? get() = selectDefaultImageModel(settings, chatModels)
    fun chatModel(requestedId: String?): ModelConfig? = selectChatModel(requestedId, settings, chatModels)
    fun imageModel(requestedId: String?): ModelConfig? = chatModels.firstOrNull { it.id == requestedId } ?: defaultImageModel
    fun imageDesignModel(requestedId: String?): ModelConfig? =
        if (requestedId == null) defaultImageModel else chatModels.firstOrNull { it.id == requestedId }
    fun status(requestedChatModelId: String? = null): ModelConfigurationStatus = modelConfigurationStatus(
        chatModel(requestedChatModelId), embeddingModel, settings.allowCleartextModelApi)
}
