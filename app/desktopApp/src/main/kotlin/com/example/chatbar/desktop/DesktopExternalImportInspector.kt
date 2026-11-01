package com.example.chatbar.desktop

import com.example.chatbar.domain.card.*
import com.example.chatbar.desktop.backup.AppBackupCodec
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.Json

internal enum class DesktopExternalImportKind(val label: String) {
    CHARACTER("角色卡"), FORMAT("格式卡"), WORLD_BOOK("世界书"), MODEL_TEMPLATE("模型模板"),
    IMAGE("图片"), BACKUP("整应用备份"), SAVE_SLOT("会话存档"), UNKNOWN("未识别的文件")
}
internal data class DesktopExternalPrepared(val original: Path, val file: Path,
    val kind: DesktopExternalImportKind, val staging: Path? = null, val sha256: String? = null)

internal object DesktopExternalImportInspector {
    val MAX_TYPED_BYTES = DesktopImageEditing.MAX_BYTES.toLong()
    private val json = Json { ignoreUnknownKeys = false }
    private val classifier = SharedImportClassifierCore(SillyTavernCardMapper(AuthoritativeCharacterTransferPromptPolicy),
        SharedImportModelTemplateDecoder { raw -> json.decodeFromString(ModelTemplatePackage.serializer(), raw).also { it.validateForImport() } })

    suspend fun prepare(path: Path, createStaging: () -> Path = { Files.createTempDirectory("ccb-external-import-") }): DesktopExternalPrepared = withContext(Dispatchers.IO) {
        DesktopArchiveFiles.safe(path, false)
        val prefix = Files.newInputStream(path).use { it.readNBytes(8) }
        if (prefix.take(AppBackupCodec.magic.size).toByteArray().contentEquals(AppBackupCodec.magic))
            return@withContext DesktopExternalPrepared(path, path, DesktopExternalImportKind.BACKUP)
        if (prefix.size >= 2 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte()) {
            val slot = DesktopSaveSlotInspector(retainHistoricalVoices = true, retainHistoricalReferences = true).inspect(path)
            return@withContext DesktopExternalPrepared(path, path,
                if (slot.status == SlotInspectStatus.STRUCTURE_VALID) DesktopExternalImportKind.SAVE_SLOT else DesktopExternalImportKind.UNKNOWN)
        }
        if (Files.size(path) > MAX_TYPED_BYTES) return@withContext DesktopExternalPrepared(path, path, DesktopExternalImportKind.UNKNOWN)
        val staging = createStaging()
        val copy = staging.resolve(path.fileName.toString())
        val stagingKey = Files.readAttributes(staging, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        var completedHash: String? = null
        try {
            val hash = DesktopArchiveFiles.copyNew(path, copy, MAX_TYPED_BYTES).also { completedHash = it }
            val bytes = Files.readAllBytes(copy)
            val detected = classifier.inspect(bytes, path.fileName.toString(), imageInfo(bytes))
            var kind = DesktopExternalImportKind.valueOf(detected.kind.name)
            if (kind == DesktopExternalImportKind.UNKNOWN) {
                val slot = DesktopSaveSlotInspector(retainHistoricalVoices = true, retainHistoricalReferences = true).inspect(copy)
                if (slot.status == SlotInspectStatus.STRUCTURE_VALID) kind = DesktopExternalImportKind.SAVE_SLOT
            }
            DesktopExternalPrepared(path, copy, kind, staging, hash)
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) {
                DesktopArchiveFiles.safe(staging, true)
                check(Files.readAttributes(staging, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey() == stagingKey)
                if (Files.exists(copy, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    DesktopArchiveFiles.safe(copy, false)
                    completedHash?.let { check(DesktopArchiveFiles.hash(copy, MAX_TYPED_BYTES) == it) }
                    Files.delete(copy)
                }
                Files.delete(staging)
            } } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    suspend fun requireTyped(prepared: DesktopExternalPrepared, kind: DesktopExternalImportKind) = withContext(Dispatchers.IO) {
        check(Files.size(prepared.file) <= MAX_TYPED_BYTES) { "文件过大，不能作为普通卡片或图片读取。请选择备份或存档检查。" }
        if (prepared.sha256 != null) check(DesktopArchiveFiles.hash(prepared.file, MAX_TYPED_BYTES) == prepared.sha256)
        val bytes = Files.readAllBytes(prepared.file)
        if (kind == DesktopExternalImportKind.IMAGE) {
            check(imageInfo(bytes) != null) { "文件不是受支持的图片" }
            DesktopImageEditing.requireStatic(bytes)
        }
        else classifier.decodeAs(bytes, SharedImportKind.valueOf(kind.name), prepared.original.fileName.toString())
        Unit
    }

    suspend fun release(prepared: DesktopExternalPrepared) = withContext(Dispatchers.IO) {
        val directory = prepared.staging ?: return@withContext
        check(prepared.file.parent == directory)
        if (Files.exists(prepared.file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            check(DesktopArchiveFiles.hash(prepared.file, MAX_TYPED_BYTES) == prepared.sha256) { "暂存文件已变化，保留待核对" }
            Files.delete(prepared.file)
        }
        Files.deleteIfExists(directory)
    }

    private fun imageInfo(bytes: ByteArray): SharedImportImageInfo? = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return@use null
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0); val height = reader.getHeight(0)
            check(width > 0 && height > 0 && width.toLong() * height <= DesktopImageEditing.MAX_PIXELS)
            val name = reader.formatName.lowercase()
            SharedImportImageInfo(if (name == "jpg" || name == "jpeg") "image/jpeg" else "image/$name", width, height, name == "gif")
        } finally { reader.dispose() }
    }
}
