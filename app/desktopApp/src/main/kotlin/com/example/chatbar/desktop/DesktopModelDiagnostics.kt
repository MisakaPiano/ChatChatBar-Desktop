package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.PRESET_MODEL_ID_PREFIX
import java.net.URI

internal enum class DesktopModelSelection { EXPLICIT, AUTOMATIC, STALE_FALLBACK }

internal enum class DesktopCredentialSource { MODEL_KEY, GLOBAL_KEY, NO_AUTH, UNCONFIGURED }

/** Presentation-only evidence. Hydrated credential values never enter this state. */
internal data class DesktopModelDiagnostic(
    val selection: DesktopModelSelection,
    val configuredId: String?,
    val effectiveId: String?,
    val displayName: String?,
    val modelName: String?,
    val baseUrl: String?,
    val templateType: ModelTemplate?,
    val preset: Boolean,
    val catalogProvider: String?,
    val credentialSource: DesktopCredentialSource,
)

internal fun AppSettings.configuredDefaultChatModelId(): String? =
    defaultModelId ?: presetDefaultModelKey?.let { PRESET_MODEL_ID_PREFIX + it }

internal fun desktopModelDiagnostic(
    configuredId: String?,
    effective: ModelConfig?,
    appSettings: AppSettings,
    rawEffective: ModelConfig?,
    catalogProvider: String?,
): DesktopModelDiagnostic {
    val selection = when {
        configuredId == null -> DesktopModelSelection.AUTOMATIC
        effective?.id == configuredId -> DesktopModelSelection.EXPLICIT
        else -> DesktopModelSelection.STALE_FALLBACK
    }
    val ownKey = rawEffective?.apiKey.orEmpty().trim()
    val noAuth = effective?.baseUrl?.let { url ->
        appSettings.allowCleartextModelApi && runCatching {
            URI(url.trim()).scheme.equals("http", ignoreCase = true)
        }.getOrDefault(false)
    } == true
    val source = when {
        effective == null -> DesktopCredentialSource.UNCONFIGURED
        ownKey.isNotEmpty() -> DesktopCredentialSource.MODEL_KEY
        noAuth -> DesktopCredentialSource.NO_AUTH
        appSettings.siliconFlowApiKey.isNotBlank() -> DesktopCredentialSource.GLOBAL_KEY
        else -> DesktopCredentialSource.UNCONFIGURED
    }
    return DesktopModelDiagnostic(
        selection = selection,
        configuredId = configuredId,
        effectiveId = effective?.id,
        displayName = effective?.displayName,
        modelName = effective?.modelName,
        baseUrl = effective?.baseUrl,
        templateType = effective?.templateType,
        preset = effective?.sourcePresetKey != null || effective?.id?.startsWith(PRESET_MODEL_ID_PREFIX) == true,
        catalogProvider = catalogProvider?.takeIf { effective?.sourcePresetKey != null },
        credentialSource = source,
    )
}
