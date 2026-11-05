package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.MomentTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class DesktopMomentAdmission(val active: Boolean = false, val tasks: Map<String, String> = emptyMap())

/** Local, root-bound consent outside transport/data. Task hashes authorize exact PENDING snapshots only. */
internal class DesktopMomentAdmissionStore(private val root: Path, private val privateHome: Path) {
    private val file = privateHome.resolve("moments-admission.json")
    private val mutex = Mutex()
    init { require(!privateHome.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun rootIdentity(): String {
        DesktopArchiveFiles.safe(root, true)
        val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val identity = root.toRealPath().toString().lowercase(Locale.ROOT) + "\n" +
            attributes.fileKey().toString() + "\n" + attributes.creationTime().toMillis() + "\n" + restoreEpoch(root)
        return hash(identity.toByteArray())
    }
    fun taskHash(task: MomentTask): String = hash(Json { encodeDefaults = true }
        .encodeToString(MomentTask.serializer(), task).toByteArray())

    suspend fun load(): DesktopMomentAdmission = mutex.withLock { withContext(Dispatchers.IO) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return@withContext DesktopMomentAdmission()
        DesktopArchiveFiles.safe(privateHome, true); DesktopArchiveFiles.safe(file, false)
        check(Files.size(file) <= 32L * 1024 * 1024) { "本机朋友圈调度授权过大，请手动核对" }
        val value = Json.parseToJsonElement(Files.readString(file)).jsonObject
        check(value.keys == setOf("version", "root", "active", "tasks") && value["version"] == JsonPrimitive(1))
        check(value["root"] == JsonPrimitive(rootIdentity())) { "资料目录身份已变化，请手动恢复本机调度" }
        val active = value.getValue("active").jsonPrimitive.also { check(!it.isString) }.booleanOrNull
        check(active != null)
        val tasks = value.getValue("tasks").jsonObject.mapValues { (id, digest) ->
            requireMomentStorageId(id)
            digest.jsonPrimitive.also { check(it.isString) }.content.also { check(Regex("[0-9a-f]{64}").matches(it)) }
        }
        DesktopMomentAdmission(active, tasks)
    } }

    suspend fun save(admission: DesktopMomentAdmission) = mutex.withLock { withContext(Dispatchers.IO) {
        val rootId = rootIdentity()
        // Only the configured local custody is created, never a directory from imported content.
        val missing = mutableListOf<Path>(); var parent: Path? = privateHome
        while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(parent); parent = parent.parent
        }
        if (parent != null) DesktopArchiveFiles.safe(parent, true)
        missing.asReversed().forEach { Files.createDirectory(it); DesktopArchiveFiles.safe(it, true) }
        DesktopArchiveFiles.safe(privateHome, true)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) DesktopArchiveFiles.safe(file, false)
        val content = buildJsonObject {
            put("version", 1); put("root", rootId); put("active", admission.active)
            put("tasks", buildJsonObject { admission.tasks.toSortedMap().forEach { (id, digest) ->
                requireMomentStorageId(id); require(Regex("[0-9a-f]{64}").matches(digest))
                put(id, digest)
            } })
        }.toString().toByteArray()
        val temporary = privateHome.resolve(".moments-${UUID.randomUUID()}.tmp")
        try {
            java.nio.channels.FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(content)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    } }

    companion object {
        private fun restoreEpoch(root: Path): String {
            val marker = root.resolve("backups/.moment-admission-epoch")
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return "original"
            DesktopArchiveFiles.safe(marker.parent, true); DesktopArchiveFiles.safe(marker, false)
            check(Files.size(marker) == 36L)
            return Files.readString(marker).also { check(Regex("[a-f0-9-]{36}").matches(it)) }
        }

        /** Whole-root restore runs before repositories; root directory identity alone does not change. */
        internal fun invalidateForRestore(root: Path) {
            val home = root.resolve("backups")
            DesktopArchiveFiles.safe(root, true); DesktopArchiveFiles.safe(home, true)
            val marker = home.resolve(".moment-admission-epoch")
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                DesktopArchiveFiles.safe(marker, false)
                // Revocation does not need to trust an old value; retain bounded corrupt ordinary bytes.
                val old = Files.newInputStream(marker).use { it.readNBytes(65537) }
                check(old.size <= 65536) { "本机调度旧授权证据过大，保留现场" }
                val text = old.toString(Charsets.UTF_8)
                if (old.size != 36 || !Regex("[a-f0-9-]{36}").matches(text)) {
                    val evidence = home.resolve(".moment-epoch-corrupt-${UUID.randomUUID()}.evidence")
                    java.nio.channels.FileChannel.open(evidence, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                        val bytes = java.nio.ByteBuffer.wrap(old)
                        while (bytes.hasRemaining()) channel.write(bytes)
                        channel.force(true)
                    }
                }
                check(Files.newInputStream(marker).use { it.readNBytes(65537) }.contentEquals(old))
            }
            val temporary = home.resolve(".moment-epoch-${UUID.randomUUID()}.tmp")
            try {
                java.nio.channels.FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    val bytes = java.nio.ByteBuffer.wrap(UUID.randomUUID().toString().toByteArray())
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}
