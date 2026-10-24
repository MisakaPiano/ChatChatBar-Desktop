package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Writes a frozen, mapped, credential-free snapshot. It neither reads SecretStore nor activates a root.
 * The selected transport schema is official147/code86/v2, not the Desktop validated upstream baseline.
 * Desktop-only domains stay explicit and are never represented as Android full portability.
 */
internal object DesktopFullBackupWriter {
    private val wire = Json { ignoreUnknownKeys = false; encodeDefaults = false }
    private val retainedSecrets = setOf("novelai_credentials", "fish_audio_credentials", "community_session")
    private val excludedOptions = setOf("novelAiCredentials", "fishAudioCredentials", "siliconFlowApiKey", "community")
    class Refusal(val stage: String) : IllegalStateException("FULL_BACKUP_EXPORT_$stage")

    suspend fun export(files: Map<String, Path>, destination: Path, password: CharArray?,
        preferences: BackupPreferences = BackupPreferences(), beforePublish: suspend () -> Unit = {}): String =
        try { withContext(Dispatchers.IO) {
            var stage = "INPUT"
            try {
                require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                DesktopArchiveFiles.safe(destination.parent, true)
                require(files.size <= DesktopBackupPolicy.MAX_FILES && files.keys.map(String::lowercase).distinct().size == files.size)
                require(preferences.novelAiToken == null && preferences.fishAudioKey == null && preferences.communitySession == null)
                require(preferences.brushSize.isFinite() && preferences.brushSize > 0 && preferences.brushType.length <= 100)
                val entities = linkedMapOf<String, JsonObject>()
                val proof = linkedMapOf<String, AppBackupFileRecord>()
                var structured = 0L; var total = 0L
                for ((name, path) in files.toSortedMap()) {
                    currentCoroutineContext().ensureActive()
                    DesktopBackupPolicy.path(name)
                    DesktopArchiveFiles.safe(path, false)
                    val size = Files.size(path)
                    require(size in 0..DesktopFullBackupSource.MAX_FILE)
                    total += size; require(total <= StrictBackupZipReader.Limits().expanded)
                    if (name.startsWith("entities/")) {
                        require(size <= DesktopBackupPolicy.MAX_JSON)
                        structured += size; require(structured <= DesktopFullBackupSource.MAX_STRUCTURED)
                        val bytes = Files.readAllBytes(path)
                        try { entities[name] = DesktopBackupPolicy.entity(name, bytes, false, wireVersionCode = 86) }
                        finally { bytes.fill(0) }
                    }
                    proof[name] = AppBackupFileRecord(name, size, DesktopArchiveFiles.hash(path, DesktopFullBackupSource.MAX_FILE))
                }
                val counts = entities.keys.groupingBy(DesktopBackupPolicy::entityType).eachCount()
                val sessions = counts["chat_sessions"] ?: 0
                val scope = InspectionScope(inspectionGlobalOptions - excludedOptions,
                    characters = counts["character_cards"] ?: 0, sessions = sessions,
                    sessionsWithImages = sessions, sessionsWithVoice = sessions, sessionsWithArchives = sessions,
                    studioSettings = counts.keys.any { it.startsWith("novelai_studio_") || it.startsWith("novelai_design_") },
                    studioHistory = (counts["novelai_generation_history"] ?: 0) > 0, studioHistoryImages = true,
                    moments = false, momentsImages = true, sessionDefaultChoice = InspectionSessionChoice())
                DesktopBackupReadOnlyInspector().scopeReport(scope, counts, entities)
                val prefs = wire.encodeToString(InspectionPreferences.serializer(), InspectionPreferences(
                    brushSize = preferences.brushSize, brushType = preferences.brushType, retainedPreferences = retainedSecrets)).toByteArray()
                val manifest = InspectionManifest(formatVersion = 2, appVersionCode = 86,
                    appVersionName = "CCB Desktop / official 1.4.7 transport compatibility", createdAt = System.currentTimeMillis(),
                    sourceFilesRoot = "desktop-selected-root", counts = counts, fileCount = proof.size,
                    totalBytes = total, preferencesSha256 = DesktopBackupPolicy.digest(prefs), scope = scope)
                DesktopBackupScratch.create(destination.parent, "backup-modern-output-").use { scratch ->
                    val part = scratch.directory.resolve("output.part")
                    stage = "WRITE"
                    scratch.output(part).use { raw ->
                        ZipOutputStream(AppBackupCodec.encryptingV2(raw, password)).use { zip ->
                            zip.setLevel(Deflater.BEST_SPEED)
                            for ((name, expected) in proof) {
                                currentCoroutineContext().ensureActive()
                                zip.putNextEntry(ZipEntry("files/$name"))
                                var copied = 0L
                                val hash = DesktopArchiveFiles.read(files.getValue(name), DesktopFullBackupSource.MAX_FILE) { buffer, count ->
                                    currentCoroutineContext().ensureActive(); zip.write(buffer, 0, count); copied += count
                                }
                                require(hash == expected.sha256 && copied == expected.size)
                                zip.closeEntry()
                            }
                            fun entry(name: String, value: ByteArray) {
                                zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
                            }
                            entry("preferences.json", prefs)
                            entry("inventory.jsonl", proof.values.joinToString("\n", postfix = "\n") {
                                wire.encodeToString(AppBackupFileRecord.serializer(), it)
                            }.toByteArray())
                            entry("manifest.json", wire.encodeToString(InspectionManifest.serializer(), manifest).toByteArray())
                        }
                    }
                    FileChannel.open(part, StandardOpenOption.WRITE).use { it.force(true) }
                    stage = "VERIFY"
                    val context = currentCoroutineContext()
                    val verified = DesktopFullBackupSource.verify(part, password, object : DesktopFullBackupSource.TentativeSink {
                        override fun entity(path: String, original: JsonObject, raw: ByteArray) {}
                        override fun preferences(original: JsonObject) {}
                        override fun media(path: String, input: InputStream) {}
                    }) { context.ensureActive() }
                    require(verified.inventory == proof && verified.manifest == manifest)
                    stage = "SOURCE_RECHECK"
                    beforePublish()
                    for ((name, record) in proof) {
                        context.ensureActive()
                        require(DesktopArchiveFiles.hash(files.getValue(name), DesktopFullBackupSource.MAX_FILE) == record.sha256)
                    }
                    stage = "PUBLISH"
                    context.ensureActive()
                    Files.move(part, destination) // No replacement, including a late-created destination.
                    require(DesktopArchiveFiles.hash(destination, DesktopFullBackupSource.MAX_SOURCE) == verified.sourceHash)
                    verified.sourceHash
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw Refusal(stage) }
        } } finally { password?.fill('\u0000') }
}
