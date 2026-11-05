package com.example.chatbar.desktop

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DesktopPresetCustodyRecord(val id: String, val kind: String, val phase: String,
    val hash: String? = null, val bytes: Long? = null, val directory: String? = null,
    val files: List<String> = emptyList())

/** Write-before-publish exact ownership only. Unknown publication or references are never deletion permission. */
internal class DesktopPresetCustody(private val root: Path) {
    private val home = root.resolve("backups/.studio-preset-custody")
    private val json = Json { encodeDefaults = true }
    private fun file(id: String): Path { require(UUID.fromString(id).toString() == id); return home.resolve("$id.json") }
    private fun ensureHome() {
        DesktopArchiveFiles.safe(root, true)
        for (directory in listOf(root.resolve("backups"), home)) {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
            DesktopArchiveFiles.safe(directory, true)
        }
    }
    fun save(record: DesktopPresetCustodyRecord) {
        ensureHome(); val target = file(record.id)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            DesktopArchiveFiles.safe(target, false)
            val old = read(target)
            require(old.kind == record.kind && old.hash == record.hash && old.bytes == record.bytes &&
                old.directory == record.directory && old.files == record.files)
        }
        val temporary = home.resolve(".${record.id}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(json.encodeToString(DesktopPresetCustodyRecord.serializer(), record).toByteArray())
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun read(path: Path): DesktopPresetCustodyRecord {
        DesktopArchiveFiles.safe(path, false); require(Files.size(path) <= 32768)
        return json.decodeFromString(DesktopPresetCustodyRecord.serializer(), Files.readString(path)).also { record ->
            require(file(record.id) == path)
            require(record.kind in setOf("PAYLOAD", "STAGING"))
            if (record.kind == "PAYLOAD") require(record.hash?.matches(Regex("[a-f0-9]{64}")) == true && record.bytes != null && record.bytes > 0)
            else {
                require(record.directory?.let { UUID.fromString(it).toString() == it } == true)
                require(record.files.size <= 35 && record.files.all { it.matches(Regex("[a-f0-9]{64}\\.(image|vibe)")) })
            }
        }
    }
    fun stage(directory: Path, files: Collection<Path>) = DesktopPresetCustodyRecord(UUID.randomUUID().toString(), "STAGING", "PREVIEW",
        directory = directory.fileName.toString(), files = files.map { it.fileName.toString() }).also(::save)
    fun beforePayload(reference: String, bytes: Long): DesktopPresetCustodyRecord = DesktopPresetCustodyRecord(
        UUID.randomUUID().toString(), "PAYLOAD", "PUBLISH_ATTEMPTED", hash = reference.substringAfter(':'), bytes = bytes).also(::save)
    fun published(record: DesktopPresetCustodyRecord, created: Boolean) {
        if (created) save(record.copy(phase = "CREATED")) else remove(record)
    }
    fun remove(record: DesktopPresetCustodyRecord) {
        val path = file(record.id)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(read(path).copy(phase = record.phase) == record); Files.delete(path)
        }
    }
    fun records(): List<DesktopPresetCustodyRecord> {
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        DesktopArchiveFiles.safe(home, true)
        return Files.newDirectoryStream(home, "*.json").use { files -> files.map(::read).toList() }
    }

    suspend fun finishPayloads(cleanup: suspend (List<String>) -> Unit, ids: Set<String>,
        committed: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        var complete = true
        for (record in records().filter { it.kind == "PAYLOAD" && it.id in ids }) {
            if (committed && record.phase == "CREATED") { remove(record); continue }
            if (record.phase == "PUBLISH_ATTEMPTED") { complete = false; continue }
            val path = "novelai-vibe-payloads/${record.hash}"
            if (record.phase !in setOf("CREATED", "RETAINED", "UNCONFIRMED")) { complete = false; continue }
            try {
                cleanup(listOf(path))
                if (Files.exists(root.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                    save(record.copy(phase = "RETAINED"))
                } else remove(record)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { complete = false; save(record.copy(phase = "UNCONFIRMED")) }
        }
        complete
    }
}
