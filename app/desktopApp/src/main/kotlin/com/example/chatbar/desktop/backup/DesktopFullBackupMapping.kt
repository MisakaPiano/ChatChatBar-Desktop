package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.*
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.domain.image.DanbooruCatalogMetadata
import com.example.chatbar.domain.voice.FishAudioStoredAudio
import java.nio.file.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Maps only registered resource fields, preserving logical IDs, versions, source turns and order. */
internal class DesktopFullBackupMapping(private val root: Path, private val asset: (String) -> ByteArray = { error("BUNDLED_ASSET_UNAVAILABLE") }) {
    data class Export(val directory: Path, val files: Map<String, Path>, val proof: Map<String, String>, val preferences: BackupPreferences,
        private val scratch: DesktopBackupScratch, val credentialFreeSource: Boolean = false) : AutoCloseable { override fun close() = scratch.close() }
    data class Restore(val directory: Path, val files: Map<String, Path>, val proof: Map<String, String>,
        private val scratch: DesktopBackupScratch) : AutoCloseable { override fun close() = scratch.close() }

    suspend fun inventory(maximum: Long = DesktopBackupPolicy.MAX_TOTAL): Map<String, String> {
        require(maximum in DesktopBackupPolicy.MAX_TOTAL..StrictBackupZipReader.Limits().expanded)
        DesktopArchiveFiles.safe(root, true)
        DesktopHistoricalVoiceCustody.validate(root)
        DesktopPreservationStore.validate(root)
        DesktopSaveSlotSourceCustody.validate(root)
        val result = linkedMapOf<String, String>()
        var bytes = 0L
        Files.newDirectoryStream(root).use { children -> children.forEach { child ->
            val name = child.fileName.toString()
            check(name in DesktopBackupPolicy.roots || name in DesktopBackupPolicy.localRoots) { "UNCLASSIFIED_ROOT_ENTRY" }
            if (name == "auxiliary") DesktopBackupAuxiliary.validate(child)
            if (name in DesktopBackupPolicy.roots) {
                DesktopArchiveFiles.safe(child, true)
                Files.walk(child).use { paths -> paths.iterator().asSequence().forEach { path ->
                    currentCoroutineContext().ensureActive()
                    val relative = root.relativize(path).joinToString("/")
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) DesktopArchiveFiles.safe(path, true)
                    else {
                        DesktopArchiveFiles.safe(path, false)
                        // Hidden directories can contain retained recovery/user bytes, not just
                        // unfinished publications. Without an ownership contract, refuse the entire
                        // export instead of silently certifying a partial backup.
                        check(relative.split('/').dropLast(1).none { it.startsWith('.') }) {
                            "UNCLASSIFIED_HIDDEN_BACKUP_ENTRY"
                        }
                        // Preserve the existing single-file unfinished-publication exclusions.
                        if (path.fileName.toString().let {
                            it.endsWith(".part") || it.endsWith(".tmp") || it.endsWith(".backup")
                        } || relative == "entities/novelai_prompt_translation_cache.json") return@forEach
                        check(!path.fileName.toString().startsWith('.')) { "UNCLASSIFIED_HIDDEN_BACKUP_ENTRY" }
                        check(result.size < DesktopBackupPolicy.MAX_FILES)
                        bytes += Files.size(path); check(bytes <= maximum)
                        result[DesktopBackupPolicy.path(relative)] = DesktopArchiveFiles.hash(path, DesktopBackupPolicy.MAX_FILE)
                    }
                } }
            }
        } }
        check(result.keys.map { it.lowercase() }.distinct().size == result.size)
        return result
    }

    /** Call under the real exclusive coordinator: root writers cannot interleave this snapshot. */
    suspend fun export(workspace: Path, wireVersionCode: Int = 84, maximum: Long = DesktopBackupPolicy.MAX_TOTAL,
        forMerge: Boolean = false, selection: AppBackupSelection? = null): Export {
        require(wireVersionCode in 84..86)
        val proof = inventory(maximum)
        val context = currentCoroutineContext()
        val catalog = selection?.let { BackupExportCatalog.read(root.toFile(), proof.keys.associateWith(root::resolve)) { context.ensureActive() } }
        val plan = selection?.let { requireNotNull(catalog).plan(it) }
        val selectedRecords = plan?.records?.associateBy { it.relative }
        val selectedMedia = plan?.additionalFiles?.mapTo(hashSetOf()) { it.second } ?: hashSetOf()
        val custodyRoots = setOf(DesktopPreservationStore.ROOT, DesktopSaveSlotSourceCustody.ROOT, DesktopHistoricalVoiceCustody.ROOT)
        if (selection != null && proof.keys.any { it.substringBefore('/') in custodyRoots })
            check(selection == requireNotNull(catalog).allSelection()) { "DESKTOP_CUSTODY_REQUIRES_FULL_SELECTION" }
        DesktopArchiveFiles.safe(workspace, true)
        val scratch = DesktopBackupScratch.create(workspace, "export-")
        val directory = scratch.directory
        try {
        val files = linkedMapOf<String, Path>()
        val names = hashSetOf<String>()
        fun add(name: String, path: Path) { check(names.add(name.lowercase())); files[name] = path }
        val bundled = linkedMapOf<String, String>()
        var preferences = BackupPreferences()
        var entityType = ""
        var entityLabel = ""
        fun resource(value: String, fields: List<String>): String {
            if (value.startsWith("chatbar-vibe-file:")) {
                val hash = value.removePrefix("chatbar-vibe-file:")
                check(hash.matches(Regex("[a-f0-9]{64}")) && "novelai-vibe-payloads/$hash" in proof)
                return value
            }
            if (value.startsWith("asset:")) {
                val relative = bundled.getOrPut(value) {
                    val bytes = asset(value.removePrefix("asset:"))
                    check(bytes.size <= DesktopBackupPolicy.MAX_JSON)
                    val extension = value.substringAfterLast('.', "png").lowercase()
                    check(extension.matches(Regex("[a-z0-9]{1,10}")))
                    val kind = if (extension in setOf("txt", "md")) "documents" else "images"
                    val name = "$kind/asset-${DesktopBackupPolicy.digest(value.toByteArray()).take(40)}.$extension"
                    val path = directory.resolve("asset-${bundled.size}")
                    scratch.write(path, bytes); add(name, path)
                    name
                }
                return DesktopBackupPolicy.PREFIX + relative
            }
            val relative = DesktopBackupPolicy.sourceRelative(value, root) ?: return value
            val inline = entityType == "pending_deletions" || fields.any { it in setOf("imageResources", "audioResources", "pendingDeletedAssets") }
            if (relative !in proof && !inline) throw DesktopBackupMissingResource(entityLabel, relative.substringAfterLast('/'))
            if (relative in proof) selectedMedia += relative
            return DesktopBackupPolicy.PREFIX + relative
        }
        // Resolve selected entity references first; an unselected broken card cannot block another card.
        proof.entries.sortedBy { if (it.key.startsWith("entities/")) 0 else 1 }.forEachIndexed { index, (relative, hash) ->
            currentCoroutineContext().ensureActive()
            val source = root.resolve(relative)
            val target = directory.resolve("file-$index")
            if (relative.startsWith("entities/")) {
                if (selectedRecords != null && relative !in selectedRecords && relative != "entities/desktop_backup_preferences.json") return@forEachIndexed
                entityType = DesktopBackupPolicy.entityType(relative)
                if (wireVersionCode >= 85) DesktopBackupRecords.requireSize(Files.size(source)) else check(Files.size(source) <= DesktopBackupPolicy.MAX_JSON)
                val raw = Files.readAllBytes(source)
                // Normal exports strip keys. A merge snapshot may not silently discard an unsafe
                // PC plaintext value that differs from/misses its protected counterpart.
                if (forMerge) DesktopBackupPolicy.entity(relative, raw, false, wireVersionCode, modern = wireVersionCode >= 85)
                val value = DesktopBackupPolicy.entity(relative, raw, true, wireVersionCode, modern = wireVersionCode >= 85)
                entityLabel = (value["name"] as? JsonPrimitive)?.contentOrNull?.take(160) ?: entityType
                if (relative == "entities/desktop_backup_preferences.json") {
                    preferences = backupJson.decodeFromJsonElement(BackupPreferences.serializer(), value)
                    return@forEachIndexed
                }
                val name = DesktopBackupPolicy.portableEntityName(relative, value)
                val selectedRecord = selectedRecords?.get(relative)
                val mapped = if (selectedRecord == null) DesktopBackupPolicy.mapResources(value, ::resource) else {
                    val output = java.io.StringWriter()
                    BackupJsonStream(value.toString().reader(), output, checkActive = { context.ensureActive() },
                        replaceValue = { plan!!.replacement(selectedRecord.file, it) }, extraRootFields = plan!!.extraFields(selectedRecord.file)) { path, fields ->
                        resource(plan.resourcePath(selectedRecord.file, path, fields), fields)
                    }.copy()
                    // Desktop background preferred is an additional registered resource field.
                    if (entityType == "desktop_character_backgrounds") {
                        val mappedValue = backupJson.parseToJsonElement(output.toString()).jsonObject
                        val preferred = value["preferred"]?.jsonPrimitive?.contentOrNull
                        JsonObject(mappedValue + ("preferred" to (preferred?.let { JsonPrimitive(resource(it, listOf("preferred"))) } ?: JsonNull))).toString().toByteArray()
                    } else output.toString().toByteArray()
                }
                scratch.write(target, mapped)
                add(name, target)
            } else {
                if (selection != null && relative !in selectedMedia && relative.substringBefore('/') !in custodyRoots) return@forEachIndexed
                check(scratch.copy(source, target, DesktopBackupPolicy.MAX_FILE) == hash); add(relative, target)
            }
        }
        check(inventory(maximum) == proof) { "SOURCE_CHANGED_DURING_EXPORT" }
        return Export(directory, files, proof, preferences, scratch, forMerge)
        } catch (error: Throwable) {
            try { scratch.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    suspend fun restore(prepared: DesktopFullBackupArchive.Prepared): Restore =
        restoreFiles(prepared.files, prepared.directory, prepared.preferences, 84, DesktopBackupPolicy.MAX_TOTAL)

    /** Separate modern prepared merge input; never converts a v2 archive into legacy Prepared. */
    internal suspend fun restoreMerged(inputs: Map<String, Path>, workspace: Path, preferences: BackupPreferences): Restore =
        restoreFiles(inputs, workspace, preferences, 86, StrictBackupZipReader.Limits().expanded)

    private suspend fun restoreFiles(inputs: Map<String, Path>, workspace: Path, preferences: BackupPreferences,
        wireVersionCode: Int, maximum: Long): Restore {
        val scratch = DesktopBackupScratch.create(workspace, "mapped-")
        val directory = scratch.directory
        try {
        val destinations = linkedMapOf<String, String>()
        // Read one typed entity at a time; do not retain all chat bodies or vectors.
        inputs.forEach { (relative, file) ->
            val value = if (relative.startsWith("entities/")) {
                if (wireVersionCode >= 85) DesktopBackupRecords.requireSize(Files.size(file)) else check(Files.size(file) <= DesktopBackupPolicy.MAX_JSON)
                DesktopBackupPolicy.entity(relative, Files.readAllBytes(file), false, wireVersionCode, modern = wireVersionCode >= 85)
            } else null
            destinations[relative] = DesktopBackupPolicy.desktopName(relative, value)
        }
        check(destinations.values.map { it.lowercase() }.distinct().size == destinations.size)
        val files = linkedMapOf<String, Path>()
        var entityType = ""
        fun mappedResource(value: String, fields: List<String>): String {
            if (value.startsWith("chatbar-vibe-file:")) {
                val hash = value.removePrefix("chatbar-vibe-file:")
                check(hash.matches(Regex("[a-f0-9]{64}")) && "novelai-vibe-payloads/$hash" in destinations)
                return value
            }
            if (value.isBlank() || value.startsWith("http:") || value.startsWith("https:") ||
                value.startsWith("chatbar-save-slot-") || value.startsWith("chatbar-vibe-file:")) return value
            check(value.startsWith(DesktopBackupPolicy.PREFIX)) { "UNMAPPED_SOURCE_DEVICE_PATH" }
            val original = DesktopBackupPolicy.path(value.removePrefix(DesktopBackupPolicy.PREFIX))
            val destination = destinations[original]
            if (destination == null && (entityType == "pending_deletions" || fields.any { it in setOf("imageResources", "audioResources", "pendingDeletedAssets") })) {
                return root.resolve(DesktopBackupPolicy.desktopName(original)).toString()
            }
            check(destination != null) { "REFERENCED_RESOURCE_MISSING" }
            // Desktop image/document owners deliberately accept only flat relative references.
            return if (destination.startsWith("images/") || destination.startsWith("documents/")) destination
                else root.resolve(destination).toString()
        }
        inputs.forEach { (relative, file) ->
            currentCoroutineContext().ensureActive()
            val destination = destinations.getValue(relative)
            val target = directory.resolve(destination)
            scratch.directories(target.parent)
            if (relative.startsWith("entities/")) {
                entityType = DesktopBackupPolicy.entityType(relative)
                val value = DesktopBackupPolicy.entity(relative, Files.readAllBytes(file), false, wireVersionCode, modern = wireVersionCode >= 85)
                scratch.write(target, DesktopBackupPolicy.mapResources(value, ::mappedResource))
            } else scratch.copy(file, target, DesktopBackupPolicy.MAX_FILE)
            files[destination] = target
        }
        // Android clips lack Desktop ownership receipts. Re-persist those bounded MP3 bytes and
        // retain their originals. Existing Desktop clips must not gain another copy per restore.
        val audio = DesktopVoiceAudioStore(directory, extendedPaths = true, onCreated = scratch::recordCreated)
        inputs.filterKeys { it.startsWith("entities/generated_voice_messages/") }.forEach { (relative, file) ->
            val source = backupJson.decodeFromString(GeneratedVoiceMessage.serializer(), Files.readString(file))
            check(source.audioPath.startsWith(DesktopBackupPolicy.PREFIX))
            val sourceRelative = source.audioPath.removePrefix(DesktopBackupPolicy.PREFIX)
            val sourcePath = requireNotNull(inputs[sourceRelative])
            // Re-persisting valid MP3 is not permission to conceal inconsistent source metadata.
            // Validate the original claim before generating a Desktop receipt/derived duration.
            check(source.durationMs >= 0) { "RECORDING_DURATION_INVALID" }
            check(source.byteLength > 0 && source.byteLength == Files.size(sourcePath)) { "RECORDING_AUDIO_LENGTH_MISMATCH" }
            val mappedAudio = destinations.getValue(sourceRelative)
            val recorded = if (mappedAudio.startsWith("audio/generated/${source.sessionId}/") &&
                destinations["$sourceRelative.owner"] == "$mappedAudio.owner") {
                val existing = files.getValue(mappedAudio)
                check(existing == directory.resolve(mappedAudio))
                // A supplied receipt must verify; corruption cannot fall back to a fresh artifact.
                val verified = audio.verify(existing.toString())
                FishAudioStoredAudio(verified.toString(), DesktopMp3Validation.validate(verified, 16L shl 20, 1_800_000), source.byteLength)
            } else Files.newInputStream(sourcePath).use {
                audio.persist(source.sessionId, UUID.randomUUID().toString(), it, expected = source.byteLength)
            }
            val stagedAudio = Path.of(recorded.path)
            val audioRelative = directory.relativize(stagedAudio).joinToString("/")
            val target = files.getValue(destinations.getValue(relative))
            val value = backupJson.parseToJsonElement(Files.readString(target)).jsonObject.toMutableMap()
            value["audioPath"] = JsonPrimitive(root.resolve(audioRelative).toString())
            value["durationMs"] = JsonPrimitive(recorded.durationMs)
            value["byteLength"] = JsonPrimitive(recorded.byteLength)
            Files.writeString(target, JsonObject(value).toString())
            files[audioRelative] = stagedAudio
            val receipt = stagedAudio.resolveSibling("${stagedAudio.fileName}.owner")
            check(Files.isRegularFile(receipt)); files["$audioRelative.owner"] = receipt
        }
        validateCatalog(directory)
        val preferencePath = "entities/desktop_backup_preferences.json"
        check(preferencePath !in files) { "DUPLICATE_PREFERENCES_AUTHORITY" }
        val preferenceFile = directory.resolve(preferencePath)
        scratch.write(preferenceFile, backupJson.encodeToString(BackupPreferences.serializer(), preferences).toByteArray())
        files[preferencePath] = preferenceFile
        check(files.size <= DesktopBackupPolicy.MAX_FILES)
        var size = 0L
        val proof = files.mapValues { (_, path) ->
            size += Files.size(path); check(size <= maximum)
            DesktopArchiveFiles.hash(path, DesktopBackupPolicy.MAX_FILE)
        }
        return Restore(directory, files, proof, scratch)
        } catch (error: Throwable) {
            try { scratch.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    suspend fun verify(plan: Restore) {
        plan.files.forEach { (relative, file) ->
            check(file == plan.directory.resolve(relative))
            check(DesktopArchiveFiles.hash(file, DesktopBackupPolicy.MAX_FILE) == plan.proof.getValue(relative))
        }
    }

    private fun validateCatalog(directory: Path) {
        val database = directory.resolve("danbooru_catalog/tag.sqlite")
        val manifest = directory.resolve("danbooru_catalog/catalog.json")
        if (!Files.exists(database) && !Files.exists(manifest)) return
        check(Files.isRegularFile(database) && Files.isRegularFile(manifest) && Files.size(manifest) <= 1024 * 1024)
        val metadata = backupJson.decodeFromString(DanbooruCatalogMetadata.serializer(), Files.readString(manifest))
        check(metadata.sourceSizeBytes == Files.size(database) && metadata.rowCount >= 0 && metadata.sourceSha.isNotBlank())
        java.sql.DriverManager.getConnection("jdbc:sqlite:file:${database.toUri().rawPath}?mode=ro&immutable=1").use { db ->
            db.createStatement().use { statement -> statement.executeQuery("PRAGMA quick_check").use { rows ->
                check(rows.next() && rows.getString(1) == "ok" && !rows.next())
            } }
        }
    }
}

internal class DesktopBackupMissingResource(val ownerLabel: String, val resourceName: String) : IllegalStateException("SELECTED_RESOURCE_MISSING")
