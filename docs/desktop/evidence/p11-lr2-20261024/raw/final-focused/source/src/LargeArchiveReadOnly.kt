package com.example.chatbar.desktop.backup

import java.io.*
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Opt-in tooling, not an alternate restore decoder. Wire/serializers are existing pinned owners.
 * Each stage is separate. Even schema-readable records do not imply full domain portability.
 */
internal object LargeArchiveReadOnly {
    data class Report(val version: Int, val code: Int, val encrypted: Boolean, val files: Int,
        val typedRecords: Int, val unsupportedRecords: Int, val resourceReferences: Int,
        val missingResources: Int, val uninterpretedResources: Int, val dependencyIssues: Int,
        val nestedPackages: Int, val scope: String, val moments: String,
        val reasons: Set<String>) {
        val restoreSupported = false
        val semanticState = "SEMANTICALLY_BLOCKED" // Complete all-domain graph admission is not implemented.
    }
    class Refusal(val stage: String) : IllegalArgumentException("LR2_REFUSED_$stage")
    fun consume(source: InputStream, password: CharArray?, limits: StrictStreamingZip.Limits = StrictStreamingZip.Limits(),
        active: () -> Unit = { check(!Thread.currentThread().isInterrupted) }): Report {
        var stage = "AUTHENTICATION_OR_ZIP"
        try { return source.use { raw ->
            var version = 0; var encrypted = false
            AppBackupCodec.decryptingForInspection(raw, password) { v, e -> version = v; encrypted = e }.use { plain ->
                val records = linkedMapOf<String, AppBackupFileRecord>()
                val entities = linkedMapOf<String, JsonObject>()
                var inventory: Map<String, AppBackupFileRecord>? = null
                var manifest: InspectionManifest? = null
                var preferencesHash: String? = null
                var typed = 0; var unsupported = 0; var structured = 0L; var packages = 0
                val refs = mutableListOf<String>(); val reasons = linkedSetOf<String>()
                var knownSecrets = false
                StrictStreamingZip.walk(plain, limits, active) { name, input ->
                    val metadata = !name.startsWith("files/")
                    val path = if (metadata) name else DesktopBackupPolicy.path(name.removePrefix("files/"))
                    val isEntity = !metadata && path.startsWith("entities/")
                    require(!metadata || name in setOf("manifest.json", "preferences.json", "inventory.jsonl"))
                    val hash = MessageDigest.getInstance("SHA-256"); var size = 0L
                    val hashing = object : FilterInputStream(input) {
                        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
                            if (it > 0) { size += it; hash.update(b, off, it) }
                        }
                    }
                    stage = "JSON_OR_ENTITY"
                    if (metadata || isEntity) {
                        val bytes = bounded(hashing, DesktopBackupPolicy.MAX_JSON, active)
                        try {
                            structured += bytes.size; require(structured <= 64L * 1024 * 1024)
                            if (name == "inventory.jsonl") {
                                val rows = linkedMapOf<String, AppBackupFileRecord>()
                                bytes.inputStream().strictUtf8Reader().useLines { lines -> lines.filter(String::isNotBlank).forEach { line ->
                                    active(); require(line.length <= 4096 && rows.size < 40000)
                                    val row = backupJson.decodeFromJsonElement<AppBackupFileRecord>(strict(line.toByteArray(), active))
                                    DesktopBackupPolicy.path(row.path)
                                    require(row.size in 0..limits.entry && row.sha256.matches(Regex("[a-f0-9]{64}")))
                                    require(rows.put(row.path, row) == null)
                                } }; inventory = rows
                            } else {
                                val value = strict(bytes, active, refs).jsonObject
                                if (name == "manifest.json") manifest = backupJson.decodeFromJsonElement(value)
                                else if (name == "preferences.json") {
                                    val prefs = backupJson.decodeFromJsonElement<InspectionPreferences>(value)
                                    require(prefs.retainedPreferences.all { it in BackupDataRegistry.preferences })
                                    if (prefs.retainedPreferences.isNotEmpty()) reasons += "PREFERENCE_RETENTION_REQUIRES_TARGET"
                                    knownSecrets = listOf(prefs.novelAiToken, prefs.fishAudioKey, prefs.communitySession).any { !it.isNullOrEmpty() }
                                    require(prefs.brushSize.isFinite() && prefs.brushSize > 0)
                                } else {
                                    require(path.endsWith(".json"))
                                    entities[path] = value
                                    val type = DesktopBackupPolicy.entityType(path)
                                    val serializer = when (type) {
                                        "moment_posts" -> MomentPostSnapshot.serializer()
                                        "moment_tasks" -> MomentTaskSnapshot.serializer()
                                        else -> DesktopBackupPolicy.schemas[type]
                                    }
                                    if (serializer == null) { unsupported++; reasons += "UNKNOWN_ENTITY_FAMILY" }
                                    else try { backupJson.decodeFromJsonElement(serializer, value); typed++ }
                                    catch (_: Exception) { unsupported++; reasons += "ENTITY_SCHEMA_UNSUPPORTED" }
                                    if (hasSecret(value)) knownSecrets = true
                                }
                            }
                        } finally { bytes.fill(0) }
                    } else if (path.startsWith("save_slot_packages/")) {
                        packages++
                        // A genuine nested ZIP is cross-checked, not blindly counted from a suffix.
                        NestedSlotClaims.validate(hashing, active)
                        reasons += "NESTED_SAVESLOT_DOMAIN_GRAPH_NOT_VERIFIED"
                    } else {
                        val buffer = ByteArray(32768)
                        try { while (hashing.read(buffer) >= 0) active() } finally { buffer.fill(0) }
                    }
                    val digest = hash.digest().joinToString("") { "%02x".format(it) }
                    if (!metadata) records[path] = AppBackupFileRecord(path, size, digest)
                    if (name == "preferences.json") preferencesHash = digest
                    stage = "AUTHENTICATION_OR_ZIP"
                }
                stage = "MANIFEST_INVENTORY"
                val m = requireNotNull(manifest)
                require(m.formatVersion == version && m.appVersionCode in 84..86 && (version != 2 || m.appVersionCode >= 85))
                require((version == 2) == (m.scope != null))
                require(inventory == records && preferencesHash != null && m.preferencesSha256 == preferencesHash)
                require(m.fileCount == records.size && m.totalBytes == records.values.sumOf { it.size })
                val counts = entities.keys.groupingBy(DesktopBackupPolicy::entityType).eachCount()
                require(m.counts == counts)
                stage = "SCOPE"
                val scope = verifyScope(m.scope, entities, counts)
                stage = "RESOURCE_GRAPH"
                var missing = 0; var opaque = 0
                refs.forEach { ref ->
                    if (ref.startsWith(DesktopBackupPolicy.PREFIX)) {
                        val path = DesktopBackupPolicy.path(ref.removePrefix(DesktopBackupPolicy.PREFIX))
                        if (path !in records) missing++
                    } else if (ref.isNotEmpty() && !ref.startsWith("chatbar-save-slot-omitted-image:")) opaque++
                }
                var dependencies = 0
                val ids = entities.entries.groupBy { DesktopBackupPolicy.entityType(it.key) }.mapValues { (_, entries) ->
                    val list = entries.mapNotNull { it.value["id"]?.jsonPrimitive?.contentOrNull }
                    dependencies += list.size - list.distinct().size; list.toSet()
                }
                val fields = mapOf("sessionId" to "chat_sessions", "characterCardId" to "character_cards",
                    "messageId" to "chat_messages", "postId" to "moment_posts")
                entities.forEach { (path, value) ->
                    if (DesktopBackupPolicy.entityType(path) != "save_slots") fields.forEach { (field, type) ->
                        value[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { if (it !in ids[type].orEmpty()) dependencies++ }
                    }
                }
                val moment = MomentArchiveReadOnlyValidator().validate(m, records, entities, "PRIVATE_NOT_EXPORTED", knownSecrets, active)
                if (knownSecrets) reasons += "CREDENTIAL_TRANSACTION_NOT_AUTHORIZED"
                if (missing > 0) reasons += "RESOURCE_MISSING"
                if (opaque > 0) reasons += "RESOURCE_REFERENCE_UNINTERPRETED"
                if (dependencies > 0) reasons += "DEPENDENCY_UNRESOLVED_OR_SELECTION_UNKNOWN"
                reasons += "ALL_DOMAIN_GRAPH_ADAPTERS_INCOMPLETE"
                active()
                Report(version, m.appVersionCode, encrypted, records.size, typed, unsupported, refs.size,
                    missing, opaque, dependencies, packages, scope, moment.state.name, reasons)
            }
        } } catch (_: Exception) { throw Refusal(stage) } // Never propagate source/parsing/crypto text.
    }
    internal fun strict(bytes: ByteArray, active: () -> Unit = {}, refs: MutableList<String>? = null): JsonElement {
        val out = StringWriter()
        BackupJsonStream(bytes.inputStream().strictUtf8Reader(), out, checkActive = active) { text, _ ->
            if (refs != null) { require(refs.size < 200000); refs += text }; text
        }.copy()
        return backupJson.parseToJsonElement(out.toString())
    }
    internal fun bounded(input: InputStream, max: Int, active: () -> Unit): ByteArray {
        val out = object : ByteArrayOutputStream() { fun wipe() { buf.fill(0); reset() } }
        val buffer = ByteArray(32768)
        try { while (true) { active(); val n = input.read(buffer); if (n < 0) break
            require(n <= max - out.size()); out.write(buffer, 0, n)
        }; return out.toByteArray() } finally { buffer.fill(0); out.wipe() }
    }
    private fun hasSecret(value: JsonElement): Boolean = when(value) {
        is JsonObject -> value.any { (key, v) -> (key in setOf("apiKey", "novelAiToken", "fishAudioKey", "communitySession", "siliconFlowApiKey") &&
            v != JsonNull && (v !is JsonPrimitive || v.content.isNotEmpty())) || hasSecret(v) }
        is JsonArray -> value.any(::hasSecret)
        else -> false
    }
    private fun verifyScope(s: InspectionScope?, entities: Map<String, JsonObject>, counts: Map<String, Int>): String {
        if (s == null) return "LEGACY_V1"
        require(s.globalOptions.all { it in inspectionGlobalOptions })
        require(s.characters == (counts["character_cards"] ?: 0) && s.sessions == (counts["chat_sessions"] ?: 0))
        require(listOf(s.sessionsWithImages, s.sessionsWithVoice, s.sessionsWithArchives).all { it in 0..s.sessions })
        require(s.moments || (counts["moment_posts"] ?: 0) + (counts["moment_tasks"] ?: 0) == 0)
        require(s.studioHistory || (counts["novelai_generation_history"] ?: 0) == 0)
        require(s.studioSettings || counts.keys.none { it.startsWith("novelai_studio_") || it.startsWith("novelai_design_") })
        val sessions = entities.filterKeys { DesktopBackupPolicy.entityType(it) == "chat_sessions" }.values
            .map { requireNotNull(it["id"]?.jsonPrimitive?.contentOrNull) }
        require(sessions.distinct().size == sessions.size && s.sessionChoices.keys.all { it in sessions })
        if (s.sessionDefaultChoice == null && s.sessionChoices.isEmpty()) return "AGGREGATES_ONLY_SELECTION_UNKNOWN"
        val choices = sessions.associateWith { requireNotNull(s.sessionChoices[it] ?: s.sessionDefaultChoice) }
        require(choices.values.count { it.images } == s.sessionsWithImages && choices.values.count { it.voice } == s.sessionsWithVoice &&
            choices.values.count { it.archives } == s.sessionsWithArchives)
        entities.forEach { (path, value) ->
            val choice = choices[value["sessionId"]?.jsonPrimitive?.contentOrNull] ?: return@forEach
            when (DesktopBackupPolicy.entityType(path)) {
                "generated_voice_messages" -> require(choice.voice)
                "save_slots" -> require(choice.archives)
                "chat_messages" -> require(choice.images ||
                    (value["images"] as? JsonArray).orEmpty().isEmpty() && (value["generatedImageMetadata"] as? JsonArray).orEmpty().isEmpty())
            }
        }
        return "EXPLICIT_SELECTION_COUNTS_CHECKED"
    }
}

