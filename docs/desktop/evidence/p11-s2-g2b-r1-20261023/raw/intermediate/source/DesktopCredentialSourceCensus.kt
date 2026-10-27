package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.ModelTemplate
import kotlinx.serialization.json.*

internal enum class CredentialFieldState { MISSING, EXPLICIT_NULL, EMPTY, NONEMPTY }
internal enum class CensusLimitation { SOURCE_VERSION, UNKNOWN_ENTITY, NESTED_CONTENT, UNKNOWN_FIELD, IDENTITY, DUPLICATE_ID }

/** No source value, source path or account ID is rendered. Record SHA is of whole validated file bytes. */
internal class CredentialSourceRecord internal constructor(
    val source: ImportCredentialSource, val fieldState: CredentialFieldState,
    val fileSha256: String, val fileLength: Long,
    private val recordIdentity: String,
) {
    override fun toString(): String = "CredentialSourceRecord(${source.kind},$fieldState,$fileLength)"
}

/** Invocation-owned volatile builder; only Inspector may publish after the complete scan. */
internal class DesktopCredentialSourceCensus : AutoCloseable {
    private val records = mutableListOf<CredentialSourceRecord>()
    private val values = mutableListOf<CharArray?>()
    private val limitations = linkedSetOf<CensusLimitation>()
    private val identities = hashSetOf<Pair<String, String>>()
    private var preferencesSeen = false
    private var published = false

    fun manifest(value: JsonObject) {
        if (value.keys != setOf("formatVersion", "appVersionCode", "appVersionName", "createdAt", "sourceFilesRoot", "counts", "fileCount", "totalBytes", "preferencesSha256"))
            limitations += CensusLimitation.UNKNOWN_FIELD
    }

    fun file(path: String) {
        if (!path.startsWith("entities/")) limitations += CensusLimitation.NESTED_CONTENT
    }

