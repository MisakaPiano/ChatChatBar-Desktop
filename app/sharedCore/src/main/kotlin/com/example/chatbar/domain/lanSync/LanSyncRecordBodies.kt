package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.PrivateSyncFile
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToStream

/**
 * Derived, private storage for decoded bodies whose records outlive the capture or input file.
 * The handle pins exact bytes, length and file identity without retaining a decoded body.
 */
internal class LanSyncRecordBodies(private val root: Path, internal val budget: LanSyncMetadataBudget) {
    init { Files.createDirectories(root); lanSyncSafe(root, true) }

    @OptIn(ExperimentalSerializationApi::class)
    fun retain(type: String, id: String, body: JsonObject): LanSyncRecord {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        val counter = object : OutputStream() {
            override fun write(value: Int) {
                length = lanSyncAddLength(length, 1)
                digest.update(value.toByte())
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                length = lanSyncAddLength(length, count.toLong())
                digest.update(bytes, offset, count)
            }
        }
        lanSyncJson.encodeToStream(JsonObject.serializer(), body, counter)
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val target = root.resolve(hash)
        return synchronized(locks.computeIfAbsent(root) { Any() }) {
            val lockPath = root.resolve("write.lock")
            FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { lockChannel ->
                lanSyncSafe(lockPath)
                lockChannel.lock().use {
                    if (Files.exists(target, NOFOLLOW_LINKS)) {
                        require(LanSyncResourceStore.hashFile(target, length) == hash) { "SYNC_METADATA_DAMAGED" }
                        budget.validate(target)
                    } else {
                        LanSyncResourceBudget().requireSpace(root, length)
                        PrivateSyncFile.create(root).use { temporary ->
                            val path = temporary.path
                            FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { channel ->
                                val output = Channels.newOutputStream(channel).buffered(64 * 1024)
                                lanSyncJson.encodeToStream(JsonObject.serializer(), body, output)
                                output.flush()
                                require(LanSyncResourceStore.hashFile(path, length) == hash) { "SYNC_METADATA_SOURCE_CHANGED" }
                                budget.validate(path)
                                channel.force(true)
                            }
                            Files.move(path, target, ATOMIC_MOVE)
                        }
                    }
                    val attrs = Files.readAttributes(target, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    LanSyncRecord(type, id, StoredBody(this, target, hash, length, attrs.fileKey()))
                }
            }
        }
    }

    internal class StoredBody(val store: LanSyncRecordBodies, private val path: Path, private val hash: String,
        private val length: Long, private val fileKey: Any?) {
        fun get(): JsonObject {
            lanSyncSafe(path)
            val before = attributes()
            val body = requireNotNull(LanSyncJsonFiles.read(path, JsonObject.serializer(), hash)) { "SYNC_METADATA_DAMAGED" }
            require(attributes().lastModifiedTime() == before.lastModifiedTime()) { "SYNC_METADATA_SOURCE_CHANGED" }
            return body
        }

        private fun attributes() = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).also {
            require(it.isRegularFile && it.size() == length && it.fileKey() == fileKey) { "SYNC_METADATA_DAMAGED" }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<Path, Any>()
    }
}
