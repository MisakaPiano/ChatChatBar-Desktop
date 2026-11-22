package com.example.chatbar.desktop

import com.example.chatbar.domain.image.ImageMetadataStripper
import com.example.chatbar.domain.lanSync.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

/** File-backed sync decoding; no encoded-file byte array or unbounded Skia fallback. */
internal object DesktopLanSyncRaster {
    fun decode(path: Path, record: LanSyncRecord?, longestSide: Int, renderOrientation: Boolean = true): BufferedImage {
        require(longestSide in 1..2048)
        LanSyncRasterPolicy.checkPng(path, record?.let(LanSyncPostProcess::record) != null)
        val heif = LanSyncHeifStill.inspect(path)
        DesktopLanSyncWebPInput.open(path, renderColorMetadata = renderOrientation, maximumPixels = LanSyncRasterPolicy.maximumPixels(record)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { if (heif == null) "SYNC_IMAGE_CODEC_UNAVAILABLE" else "SYNC_IMAGE_HEIF_PREVIEW_UNAVAILABLE" }
            val reader = readers.next()
            try {
                reader.setInput(input, false, true)
                val format = reader.formatName.lowercase()
                require(format in setOf("png", "jpeg", "jpg", "gif", "webp", "bmp") ||
                    heif != null && format in setOf("heic", "heif")) { "SYNC_IMAGE_INVALID" }
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                LanSyncRasterPolicy.validateDimensions(path, record, width, height, format == "jpeg" || format == "jpg")
                val orientation = if (!renderOrientation || format == "gif" || format == "bmp" || heif != null) 1
                    else ImageMetadataStripper.readOrientation(path.toFile())
                val sample = ((maxOf(width, height).toLong() + longestSide - 1) / longestSide).toInt().coerceAtLeast(1)
                val params = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                return DesktopImageEditing.orient(requireNotNull(reader.read(0, params)) { "SYNC_IMAGE_INVALID" }, orientation)
            } finally { reader.dispose() }
        }
    }

    fun preview(path: Path, record: LanSyncRecord? = null): ByteArray = decode(path, record, 2048).let { raster ->
        try { ByteArrayOutputStream().use { output ->
            check(ImageIO.write(raster, "png", output)) { "SYNC_IMAGE_CODEC_UNAVAILABLE" }
            output.toByteArray()
        } } finally { raster.flush() }
    }
}
