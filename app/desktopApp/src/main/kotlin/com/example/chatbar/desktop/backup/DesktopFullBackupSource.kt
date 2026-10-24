package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import com.sun.nio.file.ExtendedOpenOption
import java.io.*
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Streaming production input. Tentative sinks may write only into a non-runnable owned workspace. */
internal object DesktopFullBackupSource {
    interface TentativeSink {
        fun entity(path: String, original: JsonObject, raw: ByteArray)
        fun preferences(original: JsonObject)
        fun media(path: String, input: InputStream)
    }
    class Verified internal constructor(val manifest: InspectionManifest, val inventory: Map<String, AppBackupFileRecord>,
        val encrypted: Boolean, val sourceHash: String, val sourceBytes: Long) {
        override fun toString() = "VerifiedFullBackup(code=${manifest.appVersionCode},files=${inventory.size})"
    }
    class Refusal(val stage: String) : IllegalArgumentException("FULL_BACKUP_SOURCE_$stage")

    fun verify(source: Path, password: CharArray?, sink: TentativeSink, active: () -> Unit = {}): Verified {
        var phase = "SOURCE_FILE"
        try {
            DesktopArchiveFiles.safe(source, false)
            val size = Files.size(source)
            require(size in 1..MAX_SOURCE)
            FileChannel.open(source, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)).use { channel ->
                val digest = MessageDigest.getInstance("SHA-256")
                val input = object : FilterInputStream(java.security.DigestInputStream(Channels.newInputStream(channel), digest)) {
                    override fun close() {} // Source lease closes only after all authentication and checks.
                }
                var envelope = 0; var encrypted = false
                var manifest: InspectionManifest? = null
                var manifestFields: Set<String> = emptySet(); var preferenceFields: Set<String> = emptySet()
                var preferencesHash: String? = null
                var inventory: Map<String, AppBackupFileRecord>? = null
                var structured = 0L
                val seen = linkedMapOf<String, AppBackupFileRecord>()
                val entities = linkedMapOf<String, JsonObject>()
                phase = "AUTHENTICATION_ZIP"
                AppBackupCodec.decryptingForInspection(input, password) { version, protected -> envelope = version; encrypted = protected }.use { plain ->
                    StrictBackupZipReader.walk(plain, active = active) { name, raw ->
                        active(); phase = "ENTRY"
                        val metadata = !name.startsWith("files/")
                        require(!metadata || name in setOf("manifest.json", "preferences.json", "inventory.jsonl"))
                        val path = if (metadata) name else DesktopBackupPolicy.path(name.removePrefix("files/"))
                        val hashing = CountingHashInput(raw)
                        if (metadata || path.startsWith("entities/")) {
                            phase = "STRUCTURED_ENTRY"
                            val bytes = bounded(hashing, DesktopBackupPolicy.MAX_JSON, active)
                            try {
                                structured += bytes.size; require(structured <= MAX_STRUCTURED)
                                if (name == "inventory.jsonl") {
                                    val records = linkedMapOf<String, AppBackupFileRecord>()
                                    bytes.inputStream().strictUtf8Reader().useLines { lines -> lines.filter(String::isNotBlank).forEach { line ->
                                        active(); require(line.length <= 4096 && records.size < 40000)
                                        val row = backupJson.decodeFromJsonElement<AppBackupFileRecord>(strict(line.toByteArray()))
                                        DesktopBackupPolicy.path(row.path)
                                        require(row.size in 0..MAX_FILE && row.sha256.matches(Regex("[a-f0-9]{64}")))
                                        require(records.put(row.path, row) == null)
                                    } }
                                    inventory = records
                                } else {
                                    val value = strict(bytes)
                                    when (name) {
                                        "manifest.json" -> { manifest = backupJson.decodeFromJsonElement(value); manifestFields = value.keys.toSet() }
                                        "preferences.json" -> {
                                            preferenceFields = value.keys.toSet()
                                            val preferences = backupJson.decodeFromJsonElement<InspectionPreferences>(value)
                                            require(preferences.brushSize.isFinite() && preferences.brushSize > 0 && preferences.brushType.length <= 100)
                                            // Official147 BackupArchive forbids these preparation-only local
                                            // instructions in an incoming archive. Target retention is derived
                                            // later from authenticated scope and the user's choices.
                                            require(preferences.retainedPreferences.isEmpty())
                                            sink.preferences(value)
                                        }
                                        else -> {
                                            require(path.endsWith(".json"))
                                            entities[path] = value
                                            sink.entity(path, value, bytes)
                                        }
                                    }
                                }
                            } finally { bytes.fill(0) }
                        } else sink.media(path, hashing)
                        // Consumers may read less, but every authenticated source byte participates
                        // in CRC/inventory verification before anything can become Verified.
                        drain(hashing, active)
                        if (metadata) {
                            if (name == "preferences.json") preferencesHash = hashing.hash()
                        } else seen[path] = AppBackupFileRecord(path, hashing.size, hashing.hash())
                        phase = "AUTHENTICATION_ZIP"
                    }
                }
                phase = "MANIFEST_INVENTORY"
                val m = requireNotNull(manifest)
                require(m.formatVersion == envelope && m.appVersionCode in 84..86 && (envelope != 2 || m.appVersionCode >= 85))
                require((envelope == 2) == (m.scope != null))
                if (m.appVersionCode == 84) require("scope" !in manifestFields && "retainedPreferences" !in preferenceFields)
                require(preferencesHash != null && m.preferencesSha256 == preferencesHash && inventory == seen)
                require(m.fileCount == seen.size && m.totalBytes == seen.values.sumOf { it.size })
                val counts = entities.keys.groupingBy(DesktopBackupPolicy::entityType).eachCount()
                require(m.counts == counts)
                phase = "ENTITY_SCHEMA"
                entities.forEach { (path, value) ->
                    active()
                    when (val type = DesktopBackupPolicy.entityType(path)) {
                        "moment_posts" -> backupJson.decodeFromJsonElement<MomentPostSnapshot>(value)
                        "moment_tasks" -> backupJson.decodeFromJsonElement<MomentTaskSnapshot>(value)
                        else -> DesktopOfficialBackupEntitySchema.validate(type, value, m.appVersionCode)
                    }
                }
                phase = "SELECTION_SCOPE"
                m.scope?.let { DesktopBackupReadOnlyInspector().scopeReport(it, counts, entities) }
                active()
                require(channel.position() == size && Files.size(source) == size)
                return Verified(m, seen.toMap(), encrypted, hex(digest.digest()), size)
            }
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
        } catch (failure: StrictBackupZipReader.Refusal) {
            throw Refusal("ZIP_${failure.phase}")
        } catch (_: Exception) { throw Refusal(phase) } // No source text, credentials or crypto snippets escape.
    }

    private class CountingHashInput(source: InputStream) : FilterInputStream(source) {
        private val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L; private set
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
            if (it > 0) { size += it; digest.update(bytes, offset, it) }
        }
        override fun close() {}
        fun hash() = hex(digest.digest())
    }
    private fun strict(bytes: ByteArray): JsonObject {
        val output = StringWriter()
        BackupJsonStream(bytes.inputStream().strictUtf8Reader(), output) { value, _ -> value }.copy()
        return backupJson.parseToJsonElement(output.toString()).jsonObject
    }
    private fun bounded(input: InputStream, maximum: Int, active: () -> Unit): ByteArray {
        val buffer = ByteArray(32768)
        val out = object : ByteArrayOutputStream() { fun wipe() { buf.fill(0); reset() } }
        try {
            while (true) { active(); val count = input.read(buffer); if (count < 0) break
                require(count <= maximum - out.size()); out.write(buffer, 0, count)
            }
            return out.toByteArray()
        } finally { buffer.fill(0); out.wipe() }
    }
    private fun drain(input: InputStream, active: () -> Unit) {
        val buffer = ByteArray(32768)
        try { while (input.read(buffer) >= 0) active() } finally { buffer.fill(0) }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    const val MAX_SOURCE = 4L * 1024 * 1024 * 1024
    const val MAX_FILE = 256L * 1024 * 1024
    const val MAX_STRUCTURED = 64L * 1024 * 1024
}
