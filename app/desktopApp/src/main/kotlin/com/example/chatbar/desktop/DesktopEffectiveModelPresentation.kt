package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.model.EffectiveModelResolver

/** Read-only identities resolved by the runtime authority; no credentials or fallback policy are stored here. */
internal data class DesktopModelIdentity(val id: String, val name: String)

internal data class DesktopEffectiveModelPresentation(
    val chat: DesktopModelIdentity? = null,
    val image: DesktopModelIdentity? = null,
    val configuredImageId: String? = null,
) {
    val inheritedImageLabel: String get() = "跟随全局生图辅助默认 · ${image?.name ?: "未配置"}"
    val globalImageFallbackLabel: String get() = "跟随默认对话模型 · ${image?.name ?: "未配置"}"
    val unavailableImageOverride: String? get() = configuredImageId
        ?.takeIf { it != image?.id }
        ?.let { "已配置生图辅助模型不可用 · $it；当前使用 ${image?.name ?: "未配置"}" }
}

internal suspend fun desktopEffectiveModels(resolver: EffectiveModelResolver, app: AppSettings): DesktopEffectiveModelPresentation =
    DesktopEffectiveModelPresentation(
        chat = resolver.defaultChatModel(app).toIdentity(),
        image = resolver.defaultImageModel(app).toIdentity(),
        configuredImageId = app.defaultImageModelId,
    )

private fun ModelConfig?.toIdentity() = this?.let { DesktopModelIdentity(it.id, it.displayName) }

internal fun desktopDesignModelChoiceLabel(id: String?, models: List<DesktopPrimaryChoice>, effective: String): String =
    if (id == null) "跟随生图辅助默认 · $effective"
    else models.firstOrNull { it.id == id }?.label ?: "已配置模型不可用 · $id"
