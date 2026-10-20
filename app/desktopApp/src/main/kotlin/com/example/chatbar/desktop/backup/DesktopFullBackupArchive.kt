package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import java.io.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Immutable source, authenticated/bounded validation, then credential-free staging. No live writes. */
internal class DesktopFullBackupArchive(private val workspace: Path) {
    data class Prepared(val directory: Path, val source: Path, val sourceHash: String,
        val summary: AppBackupSummary, val records: Map<String, AppBackupFileRecord>,
        val files: Map<String, Path>, val preferences: BackupPreferences)

    suspend fun inspect(source: Path, password: CharArray?): Prepared = withContext(Dispatchers.IO) {
        DesktopArchiveFiles.safe(workspace, true)
        val work = Files.createDirectory(workspace.resolve("backup-${UUID.randomUUID()}"))
        val copy = work.resolve("source.cbbackup")
        val hash = DesktopArchiveFiles.copyNew(source, copy, DesktopBackupPolicy.MAX_TOTAL)
        DesktopArchiveFiles.safe(copy, false)
        val channel = java.nio.channels.FileChannel.open(copy, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        val lock = channel.tryLock(0, Long.MAX_VALUE, true) ?: run { channel.close(); error("ARCHIVE_INPUT_BUSY") }
        try {
        fun input(): InputStream = object : FilterInputStream(java.nio.channels.Channels.newInputStream(channel.position(0))) {
            override fun close() {}
        }
        val context = currentCoroutineContext()
        fun checkActive() = context.ensureActive()
        // First pass has no plaintext disk sink. Credential/unknown-store rejection happens here.
        val seen = linkedMapOf<String, AppBackupFileRecord>()
        var summary: AppBackupSummary? = null
        var preferences: BackupPreferences? = null
        var preferenceHash: String? = null
        var records: Map<String, AppBackupFileRecord>? = null
        var allBytes = 0L
        input().use { raw ->
            AppBackupCodec.decrypting(raw, password).use { plain ->
                val zip = ZipInputStream(object : FilterInputStream(plain) { override fun close() {} })
                val names = hashSetOf<String>()
                while (true) {
                    checkActive()
                    val entry = zip.nextEntry ?: break
                    check(!entry.isDirectory && names.add(entry.name.lowercase()) && names.size <= DesktopBackupPolicy.MAX_FILES + 3)
                    val metadata = !entry.name.startsWith("files/")
                    val relative = if (metadata) entry.name else DesktopBackupPolicy.path(entry.name.removePrefix("files/"))
                    val entity = !metadata && relative.startsWith("entities/")
                    val buffer = if (metadata || entity) ByteArrayOutputStream() else null
                    val limit = if (metadata || entity) DesktopBackupPolicy.MAX_JSON.toLong() else DesktopBackupPolicy.MAX_FILE
                    val digest = MessageDigest.getInstance("SHA-256")
                    val size = transfer(zip, buffer ?: OutputStream.nullOutputStream(), limit, checkActive = ::checkActive) { bytes, n -> digest.update(bytes, 0, n) }
                    allBytes += size; check(allBytes <= DesktopBackupPolicy.MAX_TOTAL)
                    val sha = hex(digest.digest())
                    zip.closeEntry() // Includes CRC/length validation, even for ignored binary content.
                    if (!metadata) {
                        if (entity) DesktopBackupPolicy.entity(relative, requireNotNull(buffer).toByteArray(), false)
                        seen[relative] = AppBackupFileRecord(relative, size, sha)
                    } else when (relative) {
                        "manifest.json" -> summary = backupJson.decodeFromString(buffer!!.toString(Charsets.UTF_8))
                        "preferences.json" -> {
                            val value = backupJson.decodeFromString<BackupPreferences>(buffer!!.toString(Charsets.UTF_8))
                            check(value.novelAiToken == null && value.fishAudioKey == null && value.communitySession == null) {
                                "CREDENTIAL_TRANSFER_REQUIRES_SEPARATE_APPROVAL"
                            }
                            check(value.brushSize.isFinite() && value.brushSize > 0 && value.brushType.length <= 100)
                            preferences = value; preferenceHash = sha
                        }
                        "inventory.jsonl" -> {
                            val values = linkedMapOf<String, AppBackupFileRecord>()
                            buffer!!.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach { line ->
                                check(line.length <= 4096 && values.size < DesktopBackupPolicy.MAX_FILES)
                                val value = backupJson.decodeFromString<AppBackupFileRecord>(line)
                                DesktopBackupPolicy.path(value.path)
                                check(value.size in 0..DesktopBackupPolicy.MAX_FILE && value.sha256.matches(Regex("[a-f0-9]{64}")))
                                check(values.put(value.path, value) == null)
                            }
                            records = values
                        }
                        else -> error("UNKNOWN_ARCHIVE_ENTRY")
                    }
                }
                // ZipInputStream stops at the central directory. Consume the authenticated stream to EOF.
                transfer(plain, OutputStream.nullOutputStream(), DesktopBackupPolicy.MAX_TOTAL, checkActive = ::checkActive)
            }
        }
        val manifest = requireNotNull(summary)
        check(manifest.formatVersion == 1 && manifest.appVersionCode <= SUPPORTED_VERSION_CODE)
        check(manifest.fileCount == seen.size && manifest.totalBytes == seen.values.sumOf { it.size })
        check(manifest.counts == counts(seen.keys) && manifest.preferencesSha256 == preferenceHash)
        check(records == seen && preferences != null)
        check(DesktopArchiveFiles.hash(copy, DesktopBackupPolicy.MAX_TOTAL) == hash)
        check(Files.getFileStore(work).usableSpace >= allBytes * 2 + 16L * 1024 * 1024)
        // All plaintext entries are now known and credential-free; retain the original encrypted source.
        val payload = work.resolve("validated-payload.zip")
        input().use { raw -> AppBackupCodec.decrypting(raw, password).use { plain ->
            Files.newOutputStream(payload, StandardOpenOption.CREATE_NEW).use {
                transfer(plain, it, DesktopBackupPolicy.MAX_TOTAL, checkActive = ::checkActive)
            }
        } }
        val files = linkedMapOf<String, Path>()
        ZipFile(payload.toFile()).use { zip ->
            val names = zip.entries().asSequence().toList()
            check(names.size == seen.size + 3 && names.map { it.name.lowercase() }.distinct().size == names.size)
            check(names.all { !it.isDirectory && (it.name in setOf("manifest.json", "inventory.jsonl", "preferences.json") || it.name.removePrefix("files/") in seen) })
            seen.entries.forEachIndexed { index, (relative, record) ->
                checkActive()
                val entry = requireNotNull(zip.getEntry("files/$relative"))
                check(entry.size == record.size)
                val destination = work.resolve("entry-$index") // Never interpret a ZIP pathname as a host pathname.
                val digest = MessageDigest.getInstance("SHA-256")
                val crc = CRC32()
                val size = zip.getInputStream(entry).use { input -> Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW).use { output ->
                    transfer(input, output, record.size, checkActive = ::checkActive) { bytes, n -> digest.update(bytes, 0, n); crc.update(bytes, 0, n) }
                } }
                check(size == record.size && hex(digest.digest()) == record.sha256 && crc.value == entry.crc)
                files[relative] = destination
            }
        }
        check(DesktopArchiveFiles.hash(copy, DesktopBackupPolicy.MAX_TOTAL) == hash)
        Prepared(work, copy, hash, manifest, seen, files, requireNotNull(preferences))
        } finally { lock.release(); channel.close() }
    }

