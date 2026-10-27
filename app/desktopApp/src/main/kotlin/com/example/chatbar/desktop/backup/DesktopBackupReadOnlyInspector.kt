package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import java.io.*
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** No workspace, repository, SecretStore, Prepared, or restore capability is available here. */
internal class DesktopBackupReadOnlyInspector(private val afterAuthentication: () -> Unit = {}) {
    enum class CredentialCategory { NOVEL_AI, FISH_AUDIO, COMMUNITY, MODEL, EMBEDDING, SILICON_FLOW, OTHER_KNOWN_KEY }
    enum class RestoreAssessment { REQUIRES_EXISTING_V1_PREPARATION, UNSUPPORTED }
    enum class MediaSelection { SELECTED_EMPTY, SELECTED_PRESENT, NOT_SELECTED, LEGACY_UNSPECIFIED }
    data class MediaCounts(val selectedEmpty: Int, val selectedPresent: Int, val notSelected: Int, val unspecified: Int)
    data class ScopeReport(
        val globalOptions: Set<String>, val characters: Int, val sessions: Int,
        val sessionsWithImages: Int, val sessionsWithVoice: Int, val sessionsWithArchives: Int,
        val images: MediaCounts, val voice: MediaCounts, val archives: MediaCounts,
        val studioSettings: Boolean, val studioHistory: Boolean, val studioHistoryImages: Boolean,
        val moments: Boolean, val momentsImages: Boolean
    )
    // Only trusted categories, booleans, counts and digests leave inspection. Never manifest text or IDs.
    data class ReadOnlyInspection(
        val sourceSha256: String, val envelopeVersion: Int, val manifestVersion: Int,
        val appVersionCode: Int, val encrypted: Boolean, val fileCount: Int, val totalBytes: Long,
        val scope: ScopeReport?, val credentials: Set<CredentialCategory>, val uninterpretedEntities: Int,
        val modelRecords: Int, val embeddingRecords: Int, val formatCardRecords: Int,
        val worldBookRecords: Int, val saveSlotPackages: Int, val momentPosts: Int, val momentTasks: Int,
        val restoreAssessment: RestoreAssessment
    ) {
        val formatReadable: Boolean get() = true
        val restoreSupported: Boolean get() = false // This result can NEVER authorize restore.
        val fullPortabilityAccepted: Boolean get() = false
        val resourceGraphVerified: Boolean get() = false // S1 and restore graph checks are separate.
        val unknownContentMayContainSecrets: Boolean get() = uninterpretedEntities > 0
        val credentialScanCoversOnlyKnownFields: Boolean get() = true
        val nestedSaveSlotContentsVerified: Boolean get() = false
        val dependencyClosureVerified: Boolean get() = false
    }
    class InspectionFailure(val stage: String) : IllegalArgumentException("BACKUP_READ_ONLY_$stage")

    suspend fun inspect(source: Path, password: CharArray?): ReadOnlyInspection = scan(source, password)

    /** Opt-in only; no census escapes until the same scan passes every final check. */
    suspend fun inspectCredentialSources(source: Path, password: CharArray?): VerifiedCredentialSourceMetadata {
        val builder = DesktopCredentialSourceCensus()
        var result: VerifiedCredentialSourceMetadata? = null
        try {
            scan(source, password, census = builder, censusValidated = { result = it })
            currentCoroutineContext().ensureActive()
            return requireNotNull(result)
        } catch (failure: Throwable) { result?.close(); throw failure
        } finally { builder.close() }
    }

    suspend fun inspectMoments(source: Path, password: CharArray?): MomentArchiveReadOnlyValidator.Report {
        var report: MomentArchiveReadOnlyValidator.Report? = null
        val context = currentCoroutineContext()
        scan(source, password) { manifest, records, entities, hash, credentials ->
            report = MomentArchiveReadOnlyValidator().validate(manifest, records, entities, hash, credentials.isNotEmpty()) { context.ensureActive() }
        }
        return requireNotNull(report)
    }

