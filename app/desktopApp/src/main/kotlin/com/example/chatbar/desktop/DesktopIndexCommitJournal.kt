package com.example.chatbar.desktop

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Document index publication only. Official character/vector JSON stays unchanged.
 * The caller owns the root and exclusive data admission. A forced undo intent precedes every
 * live write; startup replays incomplete intents before any repository/cache is constructed.
 * Terminal journals are retained in the snapshot infrastructure's reserved workspace.
 */
internal class DesktopIndexCommitJournal(private val root: Path,
    private val afterWrite: (Int) -> Unit = {},
) {
    private val home = root.resolve("backups/.restore-document-index.tmp")
    private val paths = DesktopDocumentRagSource(root)
    private data class Row(val path: String, val old: String?, val next: String?)

    suspend fun commit(changes: Map<String, ByteArray?>) {
        check(DesktopDataRootOwnership.isHeld(root))
        require(changes.isNotEmpty() && changes.size <= MAX_FILES)
        require(changes.values.sumOf { it?.size?.toLong() ?: 0L } <= MAX_BYTES)
        prepareHome()
        val work = Files.createDirectory(home.resolve("active-${UUID.randomUUID()}"))
        var oldBytes = 0L
        val rows = changes.entries.mapIndexed { index, (relative, bytes) ->
            val target = live(relative)
            val old = readOptional(target)
            oldBytes += old?.size ?: 0
            require(oldBytes <= MAX_BYTES)
            require(bytes == null || bytes.size <= MAX_FILE)
            old?.let { writeNew(work.resolve("$index.old"), it) }
            bytes?.let { writeNew(work.resolve("$index.next"), it) }
            Row(relative, old?.let(::hash), bytes?.let(::hash))
        }
        val intent = buildJsonObject {
            put("version", 1)
            put("rows", buildJsonArray { rows.forEach { row -> add(buildJsonObject {
                put("path", row.path); put("old", row.old?.let(::JsonPrimitive) ?: JsonNull)
                put("next", row.next?.let(::JsonPrimitive) ?: JsonNull)
            }) } })
        }.toString().toByteArray(Charsets.UTF_8)
        require(intent.size <= MAX_INTENT)
        writeNew(work.resolve("intent.part"), intent)
        Files.move(work.resolve("intent.part"), work.resolve("intent.json"), StandardCopyOption.ATOMIC_MOVE)
        // From this point an incomplete transaction always has complete immutable undo evidence.
        try {
            rows.forEachIndexed { index, row ->
                check(readOptional(live(row.path))?.let(::hash) == row.old) { "Index source changed before publication" }
                install(live(row.path), changes.getValue(row.path))
                afterWrite(index)
            }
            rows.forEach { row -> check(readOptional(live(row.path))?.let(::hash) == row.next) }
            writeNew(work.resolve("committed"), hash(intent).toByteArray(Charsets.US_ASCII))
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { rollback(work, rows) } } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
        finish(work)
    }

    /** No network, settings, credential or entity decoder; only the exact sealed file set. */
    suspend fun recover() {
        check(DesktopDataRootOwnership.isHeld(root))
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) return
        safe(home, directory = true)
        val entries = Files.newDirectoryStream(home).use { it.toList() }
        check(entries.size <= MAX_TRANSACTIONS) { "Index recovery inventory exceeds budget" }
        for (work in entries) {
            safe(work, directory = true)
            val name = work.fileName.toString()
            check(name.matches(Regex("(active|done)-[0-9a-f-]{36}"))) { "Unknown index recovery workspace" }
            if (name.startsWith("done-")) continue
            val intent = readOptional(work.resolve("intent.json"), MAX_INTENT) ?: continue
            val rows = decode(intent)
            verifyEvidence(work, rows)
            val committed = readOptional(work.resolve("committed"), 64)
            if (committed != null) {
                check(committed.toString(Charsets.US_ASCII) == hash(intent)) { "Invalid index commit marker" }
                rows.forEach { row -> check(readOptional(live(row.path))?.let(::hash) == row.next) }
            } else rollback(work, rows)
            finish(work)
        }
    }

    fun hasPending(): Boolean = Files.exists(home, LinkOption.NOFOLLOW_LINKS) &&
        Files.newDirectoryStream(home, "active-*").use { entries ->
            entries.any { Files.exists(it.resolve("intent.json"), LinkOption.NOFOLLOW_LINKS) }
        }

    private suspend fun rollback(work: Path, rows: List<Row>) {
        verifyEvidence(work, rows)
        // Prove the ENTIRE live set before undoing anything. Unknown concurrent bytes are retained.
        rows.forEach { row ->
            val current = readOptional(live(row.path))?.let(::hash)
            check(current == row.old || current == row.next) { "Index recovery found unknown live data; retained" }
        }
        rows.forEachIndexed { index, row ->
            install(live(row.path), row.old?.let { readOptional(work.resolve("$index.old"))!! })
        }
        rows.forEach { row -> check(readOptional(live(row.path))?.let(::hash) == row.old) }
        finish(work)
    }

    private suspend fun verifyEvidence(work: Path, rows: List<Row>) {
        var bytes = 0L
        rows.forEachIndexed { index, row ->
            for ((suffix, expected) in listOf("old" to row.old, "next" to row.next)) {
                val actual = readOptional(work.resolve("$index.$suffix"))
                check(actual?.let(::hash) == expected) { "Index recovery evidence changed" }
                bytes += actual?.size ?: 0
                check(bytes <= MAX_BYTES * 2)
            }
        }
    }

    private fun decode(bytes: ByteArray): List<Row> {
        val obj = Json.parseToJsonElement(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()).jsonObject
        check(obj.keys == setOf("version", "rows") && obj.getValue("version").jsonPrimitive.int == 1)
        val rows = obj.getValue("rows").jsonArray
        check(rows.size in 1..MAX_FILES)
        val parsed = rows.map { value ->
            val row = value.jsonObject
            check(row.keys == setOf("path", "old", "next"))
            fun digest(key: String): String? = row.getValue(key).jsonPrimitive.contentOrNull?.also {
                check(it.matches(Regex("[0-9a-f]{64}")))
            }
            Row(row.getValue("path").jsonPrimitive.content.also { live(it) }, digest("old"), digest("next"))
        }
        check(parsed.map { it.path.lowercase() }.distinct().size == parsed.size)
        return parsed
    }

    private fun finish(work: Path) {
        if (Files.exists(work, LinkOption.NOFOLLOW_LINKS))
            Files.move(work, work.resolveSibling(work.fileName.toString().replaceFirst("active-", "done-")), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun live(relative: String): Path {
        check(relative.matches(Regex("entities/(character_cards|vector_chunks)/[A-Za-z0-9_-]{1,256}\\.json")))
        val target = root.resolve(relative)
        safe(target.parent, directory = true, absent = true)
        return target
    }
    private fun prepareHome() {
        safe(root, directory = true)
        val backups = root.resolve("backups")
        if (!Files.exists(backups, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(backups)
        safe(backups, directory = true)
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(home)
        safe(home, directory = true)
        check(Files.newDirectoryStream(home).use { it.count() } < MAX_TRANSACTIONS)
    }
    private fun safe(path: Path, directory: Boolean, absent: Boolean = false) {
        var current = path.toAbsolutePath().normalize().root
        for (part in path.toAbsolutePath().normalize()) {
            current = current.resolve(part)
            if (absent && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return
            val attr = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(!attr.isSymbolicLink && !attr.isOther &&
                (if (current != path || directory) attr.isDirectory else attr.isRegularFile)) { "Unsafe index path" }
            paths.safeWindowsPath(current)
        }
    }
    private suspend fun readOptional(path: Path, max: Int = MAX_FILE): ByteArray? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        safe(path, directory = false)
        check(Files.size(path) <= max)
        if (System.getProperty("os.name").startsWith("Windows")) {
            val evidence = DesktopDocumentIndexReadEvidence({})
            val stamp = evidence.observe(path, directory = false)
            check(stamp.size <= max)
            val output = java.io.ByteArrayOutputStream(stamp.size.toInt())
            evidence.read(path, stamp) { bytes, count -> output.write(bytes, 0, count) }
            return output.toByteArray()
        }
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(max + 1) }.also { check(it.size <= max) }
    }
    private fun writeNew(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { file ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) file.write(buffer)
            file.force(true)
        }
    }
    private fun install(path: Path, bytes: ByteArray?) {
        safe(path.parent, directory = true, absent = true)
        Files.createDirectories(path.parent)
        safe(path.parent, directory = true)
        if (bytes == null) { Files.deleteIfExists(path); return }
        val part = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.part")
        try {
            writeNew(part, bytes)
            Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(part) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        private const val MAX_FILE = 8 * 1024 * 1024
        private const val MAX_INTENT = 4 * 1024 * 1024
        private const val MAX_FILES = 8192
        private const val MAX_TRANSACTIONS = 4096
    }
}
