package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.desktop.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Original nested package + outer record custody, separate from mutable/live SaveSlot files. */
internal object DesktopSaveSlotSourceCustody {
    const val ROOT = "desktop_save_slot_sources"
    private val hash = Regex("[a-f0-9]{64}")
    @Serializable private data class Index(val version: Int = 1, val kind: String = "original-save-slot-source",
        val archiveSha256: String, val sourceCode: Int, val sourceFormat: Int, val sourceScope: InspectionScope?,
        val recordPath: String, val slotId: String, val sessionId: String, val recordSha256: String,
        val recordBytes: Long, val packageSha256: String, val packageBytes: Long)

    suspend fun preserve(targetRoot: Path, recordPath: String, rawRecord: ByteArray, originalPackage: Path,
        archiveSha256: String, sourceCode: Int, sourceFormat: Int, sourceScope: InspectionScope?,
        beforePublish: suspend () -> Unit = {}): String = withContext(Dispatchers.IO) {
        check(DesktopDataRootOwnership.isHeld(targetRoot)) { "SLOT_CUSTODY_ROOT_NOT_OWNED" }
        require(hash.matches(archiveSha256) && sourceCode in 84..86 && sourceFormat in 1..2 &&
            (sourceFormat == 2) == (sourceScope != null) && (sourceFormat != 2 || sourceCode >= 85)) { "SLOT_CUSTODY_SOURCE_IDENTITY" }
        require(recordPath.startsWith("entities/save_slots/") && recordPath.endsWith(".json")) { "SLOT_CUSTODY_SOURCE_PATH" }
        DesktopBackupPolicy.path(recordPath)
        DesktopBackupRecords.requireSize(rawRecord.size.toLong())
        val slot = decodeRecord(rawRecord)
        require(slot.schemaVersion == 8 && slot.packageRef != null) { "SLOT_CUSTODY_SOURCE_SCHEMA" }
        require(DesktopSaveSlotInspector(true).inspect(originalPackage).status == SlotInspectStatus.STRUCTURE_VALID) { "SLOT_CUSTODY_PACKAGE_INVALID" }
        val packageHash = DesktopArchiveFiles.hash(originalPackage, DesktopSaveSlotArchive.MAX_ARCHIVE)
        val reference = requireNotNull(slot.packageRef)
        require(reference.byteLength <= 0 || reference.byteLength == Files.size(originalPackage)) { "SLOT_CUSTODY_PACKAGE_LENGTH" }
        val nested = manifest(originalPackage)
        DesktopSaveSlotLibraryEnvelope.requireCompatible(slot, nested, Files.size(originalPackage))
        val index = Index(archiveSha256 = archiveSha256, sourceCode = sourceCode, sourceFormat = sourceFormat, sourceScope = sourceScope,
            recordPath = recordPath, slotId = slot.id, sessionId = slot.sessionId, recordSha256 = DesktopBackupPolicy.digest(rawRecord),
            recordBytes = rawRecord.size.toLong(), packageSha256 = packageHash, packageBytes = Files.size(originalPackage))
        val name = sealName(index); val parent = targetRoot.resolve(ROOT)
        Files.createDirectories(parent); DesktopArchiveFiles.safe(parent, true)
        val home = parent.resolve(name)
        if (Files.exists(home, LinkOption.NOFOLLOW_LINKS)) {
            check(validateSeal(home) == index) { "SLOT_CUSTODY_EXISTING_CONFLICT" }; return@withContext name
        }
        Files.createDirectory(home)
        write(home.resolve("record.json"), rawRecord)
        check(DesktopArchiveFiles.copyNew(originalPackage, home.resolve("source.cbsave"), DesktopSaveSlotArchive.MAX_ARCHIVE) == packageHash)
        val part = home.resolve("index.part")
        write(part, backupJson.encodeToString(Index.serializer(), index).toByteArray(Charsets.UTF_8))
        beforePublish(); currentCoroutineContext().ensureActive()
        Files.move(part, home.resolve("index.json"), StandardCopyOption.ATOMIC_MOVE)
        check(validateSeal(home) == index)
        name
    }