    private suspend fun scan(source: Path, password: CharArray?,
        census: DesktopCredentialSourceCensus? = null,
        censusValidated: ((VerifiedCredentialSourceMetadata) -> Unit)? = null,
        validated: ((InspectionManifest, Map<String, AppBackupFileRecord>, Map<String, JsonObject>, String, Set<CredentialCategory>) -> Unit)? = null
    ): ReadOnlyInspection = withContext(Dispatchers.IO) {
        var stage = "INPUT"
        var sourceBytes: ByteArray? = null
        var payload: ByteArray? = null
        try {
            val buffer = WipingBuffer()
            val hash = try { DesktopArchiveFiles.read(source, MAX_COMPRESSED.toLong()) { bytes, n -> buffer.write(bytes, 0, n) }
            } catch (failure: Throwable) { buffer.wipe(); throw failure }
            sourceBytes = buffer.take()
            val context = currentCoroutineContext()
            var version = 0; var encrypted = false
            stage = "AUTHENTICATION"
            payload = AppBackupCodec.decryptingForInspection(sourceBytes.inputStream(), password) { v, e -> version = v; encrypted = e }
                .use { bounded(it, MAX_COMPRESSED) { context.ensureActive() } }
            afterAuthentication()
            context.ensureActive()
            // Authentication is consumed to EOF before any result. Plaintext is never written to disk.
            stage = "ZIP"
            val central = InspectionZipDirectory.read(payload)
            val seen = linkedMapOf<String, AppBackupFileRecord>()
            val entities = linkedMapOf<String, JsonObject>()
            var manifest: InspectionManifest? = null
            var preferenceHash: String? = null
            var inventory: Map<String, AppBackupFileRecord>? = null
            val credentials = linkedSetOf<CredentialCategory>()
            var total = 0L
            var uninterpreted = 0
            ZipInputStream(payload.inputStream()).use { zip ->
                val names = hashSetOf<String>()
                while (true) {
                    context.ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && names.add(entry.name.lowercase(Locale.ROOT)))
                    val expected = requireNotNull(central[entry.name])
                    val metadata = !entry.name.startsWith("files/")
                    val path = if (metadata) entry.name else safePath(entry.name.removePrefix("files/"))
                    val entity = !metadata && path.startsWith("entities/")
                    val limit = if (metadata || entity) DesktopBackupPolicy.MAX_JSON else MAX_EXPANDED
                    val bytes = bounded(zip, minOf(limit.toLong(), MAX_EXPANDED - total).toInt()) { context.ensureActive() }
                    try {
                        total += bytes.size
                        zip.closeEntry() // local CRC/length check
                        require(expected.size == bytes.size.toLong() && expected.crc == entry.crc)
                        val sha = DesktopBackupPolicy.digest(bytes)
                        if (!metadata) {
                            census?.file(path)
                            seen[path] = AppBackupFileRecord(path, bytes.size.toLong(), sha)
                            if (entity) {
                                stage = "ENTITY_SCHEMA"
                                val value = strictJson(bytes).jsonObject
                                scanCredentials(value, DesktopBackupPolicy.entityType(path), credentials)
                                if (DesktopBackupPolicy.schemas[DesktopBackupPolicy.entityType(path)] == null) uninterpreted++
                                entities[path] = value
                                census?.json(path, value, sha, bytes.size.toLong())
                            }
                        } else {
                            stage = "METADATA"
                            when (path) {
                                "manifest.json" -> {
                                    val value = strictJson(bytes)
                                    manifest = backupJson.decodeFromJsonElement(value)
                                    census?.manifest(value.jsonObject)
                                }
                                "preferences.json" -> {
                                    val prefs = backupJson.decodeFromJsonElement<InspectionPreferences>(strictJson(bytes))
                                    require(prefs.retainedPreferences.isEmpty())
                                    require(prefs.brushSize.isFinite() && prefs.brushSize > 0 && prefs.brushType.length <= 100)
                                    if (!prefs.novelAiToken.isNullOrEmpty()) credentials += CredentialCategory.NOVEL_AI
                                    if (!prefs.fishAudioKey.isNullOrEmpty()) credentials += CredentialCategory.FISH_AUDIO
                                    if (!prefs.communitySession.isNullOrEmpty()) credentials += CredentialCategory.COMMUNITY
                                    preferenceHash = sha
                                    census?.json(path, strictJson(bytes).jsonObject, sha, bytes.size.toLong())
                                }
                                "inventory.jsonl" -> {
                                    val records = linkedMapOf<String, AppBackupFileRecord>()
                                    bytes.inputStream().strictUtf8Reader().useLines { lines -> lines.filter { it.isNotBlank() }.forEach { line ->
                                        require(line.length <= 4096 && records.size < DesktopBackupPolicy.MAX_FILES)
                                        val record = backupJson.decodeFromJsonElement<AppBackupFileRecord>(strictJson(line.toByteArray()))
                                        safePath(record.path)
                                        require(record.size in 0..MAX_EXPANDED && record.sha256.matches(Regex("[a-f0-9]{64}")))
                                        require(records.put(record.path, record) == null)
                                    } }
                                    inventory = records
                                }
                                else -> error("ENTRY")
                            }
                        }
                    } finally { bytes.fill(0) }
                    stage = "ZIP"
                }
                require(names.size == central.size)
            }
            stage = "MANIFEST"
            val m = requireNotNull(manifest)
            require(m.formatVersion == version && m.appVersionCode in 84..86)
            require(m.formatVersion in 1..2 && (version != 2 || m.appVersionCode >= 85))
            require(inventory == seen && preferenceHash != null && preferenceHash == m.preferencesSha256)
            require(m.fileCount == seen.size && m.totalBytes == seen.values.sumOf { it.size })
            val counts = seen.keys.filter { it.startsWith("entities/") }.groupingBy(DesktopBackupPolicy::entityType).eachCount()
            require(m.counts == counts)
            stage = "ENTITY_SCHEMA"
            for ((path, value) in entities) {
                context.ensureActive()
                if (DesktopBackupPolicy.entityType(path) in DesktopBackupPolicy.schemas) {
                    DesktopOfficialBackupEntitySchema.validate(DesktopBackupPolicy.entityType(path), value, m.appVersionCode)
                    DesktopBackupPolicy.entity(path, value.toString().toByteArray(), exporting = true, wireVersionCode = m.appVersionCode)
                }
            }
            stage = "SCOPE"
            require(if (version == 2) m.scope != null else m.scope == null)
            val scope = m.scope?.let { scopeReport(it, counts, entities) }
            context.ensureActive()
            validated?.invoke(m, seen, entities, hash, credentials)
            census?.let { builder -> censusValidated?.invoke(builder.publish(m, version, hash, sourceBytes.size.toLong())) }
            ReadOnlyInspection(hash, version, m.formatVersion, m.appVersionCode, encrypted, m.fileCount, m.totalBytes,
                scope, credentials.toSet(), uninterpreted, counts.count("model_configs") + counts.count("retrieval_model_config"),
                counts.count("embedding_configs") + counts.count("embedding_model_config"), counts.count("format_cards"), counts.count("world_books"),
                seen.keys.count { it.startsWith("save_slot_packages/") }, counts.count("moment_posts"), counts.count("moment_tasks"),
                if (version == 1 && m.appVersionCode == 84 && credentials.isEmpty() && uninterpreted == 0)
                    RestoreAssessment.REQUIRES_EXISTING_V1_PREPARATION else RestoreAssessment.UNSUPPORTED)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { throw InspectionFailure(stage) // discard causes/parser snippets containing secrets
        } finally { sourceBytes?.fill(0); payload?.fill(0) }
    }

