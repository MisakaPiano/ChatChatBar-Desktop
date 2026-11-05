package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.desktop.security.*
import java.io.StringWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Volatile, invocation-owned secret custody. Only the verified staging owner may use its values. */
internal class DesktopImportSecrets : AutoCloseable {
    class Entry internal constructor(val kind: ImportCredentialKind, val state: CredentialFieldState,
        val account: ImportAccountIdentity?, internal val key: DesktopCredentialKey,
        private val value: CharArray?) {
        internal fun <T> withValue(action: (CharArray?) -> T): T = action(value)
        internal fun wipe() { value?.fill('\u0000') }
        override fun toString() = "ImportCredential($kind,$state)"
    }
    private val entries = linkedMapOf<DesktopCredentialKey, Entry>()
    private var closed = false
    val metadata: List<Entry> get() { check(!closed); return entries.values.toList() }

    fun entity(path: String, value: JsonObject): JsonObject {
        check(!closed)
        val type = DesktopBackupPolicy.entityType(path)
        val kind = when (type) {
            "app_settings" -> ImportCredentialKind.SILICON_FLOW
            "model_configs", "retrieval_model_config" -> ImportCredentialKind.MODEL
            "embedding_configs" -> ImportCredentialKind.LEGACY_EMBEDDING
            "embedding_model_config" -> ImportCredentialKind.SINGLETON_EMBEDDING
            else -> return value
        }
        val field = if (kind == ImportCredentialKind.SILICON_FLOW) "siliconFlowApiKey" else "apiKey"
        val account = if (kind == ImportCredentialKind.SILICON_FLOW) null else {
            fun string(name: String) = value.getValue(name).jsonPrimitive.also { require(it.isString) }.content
            ImportAccountIdentity(string("id"), string("displayName"), string("baseUrl"), string("modelName"),
                if (kind == ImportCredentialKind.MODEL) ModelTemplate.valueOf(value["templateType"]?.jsonPrimitive?.content ?: "OPENAI") else null)
                .also { require(it.valid(kind)) { "IMPORT_CREDENTIAL_IDENTITY" } }
        }
        val key = account?.key(kind) ?: DesktopCredentialKey.SiliconFlowApiKey
        add(value, field, kind, account, key)
        // Only exact official credential fields change. CustomParams, metadata and Prompt text do not.
        return JsonObject(value + (field to JsonPrimitive("")))
    }

    fun preferences(value: JsonObject): JsonObject {
        check(!closed)
        add(value, "novelAiToken", ImportCredentialKind.NOVEL_AI, null, DesktopCredentialKey.NovelAiToken)
        add(value, "fishAudioKey", ImportCredentialKind.FISH, null, DesktopCredentialKey.FishAudioApiKey)
        val session = value["communitySession"]
        if (session is JsonPrimitive && session.isString && session.content.isNotEmpty()) validateCommunity(session.content)
        add(value, "communitySession", ImportCredentialKind.COMMUNITY, null, DesktopCredentialKey.DormantCommunitySession)
        val accountFields = mapOf("desktopLlmAccounts" to DesktopAccountService.LLM,
            "desktopNovelAiAccounts" to DesktopAccountService.NOVELAI, "desktopFishAccounts" to DesktopAccountService.FISH)
        accountFields.forEach { (field, service) ->
            if (field !in value) return@forEach // Old official packages have no Desktop account field.
            val encoded = value[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            if (!encoded.isNullOrEmpty()) DesktopAccountStore.decode(encoded, service)
            add(value, field, accountKind(service), null, DesktopCredentialKey.AccountProfiles(service))
        }
        return JsonObject(value + (listOf("novelAiToken", "fishAudioKey", "communitySession") + accountFields.keys).associateWith { JsonNull })
    }

    private fun add(obj: JsonObject, field: String, kind: ImportCredentialKind, account: ImportAccountIdentity?, key: DesktopCredentialKey) {
        val element = obj[field]
        val state = when {
            element == null -> CredentialFieldState.MISSING
            element == JsonNull -> CredentialFieldState.EXPLICIT_NULL
            element is JsonPrimitive && element.isString -> if (element.content.isEmpty()) CredentialFieldState.EMPTY else CredentialFieldState.NONEMPTY
            else -> error("IMPORT_CREDENTIAL_FIELD_TYPE")
        }
        val characters = if (state == CredentialFieldState.NONEMPTY) element!!.jsonPrimitive.content.toCharArray() else null
        check(characters == null || characters.size <= 65536) { "IMPORT_CREDENTIAL_BUDGET" }
        val old = entries[key]
        if (old != null) {
            val sameAccount = old.account == null && account == null || old.account?.let { account?.let(it::sameAccount) } == true
            val sameValue = old.withValue { previous ->
                if (previous == null || characters == null) previous == null && characters == null else previous.contentEquals(characters)
            }
            characters?.fill('\u0000')
            check(sameAccount && sameValue && old.state == state) { "IMPORT_DUPLICATE_CREDENTIAL_TARGET_CONFLICT" }
        } else entries[key] = Entry(kind, state, account, key, characters)
    }

    /** No expiry refresh, network request, scheduler or Community account activation. */
    private fun validateCommunity(raw: String) {
        try {
            val output = StringWriter()
            BackupJsonStream(raw.reader(), output) { value, _ -> value }.copy()
            val value = backupJson.parseToJsonElement(output.toString()).jsonObject
            val expiry = value["expiresAtMillis"]
            require(expiry == null || expiry is JsonPrimitive && !expiry.isString &&
                expiry.content.matches(Regex("-?(0|[1-9][0-9]*)")) && expiry.longOrNull != null)
            backupJson.decodeFromJsonElement<CommunityWire>(value)
        } catch (_: Exception) { error("IMPORT_COMMUNITY_SESSION_SCHEMA") }
    }

    @Serializable private data class CommunityWire(val accessToken: String, val userId: String, val displayName: String,
        val refreshToken: String? = null, val tokenType: String = "bearer", val expiresAtMillis: Long = 0L)

    override fun close() {
        entries.values.forEach(Entry::wipe); entries.clear(); closed = true
    }
    override fun toString() = "DesktopImportSecrets(REDACTED)"
}
