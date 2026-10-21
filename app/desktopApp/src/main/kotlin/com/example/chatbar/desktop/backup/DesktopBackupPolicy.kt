package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import com.example.chatbar.domain.image.*
import java.io.StringWriter
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Selected 1.4.5 transport, current supported logical schema. Unknown stores fail before commit. */
internal object DesktopBackupPolicy {
    const val PREFIX = "chatbar-backup-file:"
    const val MAX_JSON = 8 * 1024 * 1024
    const val MAX_FILE = 256L * 1024 * 1024
    const val MAX_TOTAL = 512L * 1024 * 1024
    const val MAX_FILES = 40000
    val roots = BackupDataRegistry.roots
    // These are not portable content. Restore preserves them in the destination.
    val localRoots = setOf("backups", ".ccb-desktop.lock", "desktop-settings.json",
        "shared-import", "diagnostics", "updates", "prompt_dictionary", "tag_completion")
    val schemas: Map<String, KSerializer<*>> = mapOf(
        "app_settings" to AppSettings.serializer(), "player_setting" to PlayerSetting.serializer(),
        "character_cards" to CharacterCard.serializer(), "format_cards" to FormatCard.serializer(),
        "world_books" to WorldBook.serializer(), "model_configs" to ModelConfig.serializer(),
        "embedding_configs" to EmbeddingConfig.serializer(), "embedding_model_config" to EmbeddingConfig.serializer(),
        "retrieval_model_config" to ModelConfig.serializer(),
        "chat_sessions" to ChatSession.serializer(), "chat_messages" to ChatMessage.serializer(),
        "chat_message_indexes" to ChatMessageIndex.serializer(), "chat_message_order_backups" to ChatMessageOrderBackup.serializer(),
        "chat_drafts" to ChatDraft.serializer(), "chat_scroll_positions" to ChatScrollPosition.serializer(),
        "vector_chunks" to VectorChunk.serializer(), "save_slots" to SaveSlot.serializer(),
        "edit_drafts" to EditorDraft.serializer(), "memory_nodes" to MemoryNode.serializer(),
        "memory_states" to MemorySessionState.serializer(), "memory_tier_revisions" to MemoryTierRevision.serializer(),
        "memory_compression_transactions" to MemoryCompressionTransaction.serializer(),
        "memory_commits" to MemoryCommit.serializer(), "memory_commit_journals" to MemoryCommitJournal.serializer(),
        "generated_voice_messages" to GeneratedVoiceMessage.serializer(), "voice_anchor_states" to VoiceAnchorState.serializer(),
        "novelai_studio_draft" to NovelAiStudioDraft.serializer(), "novelai_studio_history_undo" to NovelAiStudioUndoDraft.serializer(),
        "novelai_studio_guidance_checkpoint" to NovelAiGuidanceEditorCheckpoint.serializer(),
        "novelai_generation_history" to NovelAiGenerationHistoryEntry.serializer(),
        "novelai_history_fold_preferences" to NovelAiHistoryFoldPreference.serializer(),
        "novelai_design_conversations" to NovelAiDesignConversation.serializer(),
        "novelai_design_current" to NovelAiDesignCurrentState.serializer(), "preset_import_state" to PresetImportState.serializer(),
        "desktop_backup_preferences" to BackupPreferences.serializer(),
        "desktop_character_backgrounds" to JsonObject.serializer(),
        "model_configuration_migration" to VersionState.serializer(),
        "character_speaker_migration_state" to SpeakerMigrationState.serializer(),
        "world_book_migration_state" to WorldBookMigrationState.serializer(),
        "pending_deletions" to DeletionState.serializer(),
    )
    private val preferenceSecrets = setOf("novelAiToken", "fishAudioKey", "communitySession")
    fun isDesktopOnly(path: String): Boolean = path.startsWith("entities/") &&
        (entityType(path).startsWith("desktop_") || entityType(path) == "retrieval_model_config")
    private val secrets = setOf("apiKey", "siliconFlowApiKey") + preferenceSecrets
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun path(value: String): String {
        require(value.length in 1..1024 && '\\' !in value && value.none { it.code < 32 })
        val parts = value.split('/')
        require(parts.first() in roots && parts.size >= 2 && parts.none { it.isBlank() || it in setOf(".", "..") })
        return value
    }
    fun entityType(path: String) = path.removePrefix("entities/").substringBefore('/').removeSuffix(".json")

