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

/** Writes a frozen mapped snapshot, optionally overlaying an explicitly captured credential snapshot.
 * The selected transport schema is official147/code86/v2, not the Desktop validated upstream baseline.
 * Desktop-only domains stay explicit and are never represented as Android full portability.
 */
internal object DesktopFullBackupWriter {
    private val wire = backupJson
    private val excludedOptions = setOf("novelAiCredentials", "fishAudioCredentials", "siliconFlowApiKey", "community")
    class Refusal(val stage: String) : IllegalStateException("FULL_BACKUP_EXPORT_$stage")

    suspend fun export(files: Map<String, Path>, destination: Path, password: CharArray?,
        preferences: BackupPreferences = BackupPreferences(), credentials: DesktopBackupExportCredentials? = null,
        selection: AppBackupSelection? = null,
        beforePublish: suspend () -> Unit = {}): String =
        try { withContext(Dispatchers.IO) {
            var stage = "INPUT"
            try {
                require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                DesktopArchiveFiles.safe(destination.parent, true)
                require(files.size <= DesktopBackupPolicy.MAX_FILES && files.keys.map(String::lowercase).distinct().size == files.size)
                require(preferences.novelAiToken == null && preferences.fishAudioKey == null && preferences.communitySession == null)
                require(preferences.desktopLlmAccounts == null && preferences.desktopNovelAiAccounts == null && preferences.desktopFishAccounts == null)
                require(preferences.brushSize.isFinite() && preferences.brushSize > 0 && preferences.brushType.length <= 100)
                val entities = linkedMapOf<String, JsonObject>()
                val proof = linkedMapOf<String, AppBackupFileRecord>()
                val sourceProof = linkedMapOf<String, String>()
                val transported = linkedMapOf<String, ByteArray>()
                var structured = 0L; var total = 0L
                for ((name, path) in files.toSortedMap()) {
                    currentCoroutineContext().ensureActive()
                    DesktopBackupPolicy.path(name)
                    DesktopArchiveFiles.safe(path, false)
                    val size = Files.size(path)
                    require(size in 0..DesktopFullBackupSource.MAX_FILE)
                    sourceProof[name] = DesktopArchiveFiles.hash(path, DesktopFullBackupSource.MAX_FILE)
                    if (name.startsWith("entities/")) {
                        require(size <= DesktopBackupPolicy.MAX_JSON)
                        structured += size; require(structured <= DesktopFullBackupSource.MAX_STRUCTURED)
                        val bytes = Files.readAllBytes(path)
                        try {
                            val original = DesktopBackupPolicy.entity(name, bytes, false, wireVersionCode = 86)
                            val value = credentials?.entity(name, original) ?: original
                            entities[name] = value
                            if (value != original) transported[name] = value.toString().toByteArray()
                        }
                        finally { bytes.fill(0) }
                    }
                    val bytes = transported[name]
                    val length = bytes?.size?.toLong() ?: size
                    total += length; require(total <= StrictBackupZipReader.Limits().expanded)
                    proof[name] = AppBackupFileRecord(name, length, bytes?.let(DesktopBackupPolicy::digest) ?: sourceProof.getValue(name))
                }
                val counts = entities.keys.groupingBy(DesktopBackupPolicy::entityType).eachCount()
                val sessions = counts["chat_sessions"] ?: 0
                val choices = selection?.sessions?.mapValues { (_, choice) -> InspectionSessionChoice(choice.images, choice.voice, choice.archives) }
                val scope = InspectionScope(selection?.globalOptions ?: if (credentials == null) inspectionGlobalOptions - excludedOptions else inspectionGlobalOptions,
                    characters = counts["character_cards"] ?: 0, sessions = sessions,
                    sessionsWithImages = choices?.values?.count { it.images } ?: sessions,
                    sessionsWithVoice = choices?.values?.count { it.voice } ?: sessions,
                    sessionsWithArchives = choices?.values?.count { it.archives } ?: sessions,
                    studioSettings = counts.keys.any { it.startsWith("novelai_studio_") || it.startsWith("novelai_design_") },
                    studioHistory = selection?.studioHistory ?: ((counts["novelai_generation_history"] ?: 0) > 0),
                    studioHistoryImages = selection?.studioHistoryImages ?: true,
                    moments = false, momentsImages = true, sessionChoices = choices.orEmpty(),
                    sessionDefaultChoice = if (choices == null) InspectionSessionChoice() else null)
                DesktopBackupReadOnlyInspector().scopeReport(scope, counts, entities)
                val capturedPreferences = credentials?.preferences(preferences) ?: preferences
                val prefs = wire.encodeToString(InspectionPreferences.serializer(), InspectionPreferences(
                    novelAiToken = capturedPreferences.novelAiToken, fishAudioKey = capturedPreferences.fishAudioKey,
                    communitySession = capturedPreferences.communitySession,
                    desktopLlmAccounts = capturedPreferences.desktopLlmAccounts,
                    desktopNovelAiAccounts = capturedPreferences.desktopNovelAiAccounts,
                    desktopFishAccounts = capturedPreferences.desktopFishAccounts,
                    brushSize = preferences.brushSize, brushType = preferences.brushType)).toByteArray()
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
                                val replacement = transported[name]
                                val hash = if (replacement != null) {
                                    zip.write(replacement); copied = replacement.size.toLong(); DesktopBackupPolicy.digest(replacement)
                                } else DesktopArchiveFiles.read(files.getValue(name), DesktopFullBackupSource.MAX_FILE) { buffer, count ->
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
                    credentials?.verifyUnchanged()
                    for ((name, hash) in sourceProof) {
                        context.ensureActive()
                        require(DesktopArchiveFiles.hash(files.getValue(name), DesktopFullBackupSource.MAX_FILE) == hash)
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
