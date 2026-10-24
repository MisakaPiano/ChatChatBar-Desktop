package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStream
import java.nio.file.*
import java.util.UUID
import java.util.zip.ZipFile
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** A validated immutable copy is the only input to later restore planning. No live entity writes. */
internal class DesktopSaveSlotArchive(private val workspace: Path) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    internal data class Prepared(val directory: Path, val file: Path, val slot: SaveSlot, val sha256: String,
        val format: SlotInspectFormat, val historicalVoices: Int = 0)

    suspend fun prepare(source: Path): Prepared = withContext(Dispatchers.IO) {
        val work = newWork()
        val staged = work.resolve("source.archive")
        val hash = DesktopArchiveFiles.copyNew(source, staged, MAX_ARCHIVE)
        inspect(work, staged, hash)
    }

    suspend fun verify(prepared: Prepared) {
        check(prepared.directory.parent == workspace.toAbsolutePath().normalize())
        check(prepared.file.startsWith(prepared.directory))
        check(DesktopArchiveFiles.hash(prepared.file, MAX_ARCHIVE) == prepared.sha256) { "暂存存档已变化，保留原文件并停止" }
    }

    suspend fun create(slot: SaveSlot, imagePolicy: SaveSlotImagePolicy, audio: Boolean,
        messages: suspend (suspend (ChatMessage) -> Unit) -> Unit,
        rag: suspend (suspend (VectorChunk) -> Unit) -> Unit,
        voices: List<GeneratedVoiceMessage>, mediaSource: (String) -> Path,
        progress: (String) -> Unit = {}): Prepared = withContext(Dispatchers.IO) {
        val work = newWork()
        val codec = SaveSlotPackageStorage(work.toFile(), ::compressImage, mediaSource = { reference ->
            mediaSource(reference).also { DesktopArchiveFiles.safe(it, false) }.toFile()
        }, maximumImportBytes = MAX_ARCHIVE)
        val created = codec.createPackage(slot, imagePolicy, audio, messages, rag, voices, progress)
        val file = work.resolve("save_slot_packages").resolve(requireNotNull(created.packageRef).fileName)
        inspect(work, file, DesktopArchiveFiles.hash(file, MAX_ARCHIVE))
    }

    suspend fun export(prepared: Prepared, destination: Path) = withContext(Dispatchers.IO) {
        verify(prepared)
        check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "目标文件已存在，请使用新文件名" }
        val part = destination.resolveSibling(".${destination.fileName}.${UUID.randomUUID()}.part")
        val copied = DesktopArchiveFiles.copyNew(prepared.file, part, MAX_ARCHIVE)
        check(copied == prepared.sha256)
        verify(prepared)
        // No REPLACE_EXISTING; even a late-created destination remains untouched.
        Files.move(part, destination)
        check(DesktopArchiveFiles.hash(destination, MAX_ARCHIVE) == prepared.sha256)
    }

    private suspend fun inspect(work: Path, file: Path, hash: String): Prepared {
        // Production restore retains explicit old identities plus the immutable original package.
        // Default structure-only diagnostic admission remains strict.
        val result = DesktopSaveSlotInspector(retainHistoricalVoices = true).inspect(file)
        check(result.status == SlotInspectStatus.STRUCTURE_VALID) { "存档未通过校验：${result.code}；原文件已保留" }
        val slot = if (result.format == SlotInspectFormat.ZIP_V8) ZipFile(file.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("manifest.json")).use { input ->
                json.decodeFromString(SaveSlot.serializer(), input.readNBytes(MAX_JSON + 1).also { check(it.size <= MAX_JSON) }.toString(Charsets.UTF_8))
            }
        } else {
            check(Files.size(file) <= MAX_JSON)
            json.decodeFromString(SaveSlot.serializer(), Files.readString(file))
        }
        check(DesktopArchiveFiles.hash(file, MAX_ARCHIVE) == hash)
        return Prepared(work, file, slot, hash, result.format, result.counts?.historicalVoices ?: 0)
    }

    private fun newWork(): Path {
        DesktopArchiveFiles.safe(workspace, true)
        return Files.createDirectory(workspace.toAbsolutePath().normalize().resolve("slot-${UUID.randomUUID()}"))
    }

    private fun compressImage(file: File, output: OutputStream) {
        check(file.length() <= DesktopImageEditing.MAX_BYTES)
        val image = DesktopImageEditing.decode(file.readBytes(), longestSide = 1600)
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        try {
            val graphics = rgb.createGraphics()
            try { graphics.color = Color.WHITE; graphics.fillRect(0, 0, rgb.width, rgb.height); graphics.drawImage(image, 0, 0, null) }
            finally { graphics.dispose() }
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            try {
                val stream = javax.imageio.stream.MemoryCacheImageOutputStream(object : java.io.FilterOutputStream(output) { override fun close() { flush() } })
                writer.output = stream
                val options = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = .82f }
                stream.use { writer.write(null, IIOImage(rgb, null, null), options) }
            } finally { writer.dispose() }
        } finally { image.flush(); rgb.flush() }
    }

    companion object { const val MAX_ARCHIVE = 256L * 1024 * 1024; private const val MAX_JSON = 1024 * 1024 }
}
