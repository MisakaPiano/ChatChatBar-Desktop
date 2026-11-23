package com.example.chatbar.desktop

import com.example.chatbar.domain.image.ApngDisguiseCodec
import com.example.chatbar.domain.image.ImageMetadataStripper
import com.example.chatbar.domain.lanSync.LanSyncHeifStill
import com.example.chatbar.domain.lanSync.LanSyncLocalImageUnsupported
import com.example.chatbar.domain.lanSync.LanSyncRasterPolicy
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.image.BufferedImage
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream

/** Normal message-image actions use the owned file as authority, independent of the zoom preview. */
internal class DesktopMessageImageFile private constructor(
    private val path: Path,
    private val root: Path,
    private val stamp: Stamp,
    private val image: ImageInfo,
) {
    val originalDescription: String = "原文件 · ${image.format} · ${image.width} × ${image.height} px · ${stamp.size} 字节"
    val originalType = DesktopFileType(image.format, if (image.extension == "jpg") listOf("jpg", "jpeg") else listOf(image.extension))
    val suggestedOriginalName: String = path.fileName.toString().substringBeforeLast('.') + ".${image.extension}"
    private val pngDimensions = image.pngDimensions
    val pngSize: String? = pngDimensions?.let { (width, height) -> "$width × $height px" }
    val canSavePngCopy: Boolean get() = pngSize != null && image.extension != "png"
    val pngUnavailable: String? = image.pngUnavailable

    fun saveOriginal(target: Path) {
        val extension = target.fileName.toString().substringAfterLast('.', "").lowercase()
        require(extension in originalType.extensions) { "请选择 ${image.format} 文件后缀" }
        writeNewExport(target, extension, ::copyOriginalBytes)
    }

    fun savePngCopy(target: Path) {
        require(canSavePngCopy) { pngUnavailable ?: "原文件已是 PNG" }
        val raster = pngRaster()
        try { writeNewExport(target, "png") { output -> check(ImageIO.write(raster, "png", output)) } }
        finally { raster.flush() }
    }

    fun copyOriginal(clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard,
        files: DesktopImageClipboardFiles = desktopClipboardFiles) {
        files.copyOriginal(readOriginalBytes(ApngDisguiseCodec.MAX_OUTPUT_BYTES.toInt()), image.extension, clipboard)
    }

    fun copyBitmap(clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard) {
        val raster = pngRaster()
        try { clipboard.setContents(desktopImageFileTransferable(raster, null), null) }
        catch (failure: Exception) { raster.flush(); throw failure }
    }

    private fun pngRaster(): BufferedImage {
        val expected = requireNotNull(pngDimensions) { pngUnavailable ?: "无法生成位图副本" }
        requireUnchanged()
        val raster = if (image.sampled) DesktopLanSyncRaster.decode(path, null, PNG_BOUND)
        else DesktopImageEditing.decode(readOriginalBytes(DesktopImageEditing.MAX_BYTES).also(DesktopImageEditing::requireStatic))
        try {
            requireUnchanged()
            check(raster.width == expected.first && raster.height == expected.second) { "图片尺寸已变化，请重新打开" }
            return raster
        } catch (failure: Exception) {
            raster.flush()
            throw failure
        }
    }

    private fun writeNewExport(target: Path, extension: String, write: (OutputStream) -> Unit) {
        val physicalParent = requireNotNull(target.toAbsolutePath().normalize().parent).toRealPath()
        require(!physicalParent.startsWith(root.toRealPath())) { "请选择 ChatBar 数据目录以外的位置" }
        // Same new-file-only external publication as chat screenshot exports.
        writeDesktopScreenshotFile(target, extension, beforePublish = ::requireUnchanged, write = write)
    }

    private fun readOriginalBytes(maximum: Int): ByteArray {
        require(stamp.size <= maximum) { "原文件超过当前操作大小限制，可另存原文件" }
        requireUnchanged()
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(stamp.size.toInt() + 1) }
        check(bytes.size.toLong() == stamp.size) { "图片已变化，请重新打开" }
        requireUnchanged()
        return bytes
    }

    private fun copyOriginalBytes(output: OutputStream) {
        requireUnchanged()
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            var remaining = stamp.size
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(read > 0) { "图片已变化，请重新打开" }
                output.write(buffer, 0, read)
                remaining -= read
            }
            check(input.read() == -1) { "图片已变化，请重新打开" }
        }
    }

    private fun requireUnchanged() {
        check(path.parent.toRealPath() == path.parent && stamp(path) == stamp) { "图片已变化，请重新打开" }
    }

    private data class ImageInfo(val extension: String, val format: String, val width: Int, val height: Int,
        val pngDimensions: Pair<Int, Int>?, val sampled: Boolean, val pngUnavailable: String? = null)

    private data class Stamp(val size: Long, val created: java.nio.file.attribute.FileTime,
        val modified: java.nio.file.attribute.FileTime, val key: Any?)

    companion object {
        private const val PNG_BOUND = 2048

        fun open(path: Path, root: Path): DesktopMessageImageFile {
            require(path.parent.toRealPath() == path.parent) { "图片目录不安全" }
            val observed = stamp(path)
            require(observed.size > 0) { "图片文件为空" }
            return DesktopMessageImageFile(path, root, observed, inspect(path, observed.size)).also { it.requireUnchanged() }
        }

        private fun stamp(path: Path): Stamp {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isRegularFile && !attrs.isSymbolicLink && !attrs.isOther) { "图片文件不安全" }
            return Stamp(attrs.size(), attrs.creationTime(), attrs.lastModifiedTime(), attrs.fileKey())
        }

        private fun inspect(path: Path, size: Long): ImageInfo {
            LanSyncHeifStill.inspectForNaming(path)?.let { heif ->
                val (width, height) = heif.dimensions
                val hasReader = listOf("heic", "heif").any { name -> ImageIO.getImageReadersByFormatName(name).hasNext() }
                val withinRasterBudget = width <= 16_384 && height <= 16_384 &&
                    width.toLong() * height <= LanSyncRasterPolicy.maximumPixels(null)
                return ImageInfo(heif.mediaType.extension, heif.mediaType.extension.uppercase(), width, height,
                    DesktopLanSyncRaster.sampledDimensions(width, height, PNG_BOUND).takeIf { hasReader && withinRasterBudget },
                    sampled = true, pngUnavailable = when {
                        !hasReader -> "当前缺少 HEIF 解码器，可另存或复制原文件。"
                        !withinRasterBudget -> "当前图片超出位图转换范围，可另存或复制原文件。"
                        else -> null
                    })
            }
            return FileImageInputStream(path.toFile()).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("无法识别图片文件格式")
                try {
                    reader.setInput(input, false, true)
                    val extension = reader.formatName.lowercase().let { if (it == "jpeg") "jpg" else it }
                    require(extension in setOf("png", "jpg", "gif", "webp", "bmp")) { "不支持此图片文件格式" }
                    val (width, height) = reader.getWidth(0) to reader.getHeight(0)
                    require(width > 0 && height > 0) { "图片尺寸无效" }
                    val format = when (extension) { "jpg" -> "JPEG"; "webp" -> "WebP"; else -> extension.uppercase() }
                    val sampled = extension == "jpg" && (size > DesktopImageEditing.MAX_BYTES || LanSyncRasterPolicy.needsSampledJpegPreview(path))
                    val rasterAllowed = if (sampled) {
                        try {
                            LanSyncRasterPolicy.validateDimensions(path, null, width, height, jpeg = true)
                            true
                        } catch (_: LanSyncLocalImageUnsupported) { false }
                    } else size <= DesktopImageEditing.MAX_BYTES && width.toLong() * height <= DesktopImageEditing.MAX_PIXELS
                    val animated = extension == "gif" || (extension == "png" && rasterAllowed && pngAnimation(path, size))
                    val dimensions = (if (sampled) DesktopLanSyncRaster.sampledDimensions(width, height, PNG_BOUND) else width to height)
                        .takeIf { rasterAllowed && !animated }
                    val orientation = if (dimensions != null && extension in setOf("jpg", "png"))
                        ImageMetadataStripper.readOrientation(path.toFile()) else 1
                    ImageInfo(extension, format, width, height,
                        if (orientation >= 5) dimensions?.let { it.second to it.first } else dimensions, sampled,
                        when { !rasterAllowed -> "当前图片超出位图转换范围，可另存或复制原文件。"
                            animated -> "另存或复制原文件可保留动画。"
                            else -> null })
                } finally { reader.dispose() }
            }
        }

        private fun pngAnimation(path: Path, length: Long): Boolean = FileImageInputStream(path.toFile()).use { input ->
            input.seek(8)
            // The animation control chunk, when present, precedes the first image data.
            repeat(65_536) {
                val size = input.readUnsignedInt()
                val type = input.readInt()
                require(size <= length - input.streamPosition - 4) { "PNG 数据损坏" }
                if (type == 0x6163544c) return true // acTL
                if (type == 0x49444154) return false // IDAT
                input.seek(input.streamPosition + size + 4)
            }
            false // The full static validation still runs before decoding.
        }
    }
}
