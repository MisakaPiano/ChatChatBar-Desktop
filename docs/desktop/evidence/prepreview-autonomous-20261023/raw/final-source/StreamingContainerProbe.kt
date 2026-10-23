package com.example.chatbar.desktop.backup

import java.io.*
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/** Supplementary local-header CRC/inventory audit, NOT S0 or a trusted Entity graph.
 * Reuses pinned production transport DTOs/UTF-8/JSON lexer and streaming codec.
 * No raw body, path, source digest or key leaves this method.
 */
internal object StreamingContainerProbe {
    data class Summary(val appCode: Int, val format: Int, val fileCount: Int, val selective: Boolean,
        val entityCounts: Map<String, Int>, val unknownEntityFiles: Int)

    fun consume(source: InputStream, password: CharArray?): Summary = source.use { raw ->
        var version = 0
        AppBackupCodec.decryptingForInspection(raw, password) { v, _ -> version = v }.use { plain ->
            val names = hashSetOf<String>()
            val records = linkedMapOf<String, AppBackupFileRecord>()
            var inventory: Map<String, AppBackupFileRecord>? = null
            var manifest: InspectionManifest? = null
            var preferences: String? = null
            var total = 0L
            // Closing the ZIP reader must not close the authenticated stream before its EOF drain.
            ZipInputStream(object : FilterInputStream(plain) { override fun close() {} }).use { zip ->
                while (true) {
                    if (Thread.currentThread().isInterrupted) error("CANCELLED")
                    val entry = zip.nextEntry ?: break
                    check(!entry.isDirectory && names.size < 40003 && names.add(entry.name.lowercase(Locale.ROOT)))
                    val metadata = !entry.name.startsWith("files/")
                    val path = if (metadata) entry.name else DesktopBackupPolicy.path(entry.name.removePrefix("files/"))
                    check(!metadata || path in setOf("manifest.json", "preferences.json", "inventory.jsonl"))
                    val bytes = if (path in setOf("manifest.json", "inventory.jsonl")) ByteArrayOutputStream() else null
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    var size = 0L
                    try {
                        while (true) {
                            if (Thread.currentThread().isInterrupted) error("CANCELLED")
                            val n = zip.read(buffer)
                            if (n < 0) break
                            size += n; total += n
                            check(size <= (if (metadata) 8L * 1024 * 1024 else 256L * 1024 * 1024))
                            check(total <= 4L * 1024 * 1024 * 1024)
                            digest.update(buffer, 0, n); bytes?.write(buffer, 0, n)
                        }
                    } finally { buffer.fill(0) }
                    zip.closeEntry() // ZipInputStream checks local CRC and uncompressed size.
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!metadata) records[path] = AppBackupFileRecord(path, size, sha)
                    else when (path) {
                        "preferences.json" -> preferences = sha // Never parse or retain its credential values.
                        "manifest.json" -> manifest = backupJson.decodeFromString<InspectionManifest>(strict(requireNotNull(bytes).toByteArray()))
                        "inventory.jsonl" -> {
                            val rows = linkedMapOf<String, AppBackupFileRecord>()
                            requireNotNull(bytes).toByteArray().inputStream().strictUtf8Reader().useLines { lines ->
                                lines.filter { it.isNotBlank() }.forEach { line ->
                                    check(line.length <= 4096 && rows.size < 40000)
                                    val row = backupJson.decodeFromString<AppBackupFileRecord>(strict(line.toByteArray()))
                                    DesktopBackupPolicy.path(row.path)
                                    check(row.size in 0..(256L * 1024 * 1024) && row.sha256.matches(Regex("[a-f0-9]{64}")))
                                    check(rows.put(row.path, row) == null)
                                }
                            }
                            inventory = rows
                        }
                    }
                }
            }
            // ZIP EOF is not authenticated stream EOF. Consume trailing central bytes/MAC before publication.
            val tail = ByteArray(65536)
            var trailing = 0L
            try { while (true) {
                if (Thread.currentThread().isInterrupted) error("CANCELLED")
                val n = plain.read(tail); if (n < 0) break
                trailing += n; check(trailing <= 8L * 1024 * 1024)
            } } finally { tail.fill(0) }
            val m = requireNotNull(manifest)
            check(m.formatVersion == version && m.appVersionCode in 84..86 && (version != 2 || m.appVersionCode >= 85))
            check((version == 2) == (m.scope != null))
            check(inventory == records && preferences != null && preferences == m.preferencesSha256)
            check(m.fileCount == records.size && m.totalBytes == records.values.sumOf { it.size })
            val counts = records.keys.filter { it.startsWith("entities/") }.groupingBy(DesktopBackupPolicy::entityType).eachCount()
            check(m.counts == counts)
            val known = DesktopBackupPolicy.schemas.keys + setOf("moment_posts", "moment_tasks")
            Summary(m.appVersionCode, version, records.size, m.scope != null,
                counts.filterKeys { it in known }, counts.filterKeys { it !in known }.values.sum())
        }
    }
    private fun strict(bytes: ByteArray): String = try {
        StringWriter().also { out -> BackupJsonStream(bytes.inputStream().strictUtf8Reader(), out) { value, _ -> value }.copy() }.toString()
    } finally { bytes.fill(0) }
}
