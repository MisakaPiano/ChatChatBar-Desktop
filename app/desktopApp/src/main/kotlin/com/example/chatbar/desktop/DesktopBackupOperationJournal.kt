package com.example.chatbar.desktop

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Local presentation evidence only. Never grants restore, retry, cleanup or key authority. */
internal class DesktopBackupOperationJournal(private val root: Path, private val lane: Lane) {
    enum class Lane { BACKUP, MERGE }
    @Serializable enum class Kind { EXPORT, CHECK, RESTORE, MERGE_CHECK, MERGE_COMMIT }
    @Serializable enum class Phase { RUNNING, WAITING, PREVIEW, RESTART, COMPLETE, CANCELLED, FAILED, UNCONFIRMED }
    data class Ticket internal constructor(internal val id: String, internal val kind: Kind)
    @Serializable private data class Record(val version: Int = 1, val id: String, val kind: Kind, val phase: Phase)
    // Ordinary files in the existing infrastructure root: excluded from data snapshots,
    // and never mistaken for a snapshot directory by the backup browser.
    private val directory = root.resolve("backups")
    private val file = directory.resolve("backup-operation-${lane.name.lowercase()}.json")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun previousNotice(): String? = try {
        when (read()?.phase) {
            Phase.RUNNING, Phase.WAITING, Phase.PREVIEW, Phase.RESTART, Phase.UNCONFIRMED ->
                "上次备份或导入的最终结果尚未确认，可能曾被中断。请核对保存的文件、当前资料和恢复提示；不会自动重试或清理恢复记录。"
            Phase.FAILED -> "上次备份或导入报告未完成，请核对文件、当前资料和已有提示。"
            else -> null
        }
    } catch (_: Exception) { STATUS_UNAVAILABLE }

    fun previousResult(): String? = try {
        read()?.let { record ->
            val action = when (record.kind) {
                Kind.EXPORT -> "备份导出"; Kind.CHECK -> "备份检查"; Kind.RESTORE -> "恢复准备"
                Kind.MERGE_CHECK -> "合并检查"; Kind.MERGE_COMMIT -> "合并准备"
            }
            when (record.phase) {
                Phase.COMPLETE -> "上次${action}已记录完成。"
                Phase.CANCELLED -> "上次${action}已取消。"
                else -> null
            }
        }
    } catch (_: Exception) { null } // The paired previousNotice exposes unreadable status.

    @Synchronized fun begin(kind: Kind): Ticket {
        read() // Never overwrite an unknown/corrupt pre-existing record silently.
        val ticket = Ticket(UUID.randomUUID().toString(), kind)
        write(Record(id = ticket.id, kind = kind, phase = Phase.RUNNING))
        return ticket
    }

    @Synchronized fun finish(ticket: Ticket, phase: Phase) {
        require(phase != Phase.RUNNING)
        val previous = read() ?: error("BACKUP_OPERATION_STATUS_MISSING")
        if (previous.id != ticket.id || previous.kind != ticket.kind) return // A late old callback cannot replace a new task.
        write(previous.copy(phase = phase))
    }

    @Synchronized fun abandonPreview(ticket: Ticket) {
        val previous = read() ?: return
        if (previous.id == ticket.id && previous.kind == ticket.kind && previous.phase in setOf(Phase.WAITING, Phase.PREVIEW))
            write(previous.copy(phase = Phase.CANCELLED))
    }

    private fun read(): Record? {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null
        DesktopArchiveFiles.safe(directory, true)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        DesktopArchiveFiles.safe(file, false)
        val bytes = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(2049) }
        require(bytes.size in 1..2048)
        return json.decodeFromString(Record.serializer(), bytes.toString(Charsets.UTF_8)).also {
            require(it.version == 1 && UUID.fromString(it.id).toString() == it.id)
        }
    }

    private fun write(record: Record) {
        DesktopArchiveFiles.safe(root, true)
        Files.createDirectories(directory); DesktopArchiveFiles.safe(directory, true)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) DesktopArchiveFiles.safe(file, false)
        val bytes = json.encodeToString(Record.serializer(), record).toByteArray()
        val temporary = directory.resolve("backup-operation-${UUID.randomUUID()}.tmp")
        var created = false
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                created = true
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            created = false
        } finally {
            if (created && Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS) &&
                Files.readAllBytes(temporary).contentEquals(bytes)) Files.delete(temporary)
            bytes.fill(0)
        }
    }

    companion object {
        const val STATUS_UNAVAILABLE = "操作状态记录未能保存或读取。请直接核对本次结果和已有恢复提示；这条提醒不代表备份、导入或恢复已经失败。"
    }
}
