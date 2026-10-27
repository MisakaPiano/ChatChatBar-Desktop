package com.example.chatbar.desktop

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** P11 session/SaveSlot publication only. Caller holds exclusive admission and the selected root lock.
 * New media is published with the entity graph; old media is retained, never swept by restore.
 * Recovery checks every live hash before changing any file and retains unknown bytes.
 */
internal class DesktopSessionCommitJournal(private val root: Path, private val afterWrite: (Int) -> Unit = {}) {
    private val home = root.resolve("backups/.restore-session.tmp")
    private data class Row(val path: String, val old: String?, val next: String?)

    suspend fun commit(changes: Map<String, Path?>, expected: Map<String, String?>) {
        check(DesktopDataRootOwnership.isHeld(root))
        check(changes.isNotEmpty() && changes.keys == expected.keys && changes.size <= MAX_FILES)
        check(changes.keys.map(String::lowercase).distinct().size == changes.size)
        prepareDirectories(home)
        val sizes = changes.map { (relative, source) ->
            val target = live(relative)
            val oldSize = if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                DesktopArchiveFiles.safe(target, false); Files.size(target)
            } else 0L
            val nextSize = source?.let { DesktopArchiveFiles.safe(it, false); Files.size(it) } ?: 0L
            check(oldSize <= MAX_FILE && nextSize <= MAX_FILE)
            oldSize to nextSize
        }
        val stagedBytes = sizes.sumOf { it.first + it.second }
        check(stagedBytes <= MAX_BYTES)
        check(Files.getFileStore(home).usableSpace >= stagedBytes + (sizes.maxOfOrNull { it.second } ?: 0L) + MAX_INTENT) {
            "磁盘空间不足以同时保留恢复证据与提交文件"
        }
        check(Files.newDirectoryStream(home).use { it.count() } < 4096)
        val work = Files.createDirectory(home.resolve("active-${UUID.randomUUID()}"))
        var total = 0L
        val rows = changes.entries.mapIndexed { index, (relative, source) ->
            val target = live(relative)
            val previous = hashOrNull(target)
            check(previous == expected.getValue(relative)) { "会话数据已变化，请重新准备；原数据保留" }
            if (previous != null) {
                total += Files.size(target); check(total <= MAX_BYTES)
                check(DesktopArchiveFiles.copyNew(target, work.resolve("$index.old"), MAX_FILE) == previous)
            }
            val next = source?.let {
                total += Files.size(it); check(total <= MAX_BYTES)
                DesktopArchiveFiles.copyNew(it, work.resolve("$index.next"), MAX_FILE)
            }
            if (!relative.startsWith("entities/")) check(previous == null && next != null || PACKAGE.matches(relative) && previous != null && next == null)
            Row(relative, previous, next)
        }
        validatePackageDeletions(work, rows)
        val intent = buildJsonObject {
            put("version", 1)
            put("rows", buildJsonArray { rows.forEach { row -> add(buildJsonObject {
                put("path", row.path); put("old", row.old?.let(::JsonPrimitive) ?: JsonNull)
                put("next", row.next?.let(::JsonPrimitive) ?: JsonNull)
            }) } })
        }.toString().toByteArray()
        check(intent.size <= MAX_INTENT)
        writeNew(work.resolve("intent.part"), intent)
        Files.move(work.resolve("intent.part"), work.resolve("intent.json"), StandardCopyOption.ATOMIC_MOVE)
        withContext(NonCancellable) {
            try {
                rows.forEachIndexed { index, row ->
                    check(hashOrNull(live(row.path)) == row.old) { "会话数据在提交前变化" }
                    install(live(row.path), row.next?.let { work.resolve("$index.next") })
                    afterWrite(index)
                }
                rows.forEach { check(hashOrNull(live(it.path)) == it.next) }
                writeNew(work.resolve("commit.part"), DesktopArchiveFiles.hash(work.resolve("intent.json"), MAX_INTENT.toLong()).toByteArray())
                Files.move(work.resolve("commit.part"), work.resolve("committed"), StandardCopyOption.ATOMIC_MOVE)
                finish(work)
            } catch (error: Throwable) {
                if (!Files.exists(work.resolve("committed"), LinkOption.NOFOLLOW_LINKS)) {
                    try { rollback(work, rows) } catch (recovery: Throwable) { error.addSuppressed(recovery) }
                }
                throw error
            }
        }
    }

    suspend fun recover() {
        check(DesktopDataRootOwnership.isHeld(root))
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) return
        DesktopArchiveFiles.safe(home, true)
        val pending = Files.newDirectoryStream(home, "active-*").use { it.toList() }
        check(pending.size <= 4096)
        for (work in pending) {
            DesktopArchiveFiles.safe(work, true)
            val intent = work.resolve("intent.json")
            if (!Files.exists(intent, LinkOption.NOFOLLOW_LINKS)) continue // Preparation did not authorize live writes.
            val rows = decode(intent)
            verifyEvidence(work, rows)
            val committed = work.resolve("committed")
            if (Files.exists(committed, LinkOption.NOFOLLOW_LINKS)) {
                DesktopArchiveFiles.safe(committed, false); check(Files.size(committed) == 64L)
                check(Files.readString(committed) == DesktopArchiveFiles.hash(intent, MAX_INTENT.toLong()))
                rows.forEach { check(hashOrNull(live(it.path)) == it.next) }
                finish(work)
            } else rollback(work, rows)
        }
    }

    fun hasPending(): Boolean = Files.exists(home, LinkOption.NOFOLLOW_LINKS) &&
        Files.newDirectoryStream(home, "active-*").use { entries -> entries.any { Files.exists(it.resolve("intent.json"), LinkOption.NOFOLLOW_LINKS) } }

    private suspend fun rollback(work: Path, rows: List<Row>) {
        verifyEvidence(work, rows)
        rows.forEach { row -> check(hashOrNull(live(row.path)) in setOf(row.old, row.next)) { "恢复发现未知内容；保留现场" } }
        rows.forEachIndexed { index, row -> install(live(row.path), row.old?.let { work.resolve("$index.old") }) }
        rows.forEach { check(hashOrNull(live(it.path)) == it.old) }
        finish(work)
    }

    private suspend fun verifyEvidence(work: Path, rows: List<Row>) {
        var bytes = 0L
        rows.forEachIndexed { index, row ->
            for ((suffix, expected) in listOf("old" to row.old, "next" to row.next)) {
                val path = work.resolve("$index.$suffix")
                check(hashOrNull(path) == expected) { "恢复证据发生变化" }
                if (expected != null) { bytes += Files.size(path); check(bytes <= MAX_BYTES) }
            }
        }
    }

    private fun decode(path: Path): List<Row> {
        DesktopArchiveFiles.safe(path, false); check(Files.size(path) <= MAX_INTENT)
        val obj = Json.parseToJsonElement(Files.readString(path)).jsonObject
        check(obj.keys == setOf("version", "rows") && obj.getValue("version").jsonPrimitive.int == 1)
        val values = obj.getValue("rows").jsonArray; check(values.size in 1..MAX_FILES)
        val rows = values.map { value ->
            val row = value.jsonObject; check(row.keys == setOf("path", "old", "next"))
            fun hash(key: String) = row.getValue(key).jsonPrimitive.contentOrNull?.also { check(it.matches(Regex("[0-9a-f]{64}"))) }
            val relative = row.getValue("path").jsonPrimitive.content.also(::live)
            Row(relative, hash("old"), hash("next")).also {
                if (!relative.startsWith("entities/")) check(it.old == null && it.next != null || PACKAGE.matches(relative) && it.old != null && it.next == null)
            }
        }
        check(rows.map { it.path.lowercase() }.distinct().size == rows.size)
        validatePackageDeletions(path.parent, rows)
        return rows
    }

    private fun validatePackageDeletions(work: Path, rows: List<Row>) {
        val deleted = rows.filter { PACKAGE.matches(it.path) && it.next == null }
        if (deleted.isEmpty()) return
        val referenced = hashSetOf<String>()
        rows.forEachIndexed { index, row ->
            if (row.path.startsWith("entities/save_slots/") && row.old != null && row.next == null) {
                val old = work.resolve("$index.old")
                DesktopArchiveFiles.safe(old, false); check(Files.size(old) <= MAX_FILE)
                Files.newInputStream(old, LinkOption.NOFOLLOW_LINKS).use { input ->
                    DesktopSaveSlotSummaryReader.read(row.path.substringAfterLast('/').removeSuffix(".json"), input) {
                        it?.let { referenced.add("save_slot_packages/$it") }
                    }
                }
            }
        }
        check(deleted.all { it.old != null && it.path in referenced }) { "删除存档包缺少对应存档记录证据" }
    }

    private fun live(relative: String): Path {
        check(ENTITY.matches(relative) || MEDIA.matches(relative) || PACKAGE.matches(relative)) { "不属于会话事务的路径" }
        val target = root.resolve(relative)
        var parent = target.parent
        while (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) parent = parent.parent
        DesktopArchiveFiles.safe(parent, true)
        return target
    }
    private suspend fun hashOrNull(path: Path): String? =
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) null else DesktopArchiveFiles.hash(path, MAX_FILE)
    private fun prepareDirectories(path: Path) {
        var ancestor = path
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.parent
        DesktopArchiveFiles.safe(ancestor, true)
        Files.createDirectories(path); DesktopArchiveFiles.safe(path, true)
    }
    private suspend fun install(target: Path, source: Path?) {
        prepareDirectories(target.parent)
        if (source == null) { Files.deleteIfExists(target); return }
        val part = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
        DesktopArchiveFiles.copyNew(source, part, MAX_FILE)
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun finish(work: Path) {
        if (Files.exists(work)) Files.move(work, work.resolveSibling(work.fileName.toString().replaceFirst("active-", "done-")), StandardCopyOption.ATOMIC_MOVE)
    }
    private fun writeNew(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { file ->
            val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true)
        }
    }
    companion object {
        private const val MAX_FILES = 40000
        private const val MAX_INTENT = 12 * 1024 * 1024
        private const val MAX_FILE = 256L * 1024 * 1024
        private const val MAX_BYTES = 512L * 1024 * 1024
        private val ENTITY = Regex("entities/(chat_sessions|chat_messages|chat_message_indexes|chat_message_order_backups|chat_drafts|chat_scroll_positions|vector_chunks|memory_states|memory_nodes|memory_tier_revisions|memory_compression_transactions|memory_commits|memory_commit_journals|generated_voice_messages|voice_anchor_states|save_slots)/[A-Za-z0-9_-]{1,256}\\.json")
        private val MEDIA = Regex("((images/save_slots|audio/generated)/[A-Za-z0-9_-]{1,128}/|images/)[A-Za-z0-9][A-Za-z0-9_.-]{0,179}")
        private val PACKAGE = Regex("save_slot_packages/[A-Za-z0-9_-]{1,128}\\.cbsave")
    }
}
