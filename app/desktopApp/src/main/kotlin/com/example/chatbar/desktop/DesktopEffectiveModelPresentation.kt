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
    fun inheritedImageLabel(t: DesktopUiStrings): String =
        "${t(DesktopUiText.INHERIT_GLOBAL_IMAGE_MODEL)} · ${image?.name ?: t(DesktopUiText.NOT_CONFIGURED)}"
    fun globalImageFallbackLabel(t: DesktopUiStrings): String =
        "${t(DesktopUiText.FOLLOW_CHAT_DEFAULT_MODEL)} · ${chat?.name ?: t(DesktopUiText.NOT_CONFIGURED)}"
    fun unavailableImageOverride(t: DesktopUiStrings): String? = configuredImageId
        ?.takeIf { it != image?.id }
        ?.let { "${t(DesktopUiText.SPECIFIED_UNAVAILABLE)} · $it; ${t(DesktopUiText.CURRENT_EFFECTIVE)}: ${image?.name ?: t(DesktopUiText.NOT_CONFIGURED)}" }
}

internal suspend fun desktopEffectiveModels(resolver: EffectiveModelResolver, app: AppSettings): DesktopEffectiveModelPresentation =
    DesktopEffectiveModelPresentation(
        chat = resolver.defaultChatModel(app).toIdentity(),
        image = resolver.defaultImageModel(app).toIdentity(),
        configuredImageId = app.defaultImageModelId,
    )

private fun ModelConfig?.toIdentity() = this?.let { DesktopModelIdentity(it.id, it.displayName) }

internal fun desktopDesignModelChoiceLabel(id: String?, models: List<DesktopPrimaryChoice>, effective: String,
    t: DesktopUiStrings): String =
    if (id == null) "${t(DesktopUiText.FOLLOW_IMAGE_DEFAULT_MODEL)} · $effective"
    else models.firstOrNull { it.id == id }?.label ?: "${t(DesktopUiText.SPECIFIED_UNAVAILABLE)} · $id"
