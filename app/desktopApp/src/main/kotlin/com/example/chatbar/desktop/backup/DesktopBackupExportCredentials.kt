package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.security.*
import kotlinx.serialization.json.*

/** Invocation-owned snapshot of exact official credential fields, never plaintext staging files. */
internal class DesktopBackupExportCredentials private constructor(private val store: DesktopSecretStore) : AutoCloseable {
    private val values = linkedMapOf<DesktopCredentialKey, CharArray?>()
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
        communitySession = value(DesktopCredentialKey.DormantCommunitySession))
    fun verifyUnchanged() {
        check(!closed)
        values.forEach { (key, before) ->
            val now = read(key)
            try { check(if (before == null || now == null) before == null && now == null else before.contentEquals(now)) {
                "EXPORT_CREDENTIAL_CHANGED"
            } } finally { now?.fill('\u0000') }
        }
    }
    override fun close() { values.values.forEach { it?.fill('\u0000') }; values.clear(); closed = true }
    override fun toString() = "DesktopBackupExportCredentials(REDACTED)"
    companion object {
        fun capture(store: DesktopSecretStore, entities: Map<String, JsonObject>, options: Set<String> = inspectionGlobalOptions): DesktopBackupExportCredentials {
            val result = DesktopBackupExportCredentials(store)
            try {
                mapOf("novelAiCredentials" to DesktopCredentialKey.NovelAiToken, "fishAudioCredentials" to DesktopCredentialKey.FishAudioApiKey,
                    "community" to DesktopCredentialKey.DormantCommunitySession).filterKeys { it in options }.values.forEach(result::capture)
                entities.forEach { (path, entity) -> key(path, entity)?.takeIf {
                    it != DesktopCredentialKey.SiliconFlowApiKey || "siliconFlowApiKey" in options }?.let(result::capture) }
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
