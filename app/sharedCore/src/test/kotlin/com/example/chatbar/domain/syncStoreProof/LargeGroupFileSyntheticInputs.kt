package com.example.chatbar.domain.syncStoreProof

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.lanSync.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inline synthetic inputs only; every document/archive byte is written sequentially. */
internal object LargeGroupFileSyntheticInputs {
    fun <T> entity(root: Path, type: String, id: String, serializer: KSerializer<T>, value: T) {
        val path = root.resolve("entities/$type/$id.json")
        Files.createDirectories(path.parent)
        LanSyncJsonFiles.write(path, serializer, value)
    }

    fun document(path: Path, length: Long, seed: Int): String {
        Files.createDirectories(path.parent)
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
            val buffer = ByteArray(256 * 1024) { ((it + seed) % 251).toByte() }
            var written = 0L
            while (written < length) {
                val count = minOf(buffer.size.toLong(), length - written).toInt()
                val bytes = ByteBuffer.wrap(buffer, 0, count)
                while (bytes.hasRemaining()) channel.write(bytes)
                digest.update(buffer, 0, count)
                written += count
            }
            channel.force(true)
            assertEquals(length, channel.size())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** STORED v8 entries keep the large message stream present in the actual package bytes. */
    fun archive(root: Path): Pair<SaveSlot, String> {
        val directory = Files.createDirectories(root.resolve("save_slot_packages"))
        val lines = directory.resolve("messages.jsonl")
        val count = 4096
        val content = "x".repeat(65_536)
        val crc = CRC32()
        try {
            Files.newOutputStream(lines, CREATE_NEW).buffered(64 * 1024).use { output ->
                repeat(count) { index ->
                    val row = ChatMessage("archived-$index", "session", MessageRole.USER, content,
                        createdAt = index + 1L, updatedAt = index + 1L)
                    val bytes = (lanSyncJson.encodeToString(ChatMessage.serializer(), row) + "\n").toByteArray(Charsets.UTF_8)
                    output.write(bytes)
                    crc.update(bytes)
                }
            }
            val slot = SaveSlot(schemaVersion = 8, id = "large-slot", sessionId = "session", name = "合成文件恢复大存档",
                imagePolicy = SaveSlotImagePolicy.NONE, includeAudio = false,
                packageRef = SaveSlotPackageRef("large.cbsave", messageCount = count), createdAt = 1)
            val path = directory.resolve("large.cbsave")
            ZipOutputStream(Files.newOutputStream(path, CREATE_NEW)).use { zip ->
                fun entry(name: String, length: Long, checksum: Long, body: () -> Unit) {
                    zip.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = length
                        compressedSize = length
                        this.crc = checksum
                    })
                    body()
                    zip.closeEntry()
                }
                val manifest = lanSyncJson.encodeToString(SaveSlot.serializer(), slot).toByteArray(Charsets.UTF_8)
                entry("manifest.json", manifest.size.toLong(), CRC32().apply { update(manifest) }.value) { zip.write(manifest) }
                entry("messages.jsonl", Files.size(lines), crc.value) { Files.newInputStream(lines).use { it.copyTo(zip, 64 * 1024) } }
                entry("rag.jsonl", 0, 0) {}
            }
            val full = slot.copy(packageRef = requireNotNull(slot.packageRef).copy(byteLength = Files.size(path)))
            assertTrue(Files.size(path) > 256L * 1024 * 1024)
            LanSyncSlotPackages.validate(path, lanSyncJson.encodeToJsonElement(SaveSlot.serializer(), full).jsonObject)
            return full to LanSyncResourceStore.hashFile(path, Files.size(path))
        } finally { Files.deleteIfExists(lines) }
    }
}
