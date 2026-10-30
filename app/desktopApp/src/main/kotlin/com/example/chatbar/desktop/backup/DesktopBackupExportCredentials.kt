package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.security.*
import kotlinx.serialization.json.*

/** Invocation-owned snapshot of exact official credential fields, never plaintext staging files. */
internal class DesktopBackupExportCredentials private constructor(private val store: DesktopSecretStore) : AutoCloseable {
    private val values = linkedMapOf<DesktopCredentialKey, CharArray?>()
    private val accountExports = linkedMapOf<DesktopAccountService, String>()
    private var closed = false
    private fun read(key: DesktopCredentialKey): CharArray? = store.load(key)?.toCharArray()?.also {
        check(it.size <= 65536) { "EXPORT_CREDENTIAL_BUDGET" }
    }
    private fun capture(key: DesktopCredentialKey) { if (!values.containsKey(key)) values[key] = read(key) }
    private fun value(key: DesktopCredentialKey): String? {
        check(!closed)
        return values[key]?.concatToString()
    }
    fun entity(path: String, original: JsonObject): JsonObject {
        check(!closed)
        val key = key(path, original) ?: return original
        val field = if (key == DesktopCredentialKey.SiliconFlowApiKey) "siliconFlowApiKey" else "apiKey"
        return JsonObject(original + (field to JsonPrimitive(value(key).orEmpty())))
    }
    fun preferences(original: BackupPreferences) = original.copy(
        novelAiToken = value(DesktopCredentialKey.NovelAiToken), fishAudioKey = value(DesktopCredentialKey.FishAudioApiKey),
        communitySession = value(DesktopCredentialKey.DormantCommunitySession),
        desktopLlmAccounts = accountExports[DesktopAccountService.LLM],
        desktopNovelAiAccounts = accountExports[DesktopAccountService.NOVELAI],
        desktopFishAccounts = accountExports[DesktopAccountService.FISH])
    fun verifyUnchanged() {
        check(!closed)
        values.forEach { (key, before) ->
            val now = read(key)
            try { check(if (before == null || now == null) before == null && now == null else before.contentEquals(now)) {
                "EXPORT_CREDENTIAL_CHANGED"
            } } finally { now?.fill('\u0000') }
        }
    }
    override fun close() { values.values.forEach { it?.fill('\u0000') }; values.clear(); accountExports.clear(); closed = true }
    override fun toString() = "DesktopBackupExportCredentials(REDACTED)"
    companion object {
        fun capture(store: DesktopSecretStore, entities: Map<String, JsonObject>, options: Set<String> = inspectionGlobalOptions): DesktopBackupExportCredentials {
            val result = DesktopBackupExportCredentials(store)
            try {
                mapOf("novelAiCredentials" to DesktopCredentialKey.NovelAiToken, "fishAudioCredentials" to DesktopCredentialKey.FishAudioApiKey,
                    "community" to DesktopCredentialKey.DormantCommunitySession).filterKeys { it in options }.values.forEach(result::capture)
                entities.forEach { (path, entity) -> key(path, entity)?.takeIf {
                    it != DesktopCredentialKey.SiliconFlowApiKey || "siliconFlowApiKey" in options }?.let(result::capture) }
                val modelIds = entities.mapNotNull { (path, entity) ->
                    if (DesktopBackupPolicy.entityType(path) in setOf("model_configs", "retrieval_model_config")) entity["id"]?.jsonPrimitive?.content else null
                }.toSet()
                DesktopAccountService.entries.filter { service -> when (service) {
                    DesktopAccountService.LLM -> modelIds.isNotEmpty() || "models" in options
                    DesktopAccountService.NOVELAI -> "novelAiCredentials" in options
                    DesktopAccountService.FISH -> "fishAudioCredentials" in options
                } }.forEach { service ->
                    val key = DesktopCredentialKey.AccountProfiles(service)
                    result.capture(key)
                    result.value(key)?.let { raw ->
                        val bundle = DesktopAccountStore.decode(raw, service)
                        val included = (store as? DesktopAccountStore)?.exportBundle(service, modelIds, "models" in options) ?: if (service == DesktopAccountService.LLM) {
                            val bindings = bundle.models.filterKeys { it in modelIds }
                            bundle.copy(accounts = bundle.accounts.filter { it.id in bindings.values }, models = bindings, selected = null)
                        } else bundle
                        if (included.accounts.isNotEmpty()) result.accountExports[service] = DesktopAccountStore.encode(included, service)
                    }
                }
                result.verifyUnchanged()
                return result
            } catch (failure: Throwable) { result.close(); throw failure }
        }
        private fun key(path: String, value: JsonObject): DesktopCredentialKey? = when (DesktopBackupPolicy.entityType(path)) {
            "app_settings" -> DesktopCredentialKey.SiliconFlowApiKey
            "model_configs", "retrieval_model_config" -> DesktopCredentialKey.ModelApiKey(value.getValue("id").jsonPrimitive.content)
            "embedding_configs" -> DesktopCredentialKey.LegacyEmbeddingApiKey(value.getValue("id").jsonPrimitive.content)
            "embedding_model_config" -> DesktopCredentialKey.SingletonEmbeddingApiKey
            else -> null
        }
    }
}
