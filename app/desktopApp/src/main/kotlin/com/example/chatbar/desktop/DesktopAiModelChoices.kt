package com.example.chatbar.desktop

import androidx.compose.runtime.Composable
import com.example.chatbar.domain.model.EffectiveModelConfiguration

internal enum class DesktopReferenceImageRoute { NO_MODEL, DIRECT, LINKED, UNAVAILABLE }

/** UI-only projection. Neither the configuration nor hydrated credentials leave this function. */
internal data class DesktopAiModelChoices(
    val options: List<DesktopCardDesignOption>,
    val defaultName: String,
    val referenceRoute: DesktopReferenceImageRoute,
    val referenceModelName: String = "",
)

internal fun desktopAiModelChoices(configuration: EffectiveModelConfiguration, selectedId: String?): DesktopAiModelChoices {
    val selected = if (selectedId == null) configuration.defaultChatModel else configuration.chatModels.firstOrNull { it.id == selectedId }
    // Match ImageUnderstandingService's exact linked-model and nonblank-key requirements.
    val vision = selected?.visionModelId?.let { id -> configuration.auxiliaryTextModels.firstOrNull { it.id == id } }
        ?.takeIf { it.isMultimodal && it.apiKey.isNotBlank() }
    return DesktopAiModelChoices(
        configuration.chatModels.map { DesktopCardDesignOption(it.id, it.desktopAccountLabel()) },
        configuration.defaultChatModel?.desktopAccountLabel().orEmpty(),
        when { selected == null -> DesktopReferenceImageRoute.NO_MODEL
            selected.isMultimodal -> DesktopReferenceImageRoute.DIRECT
            vision != null -> DesktopReferenceImageRoute.LINKED
            else -> DesktopReferenceImageRoute.UNAVAILABLE },
        vision?.desktopAccountLabel().orEmpty(),
    )
}

@Composable
internal fun DesktopAiModelChoicesFailure(failed: Boolean, retry: () -> Unit) {
    if (!failed) return
    val t = LocalDesktopUiStrings.current
    StatusText(t(DesktopUiText.MODEL_CHOICES_READ_FAILED), DesktopBootstrapColors.warning)
    BootstrapButton(t(DesktopUiText.MODEL_CHOICES_RETRY), secondary = true, onClick = retry)
}