    suspend fun validate(root: Path) {
        val directory = root.resolve(ROOT)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        DesktopArchiveFiles.safe(directory, true)
        Files.newDirectoryStream(directory).use { children ->
            var count = 0
            for (home in children) {
                currentCoroutineContext().ensureActive(); check(++count <= 10000) { "SLOT_CUSTODY_COUNT" }
                validateSeal(home)
            }
        }
    }

    private suspend fun validateSeal(home: Path): Index {
        DesktopArchiveFiles.safe(home, true)
        val names = Files.newDirectoryStream(home).use { it.map { p -> p.fileName.toString() }.toSet() }
        check(names == setOf("index.json", "record.json", "source.cbsave")) { "SLOT_CUSTODY_FILE_SET" }
        val indexPath = home.resolve("index.json"); DesktopArchiveFiles.safe(indexPath, false)
        check(Files.size(indexPath) <= 256 * 1024)
        val index = backupJson.decodeFromJsonElement(Index.serializer(), strict(Files.readAllBytes(indexPath)))
        check(index.version == 1 && index.kind == "original-save-slot-source" && index.sourceCode in 84..86 &&
            index.sourceFormat in 1..2 && (index.sourceFormat == 2) == (index.sourceScope != null) &&
            (index.sourceFormat != 2 || index.sourceCode >= 85)) { "SLOT_CUSTODY_INDEX_SCHEMA" }
        check(listOf(index.archiveSha256, index.recordSha256, index.packageSha256).all(hash::matches) && sealName(index) == home.fileName.toString())
        check(index.recordPath.startsWith("entities/save_slots/") && index.recordPath.endsWith(".json")); DesktopBackupPolicy.path(index.recordPath)
        val record = home.resolve("record.json"); val source = home.resolve("source.cbsave")
        check(index.recordBytes in 1..DesktopFullBackupSource.MAX_FILE && index.packageBytes in 1..DesktopSaveSlotArchive.MAX_ARCHIVE)
        check(Files.size(record) == index.recordBytes && DesktopArchiveFiles.hash(record, DesktopFullBackupSource.MAX_FILE) == index.recordSha256)
        check(Files.size(source) == index.packageBytes && DesktopArchiveFiles.hash(source, DesktopSaveSlotArchive.MAX_ARCHIVE) == index.packageSha256)
        DesktopBackupRecords.requireSize(Files.size(record))
        val slot = decodeRecord(Files.readAllBytes(record))
        check(slot.schemaVersion == 8 && slot.packageRef != null && slot.id == index.slotId && slot.sessionId == index.sessionId)
        check(DesktopSaveSlotInspector(true).inspect(source).status == SlotInspectStatus.STRUCTURE_VALID) { "SLOT_CUSTODY_PACKAGE_INVALID" }
        val reference = requireNotNull(slot.packageRef)
        check(reference.byteLength <= 0 || reference.byteLength == index.packageBytes) { "SLOT_CUSTODY_PACKAGE_LENGTH" }
        val nested = manifest(source)
        DesktopSaveSlotLibraryEnvelope.requireCompatible(slot, nested, index.packageBytes)
        return index
    }

    private fun manifest(source: Path): SaveSlot = java.util.zip.ZipFile(source.toFile()).use { zip ->
        val bytes = zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).use { it.readNBytes(256 * 1024 + 1) }
        check(bytes.size <= 256 * 1024); decodeRecord(bytes)
    }

    private fun sealName(index: Index) = DesktopBackupPolicy.digest((index.archiveSha256 + "\u0000" + index.recordPath).toByteArray(Charsets.UTF_8))
    private fun strict(bytes: ByteArray): JsonObject {
        val out = java.io.StringWriter()
        bytes.inputStream().strictUtf8Reader().use { BackupJsonStream(it, out) { path, _ -> path }.copy() }
        return backupJson.parseToJsonElement(out.toString()).jsonObject
    }
    private fun decodeRecord(bytes: ByteArray) = backupJson.decodeFromJsonElement(SaveSlot.serializer(), strict(bytes))
    private fun write(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val data = ByteBuffer.wrap(bytes); while (data.hasRemaining()) channel.write(data); channel.force(true)
        }
    }
}
