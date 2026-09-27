package com.example.chatbar.desktop.security

sealed interface DesktopCredentialKey {
    data object SiliconFlowApiKey : DesktopCredentialKey

    data class ModelApiKey(val modelId: String) : DesktopCredentialKey {
        init {
            require(modelId.isNotBlank()) { "Model credential ID must not be blank" }
        }
    }
}

interface DesktopSecretStore {
    /** Returns null only when no secret exists for this logical key. */
    fun load(key: DesktopCredentialKey): String?

    fun save(key: DesktopCredentialKey, secret: String)

    fun delete(key: DesktopCredentialKey)
}

enum class DesktopSecretStoreFailureKind {
    ROOT_UNAVAILABLE,
    STORAGE_FAILURE,
    MALFORMED_ENVELOPE,
    UNSUPPORTED_ENVELOPE_VERSION,
    PROTECTION_FAILURE,
    UNSUPPORTED_PLATFORM,
}

class DesktopSecretStoreException(
    val kind: DesktopSecretStoreFailureKind,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun DesktopCredentialKey.canonicalValue(): String = when (this) {
    DesktopCredentialKey.SiliconFlowApiKey -> "ccb-desktop-credential/v1/global/silicon-flow-api-key"
    is DesktopCredentialKey.ModelApiKey ->
        "ccb-desktop-credential/v1/model-api-key/${modelId.length}:$modelId"
}