    suspend fun verify(prepared: Prepared) {
        check(prepared.directory.parent == workspace && prepared.source.parent == prepared.directory)
        check(DesktopArchiveFiles.hash(prepared.source, DesktopBackupPolicy.MAX_TOTAL) == prepared.sourceHash)
        check(prepared.files.keys == prepared.records.keys)
        prepared.files.forEach { (relative, path) ->
            check(path.parent == prepared.directory && DesktopArchiveFiles.hash(path, DesktopBackupPolicy.MAX_FILE) == prepared.records.getValue(relative).sha256)
        }
    }

    /** Input is a coordinated, already mapped snapshot. This method never reads SecretStore. */
    suspend fun export(files: Map<String, Path>, destination: Path, sourceRoot: String,
        password: CharArray?, preferences: BackupPreferences = BackupPreferences()): String = withContext(Dispatchers.IO) {
        check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "目标已存在；请选择新文件名" }
        DesktopArchiveFiles.safe(destination.parent, true)
        check(files.size <= DesktopBackupPolicy.MAX_FILES && files.keys.map { it.lowercase() }.distinct().size == files.size)
        check(preferences.novelAiToken == null && preferences.fishAudioKey == null && preferences.communitySession == null)
        val part = destination.resolveSibling(".${destination.fileName}.${UUID.randomUUID()}.part")
        val records = mutableListOf<AppBackupFileRecord>()
        val context = currentCoroutineContext()
        var total = 0L
        Files.newOutputStream(part, StandardOpenOption.CREATE_NEW).use { raw ->
            ZipOutputStream(AppBackupCodec.encrypting(raw, password)).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                files.forEach { (relative, file) ->
                    DesktopBackupPolicy.path(relative)
                    val entityBytes = if (relative.startsWith("entities/")) {
                        check(Files.size(file) <= DesktopBackupPolicy.MAX_JSON)
                        val bytes = Files.readAllBytes(file)
                        DesktopBackupPolicy.entity(relative, bytes, false)
                        bytes
                    } else null
                    zip.putNextEntry(ZipEntry("files/$relative"))
                    var size = 0L
                    val hash = if (entityBytes != null) {
                        zip.write(entityBytes); size = entityBytes.size.toLong(); total += size
                        check(total <= DesktopBackupPolicy.MAX_TOTAL)
                        DesktopBackupPolicy.digest(entityBytes)
                    } else DesktopArchiveFiles.read(file, DesktopBackupPolicy.MAX_FILE) { bytes, count ->
                        context.ensureActive(); zip.write(bytes, 0, count); size += count; total += count
                        check(total <= DesktopBackupPolicy.MAX_TOTAL)
                    }
                    zip.closeEntry(); records += AppBackupFileRecord(relative, size, hash)
                }
                val prefs = backupJson.encodeToString(preferences).toByteArray()
                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                entry("preferences.json", prefs)
                entry("inventory.jsonl", records.joinToString("\n", postfix = "\n") { backupJson.encodeToString(it) }.toByteArray())
                entry("manifest.json", backupJson.encodeToString(AppBackupSummary(appVersionCode = SUPPORTED_VERSION_CODE,
                    appVersionName = "CCB Desktop / ChatBar 1.4.5 archive compatibility", createdAt = System.currentTimeMillis(),
                    sourceFilesRoot = sourceRoot, counts = counts(files.keys), fileCount = files.size,
                    totalBytes = total, preferencesSha256 = DesktopBackupPolicy.digest(prefs))).toByteArray())
            }
        }
        context.ensureActive()
        // Validate the exact bytes before making a visible final file. No overwrite or silent fallback.
        val verified = inspect(part, password)
        val hash = verified.sourceHash
        check(DesktopArchiveFiles.hash(part, DesktopBackupPolicy.MAX_TOTAL) == hash)
        Files.move(part, destination)
        check(DesktopArchiveFiles.hash(destination, DesktopBackupPolicy.MAX_TOTAL) == hash)
        hash
    }

    private fun counts(paths: Collection<String>): Map<String, Int> = paths.filter { it.startsWith("entities/") }
        .groupingBy(DesktopBackupPolicy::entityType).eachCount()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun transfer(input: InputStream, output: OutputStream, maximum: Long,
        checkActive: () -> Unit, observe: (ByteArray, Int) -> Unit = { _, _ -> }): Long {
        val buffer = ByteArray(32768); var total = 0L
        while (true) {
            checkActive(); val count = input.read(buffer); if (count < 0) break
            check(count <= maximum - total) { "ARCHIVE_BUDGET_EXCEEDED" }
            output.write(buffer, 0, count); observe(buffer, count); total += count
        }
        return total
    }
    companion object {
        // Selected reference build.gradle.kts versionCode; verified by provenance before release.
        const val SUPPORTED_VERSION_CODE = 84
    }
}
