package com.example.chatbar.desktop.security

sealed interface DesktopCredentialKey {
    data object NovelAiToken : DesktopCredentialKey
    data object FishAudioApiKey : DesktopCredentialKey
    data object SiliconFlowApiKey : DesktopCredentialKey

    data class ModelApiKey(val modelId: String) : DesktopCredentialKey {
        init {
            require(modelId.isNotBlank()) { "Model credential ID must not be blank" }
        }
    }

    data class LegacyEmbeddingApiKey(val embeddingId: String) : DesktopCredentialKey {
        init {
            require(embeddingId.isNotBlank()) { "Embedding credential ID must not be blank" }
        }
    }

    data object SingletonEmbeddingApiKey : DesktopCredentialKey

    /** Imported session custody only; no Community runtime or refresh task consumes this key. */
    data object DormantCommunitySession : DesktopCredentialKey
    data class AccountProfiles(val service: DesktopAccountService) : DesktopCredentialKey
    data class AccountProfilesRequired(val service: DesktopAccountService) : DesktopCredentialKey
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
    DesktopCredentialKey.NovelAiToken -> "ccb-desktop-credential/v1/global/novelai-token"
    DesktopCredentialKey.FishAudioApiKey -> "ccb-desktop-credential/v1/global/fish-audio-api-key"
    DesktopCredentialKey.SiliconFlowApiKey -> "ccb-desktop-credential/v1/global/silicon-flow-api-key"
    is DesktopCredentialKey.ModelApiKey ->
        "ccb-desktop-credential/v1/model-api-key/${modelId.length}:$modelId"
    is DesktopCredentialKey.LegacyEmbeddingApiKey ->
        "ccb-desktop-credential/v1/legacy-embedding-api-key/${embeddingId.length}:$embeddingId"
    DesktopCredentialKey.SingletonEmbeddingApiKey ->
        "ccb-desktop-credential/v1/singleton-embedding-api-key"
    DesktopCredentialKey.DormantCommunitySession ->
        "ccb-desktop-credential/v1/dormant/community-session"
    is DesktopCredentialKey.AccountProfiles -> "ccb-desktop-credential/v1/accounts/${service.name.lowercase()}"
    is DesktopCredentialKey.AccountProfilesRequired -> "ccb-desktop-credential/v1/accounts-required/${service.name.lowercase()}"
}
