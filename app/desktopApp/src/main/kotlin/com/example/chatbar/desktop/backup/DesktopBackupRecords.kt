package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Modern records are bounded by the available per-record working budget, not archive JSON totals. */
internal object DesktopBackupRecords {
    class ResourceLimit(val actual: Long, val limit: Long) : IllegalArgumentException("BACKUP_RECORD_MEMORY_BUDGET")
    val recordBudget: Long get() = minOf(DesktopFullBackupSource.MAX_FILE, Runtime.getRuntime().maxMemory() / 8)
    fun requireSize(size: Long) { if (size > recordBudget) throw ResourceLimit(size, recordBudget) }
    fun parse(bytes: ByteArray): JsonObject {
        requireSize(bytes.size.toLong())
        val writer = StringWriter()
        BackupJsonStream(bytes.inputStream().strictUtf8Reader(), writer) { value, _ -> value }.copy()
        return backupJson.parseToJsonElement(writer.toString()).jsonObject
    }
    fun read(file: Path, expected: String? = null): JsonObject {
        DesktopArchiveFiles.safe(file, false); requireSize(Files.size(file))
        val bytes = Files.readAllBytes(file)
        try {
            if (expected != null) check(DesktopBackupPolicy.digest(bytes) == expected) { "BACKUP_RECORD_CHANGED" }
            return parse(bytes)
        } finally { bytes.fill(0) }
    }
    fun entity(relative: String, file: Path): JsonObject {
        DesktopArchiveFiles.safe(file, false); requireSize(Files.size(file))
        val bytes = Files.readAllBytes(file)
        try { return DesktopBackupPolicy.entity(relative, bytes, false, 86, modern = true) }
        finally { bytes.fill(0) }
    }
    fun files(files: Map<String, Path>, hashes: Map<String, String>? = null): Map<String, JsonObject> =
        view(files.keys) { key -> read(files.getValue(key), hashes?.getValue(key)) }
    fun view(keys: Collection<String>, load: (String) -> JsonObject): Map<String, JsonObject> = object : AbstractMap<String, JsonObject>() {
        private val names = keys.toSet()
        override val keys: Set<String> get() = names
        override val size: Int get() = names.size
        override fun containsKey(key: String) = key in names
        override fun get(key: String): JsonObject? = if (key in names) load(key) else null
        override val entries: Set<Map.Entry<String, JsonObject>> get() = object : AbstractSet<Map.Entry<String, JsonObject>>() {
            override val size: Int get() = names.size
            override fun iterator(): Iterator<Map.Entry<String, JsonObject>> = names.map { name -> object : Map.Entry<String, JsonObject> {
                override val key = name
                override val value: JsonObject get() = load(name)
                override fun equals(other: Any?) = other is Map.Entry<*, *> && key == other.key && value == other.value
                override fun hashCode() = key.hashCode() xor value.hashCode()
            } }.iterator()
        }
    }
    /** Only fields consumed by the transport scope validator; full records were separately validated. */
    fun scopeRecord(value: JsonObject): JsonObject = JsonObject(buildMap {
        for (key in listOf("id", "sessionId")) value[key]?.let { put(key, it) }
        for (key in listOf("images", "generatedImageMetadata")) if ((value[key] as? JsonArray)?.isNotEmpty() == true)
            put(key, JsonArray(listOf(JsonNull)))
    })
}

/** Invocation-owned credential-free records. No parsed-record cache survives an individual access. */
internal class DesktopBackupRecordStore(private val scratch: DesktopBackupScratch) : AbstractMutableMap<String, JsonObject>() {
    private val files = linkedMapOf<String, Pair<Path, String>>()
    private var sequence = 0
    override val size: Int get() = files.size
    override fun containsKey(key: String) = files.containsKey(key)
    override fun get(key: String): JsonObject? = files[key]?.let { DesktopBackupRecords.read(it.first, it.second) }
    override fun put(key: String, value: JsonObject): JsonObject? {
        val old = get(key)
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        try {
            DesktopBackupRecords.requireSize(bytes.size.toLong())
            val file = scratch.directory.resolve("record-" + sequence++)
            scratch.write(file, bytes); files[key] = file to DesktopBackupPolicy.digest(bytes)
        } finally { bytes.fill(0) }
        return old
    }
    override val entries: MutableSet<MutableMap.MutableEntry<String, JsonObject>> get() = object : AbstractMutableSet<MutableMap.MutableEntry<String, JsonObject>>() {
        override val size: Int get() = files.size
        override fun add(element: MutableMap.MutableEntry<String, JsonObject>): Boolean = error("RECORD_ENTRY_ADD_UNSUPPORTED")
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, JsonObject>> {
            val iterator = files.keys.iterator()
            return object : MutableIterator<MutableMap.MutableEntry<String, JsonObject>> {
                override fun hasNext() = iterator.hasNext()
                override fun next(): MutableMap.MutableEntry<String, JsonObject> {
                    val name = iterator.next()
                    return object : MutableMap.MutableEntry<String, JsonObject> {
                        override val key = name
                        override val value: JsonObject get() = requireNotNull(get(name))
                        override fun setValue(newValue: JsonObject): JsonObject = requireNotNull(put(name, newValue))
                        override fun equals(other: Any?) = other is Map.Entry<*, *> && key == other.key && value == other.value
                        override fun hashCode() = key.hashCode() xor value.hashCode()
                    }
                }
                override fun remove() = iterator.remove()
            }
        }
    }
}