    internal fun scopeReport(s: InspectionScope, counts: Map<String, Int>, entities: Map<String, JsonObject>): ScopeReport {
        require(s.globalOptions.all { it in inspectionGlobalOptions })
        require(s.characters == counts.count("character_cards") && s.sessions == counts.count("chat_sessions"))
        require(listOf(s.sessionsWithImages, s.sessionsWithVoice, s.sessionsWithArchives).all { it in 0..s.sessions })
        require(s.moments || counts.count("moment_posts") + counts.count("moment_tasks") == 0)
        require(s.studioHistory || counts.count("novelai_generation_history") == 0)
        require(s.studioSettings || counts.keys.none { it.startsWith("novelai_studio_") || it.startsWith("novelai_design_") })
        val sessions = entities.filterKeys { DesktopBackupPolicy.entityType(it) == "chat_sessions" }.values
            .map { requireNotNull(it["id"]?.jsonPrimitive?.contentOrNull) }
        require(sessions.distinct().size == sessions.size && s.sessionChoices.keys.all { it in sessions })
        val choices = sessions.associateWith { s.sessionChoices[it] ?: s.sessionDefaultChoice }
        if (s.sessionDefaultChoice != null || s.sessionChoices.isNotEmpty()) {
            require(choices.values.all { it != null })
            require(choices.values.count { it!!.images } == s.sessionsWithImages)
            require(choices.values.count { it!!.voice } == s.sessionsWithVoice)
            require(choices.values.count { it!!.archives } == s.sessionsWithArchives)
        }
        fun media(flag: (InspectionSessionChoice) -> Boolean, types: Set<String>, image: Boolean = false): MediaCounts {
            val states = sessions.map { id ->
                val choice = choices[id]
                val present = entities.any { (path, value) -> DesktopBackupPolicy.entityType(path) in types &&
                    value["sessionId"]?.jsonPrimitive?.contentOrNull == id && (!image ||
                    (value["images"] as? JsonArray)?.isNotEmpty() == true || (value["generatedImageMetadata"] as? JsonArray)?.isNotEmpty() == true) }
                require(choice == null || flag(choice) || !present)
                when { choice == null -> MediaSelection.LEGACY_UNSPECIFIED
                    !flag(choice) -> MediaSelection.NOT_SELECTED
                    present -> MediaSelection.SELECTED_PRESENT
                    else -> MediaSelection.SELECTED_EMPTY }
            }
            return MediaCounts(states.count { it == MediaSelection.SELECTED_EMPTY }, states.count { it == MediaSelection.SELECTED_PRESENT },
                states.count { it == MediaSelection.NOT_SELECTED }, states.count { it == MediaSelection.LEGACY_UNSPECIFIED })
        }
        return ScopeReport(s.globalOptions, s.characters, s.sessions, s.sessionsWithImages, s.sessionsWithVoice, s.sessionsWithArchives,
            media({ it.images }, setOf("chat_messages"), true),
            media({ it.voice }, setOf("generated_voice_messages")), media({ it.archives }, setOf("save_slots")),
            s.studioSettings, s.studioHistory, s.studioHistoryImages, s.moments, s.momentsImages)
    }