    /** No exception text from parsing is exposed by controllers (it may contain user data). */
    fun entity(relative: String, bytes: ByteArray, exporting: Boolean): JsonObject {
        require(relative.startsWith("entities/") && relative.endsWith(".json") && bytes.size <= MAX_JSON)
        val type = entityType(relative)
        val serializer = schemas[type] ?: throw DesktopBackupCompatibilityException(
            if (type in setOf("moment_posts", "moment_tasks")) DesktopBackupCompatibility.MOMENTS_UNSUPPORTED
            else DesktopBackupCompatibility.ENTITY_UNSUPPORTED)
        // The official transducer also rejects duplicate keys before a JsonObject can collapse them.
        val strict = StringWriter()
        BackupJsonStream(bytes.inputStream().strictUtf8Reader(), strict) { value, _ -> value }.copy()
        val value = json.parseToJsonElement(strict.toString()).jsonObject
        fun credentialFree(node: JsonElement, context: List<String> = emptyList()): JsonElement = when (node) {
            is JsonObject -> JsonObject(node.mapValues { (key, item) ->
                if (key in secrets && context.none { it in setOf("metadata", "extensions") }) {
                    require(item is JsonPrimitive && (item.isString || item is JsonNull))
                    if (!exporting && item !is JsonNull && item.content.isNotBlank())
                        throw DesktopBackupCompatibilityException(DesktopBackupCompatibility.CREDENTIAL_APPROVAL_REQUIRED)
                    if (key in preferenceSecrets) JsonNull else JsonPrimitive("")
                } else credentialFree(item, context + key)
            })
            is JsonArray -> JsonArray(node.map { credentialFree(it, context) })
            else -> node
        }
        val clean = credentialFree(value).jsonObject
        if (type == "desktop_character_backgrounds") {
            require(clean.keys == setOf("images", "preferred"))
            val images = clean.getValue("images").jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }
            val preferred = clean.getValue("preferred").jsonPrimitive.contentOrNull
            require(images.distinct().size == images.size && (preferred == null || preferred in images))
        }
        try {
            json.decodeFromJsonElement(serializer, clean) // Unknown fields are never silently lost.
        } catch (_: SerializationException) {
            throw DesktopBackupCompatibilityException(DesktopBackupCompatibility.ENTITY_SCHEMA_UNSUPPORTED)
        }
        return clean
    }

    fun portableEntityName(relative: String, value: JsonObject): String =
        if (entityType(relative) == "model_configs") path("entities/model_configs/${value.getValue("id").jsonPrimitive.content}.json")
        else path(relative)

    fun desktopName(relative: String, value: JsonObject? = null): String {
        path(relative)
        val name = when {
            relative.startsWith("images/") || relative.startsWith("documents/") -> {
                val extension = relative.substringAfterLast('.', "bin").lowercase()
                require(extension.matches(Regex("[a-z0-9]{1,10}")))
                "${relative.substringBefore('/')}/${digest(relative.toByteArray()).take(40)}.$extension"
            }
            relative.startsWith("entities/model_configs/") ->
                "entities/model_configs/${WindowsSafeModelStorageKeyPolicy.storageKey(requireNotNull(value).getValue("id").jsonPrimitive.content)}.json"
            else -> relative
        }
        require(name.split('/').all { part -> part.length <= 240 && part.none { it in "<>:\"|?*" } &&
            !part.endsWith('.') && !part.endsWith(' ') &&
            !part.substringBefore('.').matches(Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) })
        return name
    }

    fun mapResources(value: JsonObject, map: (String, List<String>) -> String): ByteArray {
        val output = StringWriter()
        BackupJsonStream(value.toString().reader(), output, mapPath = map).copy()
        // Desktop's separate background preference is not an official resource-field name.
        if (value.keys == setOf("images", "preferred")) {
            val parsed = json.parseToJsonElement(output.toString()).jsonObject.toMutableMap()
            value["preferred"]?.jsonPrimitive?.contentOrNull?.let { parsed["preferred"] = JsonPrimitive(map(it, listOf("preferred"))) }
            return JsonObject(parsed).toString().toByteArray(Charsets.UTF_8)
        }
        return output.toString().toByteArray(Charsets.UTF_8)
    }

    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sourceRelative(value: String, root: Path): String? {
        if (value.startsWith(PREFIX)) return path(value.removePrefix(PREFIX))
        if (value.isBlank() || value.startsWith("http:") || value.startsWith("https:") ||
            value.startsWith("chatbar-save-slot-") || value.startsWith("chatbar-vibe-file:")) return null
        require(!value.startsWith("content:") && !value.startsWith("file:") && !value.startsWith("asset:")) { "RESOURCE_REQUIRES_MATERIALIZATION" }
        val candidate = Path.of(value)
        return if (candidate.isAbsolute) {
            require(candidate.normalize().startsWith(root)) { "RESOURCE_OUTSIDE_SELECTED_ROOT" }
            path(root.relativize(candidate.normalize()).joinToString("/"))
        } else path(value.replace('\\', '/'))
    }

    // Exact selected 1.4.5 validator records; these do not introduce new persisted schemas.
    @Serializable private data class VersionState(val version: Int = 0)
    @Serializable private data class SpeakerMigrationState(val lastRunAt: Long, val migratedCards: Int)
    @Serializable private data class WorldBookMigrationState(val lastRunAt: Long, val migratedCharacters: Int)
    @Serializable private data class DeletionState(val id: String, val type: DeletionType, val ownerId: String,
        val filePaths: List<String> = emptyList(), val createdAt: Long = 0)
    @Serializable private enum class DeletionType { CHARACTER, SESSION }
}