    fun json(path: String, value: JsonObject, sha: String, length: Long) {
        if (path == "preferences.json") {
            preferencesSeen = true
            if (value.keys.any { it !in preferenceFields }) limitations += CensusLimitation.UNKNOWN_FIELD
            add(value, "novelAiToken", ImportCredentialKind.NOVEL_AI, ImportCredentialLocation.PREFERENCES_NOVEL_AI, sha, length, path)
            add(value, "fishAudioKey", ImportCredentialKind.FISH, ImportCredentialLocation.PREFERENCES_FISH, sha, length, path)
            add(value, "communitySession", ImportCredentialKind.COMMUNITY, ImportCredentialLocation.PREFERENCES_COMMUNITY, sha, length, path)
            // Session JSON is nested serialized data: validate its exact known wire fields, never substitute an API key.
            val session = value["communitySession"]
            if (session is JsonPrimitive && session.isString && session.content.isNotEmpty()) {
                try {
                    val writer = java.io.StringWriter()
                    BackupJsonStream(session.content.reader(), writer) { text, _ -> text }.copy()
                    val obj = backupJson.parseToJsonElement(writer.toString()).jsonObject
                    require(obj.keys.all { it in communityFields })
                    require(listOf("accessToken", "userId", "displayName").all { obj[it] is JsonPrimitive && obj[it]!!.jsonPrimitive.isString })
                    require(obj["refreshToken"] == null || obj["refreshToken"] == JsonNull || obj["refreshToken"]!!.jsonPrimitive.isString)
                    require(obj["tokenType"] == null || obj["tokenType"]!!.jsonPrimitive.isString)
                    val expiry = obj["expiresAtMillis"]
                    require(expiry == null || expiry is JsonPrimitive && !expiry.isString && expiry.longOrNull != null)
                } catch (_: Exception) { limitations += CensusLimitation.NESTED_CONTENT }
            }
            return
        }
        val type = DesktopBackupPolicy.entityType(path)
        if (type !in setOf("app_settings", "model_configs", "embedding_configs", "embedding_model_config")) {
            limitations += CensusLimitation.UNKNOWN_ENTITY; return
        }
        if (type == "app_settings") {
            if (!identities.add(type to "singleton")) limitations += CensusLimitation.DUPLICATE_ID
            if (value.keys.any { it !in settingsFields }) limitations += CensusLimitation.UNKNOWN_FIELD
            // Official appearance objects contain numbers only. Other nested payloads are not inferred safe.
            for ((key, child) in value) if (child is JsonObject || child is JsonArray) {
                val colors = when (key) { "themeColor" -> listOf(child); "themeColorHistory" -> (child as? JsonArray)?.toList(); else -> null }
                if (colors == null || colors.any { color -> color !is JsonObject || color.keys.any { it !in colorFields } || color.values.any { it !is JsonPrimitive || it.isString || it.floatOrNull?.isFinite() != true } })
                    limitations += CensusLimitation.NESTED_CONTENT
            }
            add(value, "siliconFlowApiKey", ImportCredentialKind.SILICON_FLOW, ImportCredentialLocation.APP_SETTINGS, sha, length, path)
            return
        }
        val model = type == "model_configs"
        if (value.keys.any { it !in if (model) modelFields else embeddingFields }) limitations += CensusLimitation.UNKNOWN_FIELD
        if ((value["customParams"] as? JsonObject)?.isNotEmpty() == true || value.any { (key, child) ->
                (child is JsonObject || child is JsonArray) && !(key == "customParams" && child is JsonObject && child.isEmpty()) })
            limitations += CensusLimitation.NESTED_CONTENT
        val id = (value["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (!identities.add(type to if (type == "embedding_model_config") "singleton" else id)) limitations += CensusLimitation.DUPLICATE_ID
        val kind = if (model) ImportCredentialKind.MODEL else if (type == "embedding_configs") ImportCredentialKind.LEGACY_EMBEDDING else ImportCredentialKind.SINGLETON_EMBEDDING
        val location = if (model) ImportCredentialLocation.MODEL_CONFIG else if (type == "embedding_configs") ImportCredentialLocation.LEGACY_EMBEDDING_CONFIG else ImportCredentialLocation.SINGLETON_EMBEDDING_CONFIG
        val account = try {
            fun string(key: String) = value.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            ImportAccountIdentity(string("id"), string("displayName"), string("baseUrl"), string("modelName"),
                if (model) ModelTemplate.valueOf(value["templateType"]?.jsonPrimitive?.content ?: "OPENAI") else null)
                .also { require(it.valid(kind)) }
        } catch (_: Exception) { limitations += CensusLimitation.IDENTITY; null }
        add(value, "apiKey", kind, location, sha, length, id, account)
    }

    private fun add(obj: JsonObject, field: String, kind: ImportCredentialKind, location: ImportCredentialLocation,
        sha: String, length: Long, identity: String, account: ImportAccountIdentity? = null) {
        val element = obj[field]
        val state = when {
            element == null -> CredentialFieldState.MISSING
            element == JsonNull -> CredentialFieldState.EXPLICIT_NULL
            element is JsonPrimitive && element.isString -> if (element.content.isEmpty()) CredentialFieldState.EMPTY else CredentialFieldState.NONEMPTY
            else -> { limitations += CensusLimitation.UNKNOWN_FIELD; return }
        }
        val presence = when (state) {
            CredentialFieldState.MISSING, CredentialFieldState.EXPLICIT_NULL -> ImportSecretPresence.ABSENT
            CredentialFieldState.EMPTY -> ImportSecretPresence.EMPTY
            CredentialFieldState.NONEMPTY -> ImportSecretPresence.NONEMPTY
        }
        records += CredentialSourceRecord(ImportCredentialSource(kind, location, presence, account), state, sha, length, identity)
        values += if (state == CredentialFieldState.NONEMPTY) element!!.jsonPrimitive.content.toCharArray() else null
    }

    fun publish(manifest: InspectionManifest, envelopeVersion: Int, sourceSha: String, sourceLength: Long): VerifiedCredentialSourceMetadata {
        check(!published)
        if (manifest.appVersionCode != 84 || manifest.formatVersion != 1 || envelopeVersion != 1 || manifest.scope != null)
            limitations += CensusLimitation.SOURCE_VERSION
        check(preferencesSeen)
        published = true
        return VerifiedCredentialSourceMetadata(sourceSha, sourceLength, envelopeVersion, manifest.formatVersion,
            manifest.appVersionCode, records.toList(), limitations.toSet(), values.toList()).also { values.clear() }
    }
    override fun close() { values.forEach { it?.fill('\u0000') }; values.clear(); records.clear() }

    companion object {
        private val preferenceFields = setOf("novelAiToken", "fishAudioKey", "communitySession", "brushSize", "brushType")
        private val communityFields = setOf("accessToken", "refreshToken", "tokenType", "expiresAtMillis", "userId", "displayName")
        private val colorFields = setOf("hueDegrees", "saturation", "value")
        private val embeddingFields = setOf("id", "displayName", "baseUrl", "apiKey", "modelName", "dimensions")
        private val modelFields = setOf("id", "displayName", "baseUrl", "apiKey", "modelName", "selectableForChat", "isMultimodal", "visionModelId", "templateType", "customParams", "reasoningEffort", "enableThinking", "maxOutputTokens", "outputTokenParameter", "formatPromptPosition", "supportsJsonMode", "supportsDisableThinking", "sourcePresetKey", "sourcePresetVersion", "createdAt")
        // Fixed official145 AppSettings declaration; future fields must not inherit census completeness.
        private val settingsFields = "defaultModelId defaultImageModelId modelConfigurationMode presetDefaultModelKey siliconFlowApiKey allowCleartextModelApi defaultEmbeddingId defaultFormatCardId automaticFormatCheckEnabled formatRepairModelId memoryRagTopK memoryRagSimilarityThreshold docRagTopK docRagSimilarityThreshold ragInjectionMode defaultContextWindowSize episodeMaxSourceTurns excludeAssistantStatusFromHistory themeMode themeColor themeColorHistory chatBubbleFontScale chatBackgroundImageOpacity assistantSegmentedBubblesEnabled tutorialVersion webSearchSettingsVersion webSearchEnabled characterAutoFillWebSearchEnabled characterRewriteWebSearchEnabled characterAutoFillResearchSourceMode characterRewriteResearchSourceMode worldBookAiResearchSourceMode webSearchMaxResultsPerQuery novelAiImageModel novelAiImageAspectRatio novelAiPromptTranslationConsent imagePromptToolPreference fishAudioTtsModelId voiceTagModelId audiobookModeEnabled momentsEnabled momentsImagesEnabled momentsMinDelayHours momentsMaxDelayHours momentsBackgroundGuideDismissed momentsAutoStartConfirmed lastSeenMomentsAt lastSeenChatAt".split(' ').toSet()
    }
}