    private fun scanCredentials(value: JsonElement, type: String, found: MutableSet<CredentialCategory>) {
        when (value) {
            is JsonObject -> value.forEach { (key, child) ->
                val category = when (key) {
                    "novelAiToken" -> CredentialCategory.NOVEL_AI; "fishAudioKey" -> CredentialCategory.FISH_AUDIO
                    "communitySession" -> CredentialCategory.COMMUNITY; "siliconFlowApiKey" -> CredentialCategory.SILICON_FLOW
                    "apiKey" -> when (type) {
                        "model_configs", "retrieval_model_config" -> CredentialCategory.MODEL
                        "embedding_configs", "embedding_model_config" -> CredentialCategory.EMBEDDING
                        else -> CredentialCategory.OTHER_KNOWN_KEY }
                    else -> null
                }
                if (category != null && child != JsonNull && (child !is JsonPrimitive || child.content.isNotEmpty())) found += category
                scanCredentials(child, type, found)
            }
            is JsonArray -> value.forEach { scanCredentials(it, type, found) }
            else -> Unit
        }
    }
    private fun strictJson(bytes: ByteArray): JsonElement {
        val writer = StringWriter()
        BackupJsonStream(bytes.inputStream().strictUtf8Reader(), writer) { value, _ -> value }.copy()
        return backupJson.parseToJsonElement(writer.toString())
    }
    private fun safePath(path: String): String {
        DesktopBackupPolicy.path(path)
        require(path.none { it in ":<>\"|?*" } && path.split('/').all { !it.endsWith('.') && !it.endsWith(' ') })
        return path
    }
    private fun Map<String, Int>.count(type: String) = get(type) ?: 0
    private fun bounded(input: InputStream, maximum: Int, active: () -> Unit): ByteArray {
        val out = WipingBuffer(); val buffer = ByteArray(32768)
        try { while (true) { active(); val n = input.read(buffer); if (n < 0) break
            require(n <= maximum - out.size()); out.write(buffer, 0, n)
        }; return out.take() } finally { buffer.fill(0); out.wipe() }
    }
    private class WipingBuffer : ByteArrayOutputStream() {
        fun wipe() { buf.fill(0); reset() }
        fun take(): ByteArray = toByteArray().also { wipe() }
    }
    companion object {
        // Deliberate read-only memory budget; larger archives fail closed, never spill secrets to disk.
        const val MAX_COMPRESSED = 64 * 1024 * 1024
        const val MAX_EXPANDED = 128L * 1024 * 1024
    }
}
