package com.example.chatbar.desktop

import androidx.compose.runtime.Composable
import com.example.chatbar.data.local.entity.ModelTemplate

/** Diagnostic labels are deliberately derived from IDs and provenance, never key material. */
@Composable
internal fun DesktopModelEvidence(diagnostic: DesktopModelDiagnostic?, session: Boolean) {
    val t = LocalDesktopUiStrings.current
    if (diagnostic == null) return
    val name = diagnostic.displayName ?: t(DesktopUiText.NOT_CONFIGURED)
    val selection = when (diagnostic.selection) {
        DesktopModelSelection.EXPLICIT ->
            "${t(if (session) DesktopUiText.SESSION_SPECIFIED else DesktopUiText.DEFAULT_CHAT_MODEL)}: $name"
        DesktopModelSelection.AUTOMATIC ->
            "${t(if (session) DesktopUiText.FOLLOWS_GLOBAL_DEFAULT else DesktopUiText.AUTOMATIC_SELECTION)} · ${t(DesktopUiText.CURRENT_EFFECTIVE)}: $name"
        DesktopModelSelection.STALE_FALLBACK ->
            "${t(DesktopUiText.SPECIFIED_UNAVAILABLE)}: ${diagnostic.configuredId} → ${t(DesktopUiText.CURRENT_FALLBACK)}: $name"
    }
    StatusText(selection)
    diagnostic.modelName?.let { StatusText("$it · ${diagnostic.baseUrl.orEmpty()}") }
    if (diagnostic.effectiveId != null) {
        val template = diagnostic.templateType?.let { t(when (it) {
            ModelTemplate.OPENAI -> DesktopUiText.TEMPLATE_OPENAI
            ModelTemplate.CLAUDE -> DesktopUiText.TEMPLATE_CLAUDE
            ModelTemplate.GEMINI -> DesktopUiText.TEMPLATE_GEMINI
            ModelTemplate.CUSTOM -> DesktopUiText.TEMPLATE_CUSTOM
        }) }
        val provenance = t(if (diagnostic.preset) DesktopUiText.PRESET else DesktopUiText.CUSTOM)
        StatusText(listOfNotNull(provenance, diagnostic.catalogProvider, template).joinToString(" · "))
    }
    val credential = t(when (diagnostic.credentialSource) {
        DesktopCredentialSource.MODEL_KEY -> DesktopUiText.MODEL_SPECIFIC_KEY
        DesktopCredentialSource.GLOBAL_KEY -> DesktopUiText.GLOBAL_DEFAULT_KEY
        DesktopCredentialSource.NO_AUTH -> DesktopUiText.NO_AUTH
        DesktopCredentialSource.UNCONFIGURED -> DesktopUiText.NOT_CONFIGURED
    })
    StatusText("${t(DesktopUiText.CREDENTIAL_SOURCE)}: $credential")
}
