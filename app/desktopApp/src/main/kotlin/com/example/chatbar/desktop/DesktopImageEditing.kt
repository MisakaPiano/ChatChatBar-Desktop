package com.example.chatbar.desktop

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/** A transient transform, never an Entity or Package field. Source bytes remain immutable. */
internal data class DesktopImageTransform(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val zoom: Float = 1f,
) {
    fun normalized() = copy(
        centerX = centerX.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f,
        centerY = centerY.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f,
        zoom = zoom.takeIf(Float::isFinite)?.coerceIn(1f, 8f) ?: 1f,
    )

    fun pan(dx: Float, dy: Float, sourceWidth: Int, sourceHeight: Int, viewportWidth: Float,
        viewportHeight: Float): DesktopImageTransform {
        if (viewportWidth <= 0 || viewportHeight <= 0) return this
        val scale = max(viewportWidth / sourceWidth, viewportHeight / sourceHeight) * zoom
        return copy(centerX = centerX - dx / (sourceWidth * scale),
            centerY = centerY - dy / (sourceHeight * scale)).normalized()
    }
}

internal fun desktopImageCrop(width: Int, height: Int, outputWidth: Int, outputHeight: Int,
    transform: DesktopImageTransform): com.example.chatbar.domain.image.ImagePixelCrop {
    val value = transform.normalized()
    return com.example.chatbar.domain.image.centeredImageCrop(width, height, outputWidth, outputHeight,
        value.centerX, value.centerY, value.zoom)
}

internal object DesktopImageEditing {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_PIXELS = 32_000_000L

    fun decode(bytes: ByteArray, longestSide: Int? = null, minimumDisplayWidth: Int? = null,
        onSourceDimensions: ((Int, Int) -> Unit)? = null,
        displayTargetForSource: ((Int, Int) -> Pair<Int, Int>)? = null): BufferedImage {
        require(bytes.size in 1..MAX_BYTES) { "图片大小超过 32 MB" }
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (readers.hasNext()) {
                val reader = readers.next()
                try {
                    reader.input = input
                    val sourceWidth = reader.getWidth(0)
                    val sourceHeight = reader.getHeight(0)
                    require(sourceWidth.toLong() * sourceHeight <= MAX_PIXELS) { "图片像素过大" }
                    val orientation = jpegOrientation(bytes)
                    val orientedWidth = if (orientation >= 5) sourceHeight else sourceWidth
                    val orientedHeight = if (orientation >= 5) sourceWidth else sourceHeight
                    val displayTarget = displayTargetForSource?.invoke(orientedWidth, orientedHeight)
                    val params = reader.defaultReadParam
                    longestSide?.let { bound ->
                        require(bound > 0)
                        val targetBound = displayTarget?.let { maxOf(it.first, it.second).coerceAtLeast(1) }
                            ?.coerceAtMost(bound) ?: bound
                        var sample = kotlin.math.ceil(maxOf(sourceWidth, sourceHeight).toDouble() / targetBound)
                            .toInt().coerceAtLeast(1)
                        (displayTarget?.first ?: minimumDisplayWidth)?.let { target ->
                            require(target > 0)
                            sample = minOf(sample, (orientedWidth / target).coerceAtLeast(1))
                        }
                        params.setSourceSubsampling(sample, sample, 0, 0)
                    }
                    val image = orient(reader.read(0, params), orientation)
                    onSourceDimensions?.invoke(orientedWidth, orientedHeight)
                    return image
                } finally { reader.dispose() }
            }
        }
        // Skia supplies Desktop WebP support; bounds are checked before rasterization.
        org.jetbrains.skia.Image.makeFromEncoded(bytes).use { image ->
            require(image.width.toLong() * image.height <= MAX_PIXELS) { "图片像素过大" }
            val png = requireNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG))
            return png.use {
                val decoded = requireNotNull(ImageIO.read(ByteArrayInputStream(it.bytes))) { "无法解码图片" }
                onSourceDimensions?.invoke(image.width, image.height)
                decoded
            }
        }
    }

    private fun jpegOrientation(bytes: ByteArray): Int {
        if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return 1
        fun u16(offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        var offset = 2
        while (offset + 4 <= bytes.size && bytes[offset] == 0xff.toByte()) {
            val marker = bytes[offset + 1].toInt() and 255
            if (marker == 0xda || marker == 0xd9) break
            val length = u16(offset + 2)
            if (length < 2 || offset.toLong() + length + 2 > bytes.size) break
            if (marker == 0xe1 && length >= 16 && String(bytes, offset + 4, 6, Charsets.US_ASCII) == "Exif\u0000\u0000") {
                val tiff = java.nio.ByteBuffer.wrap(bytes, offset + 10, length - 8).slice()
                val little = tiff.get(0) == 'I'.code.toByte() && tiff.get(1) == 'I'.code.toByte()
                val big = tiff.get(0) == 'M'.code.toByte() && tiff.get(1) == 'M'.code.toByte()
                require(little || big) { "EXIF 字节序无效" }
                tiff.order(if (little) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
                require(tiff.getShort(2).toInt() == 42) { "EXIF 数据无效" }
                val directory = tiff.getInt(4)
                require(directory >= 8 && directory + 2L <= tiff.limit()) { "EXIF 目录无效" }
                val count = tiff.getShort(directory).toInt() and 65535
                require(directory + 2L + count * 12L <= tiff.limit()) { "EXIF 目录不完整" }
                repeat(count) { index ->
                    val entry = directory + 2 + index * 12
                    if (tiff.getShort(entry).toInt() and 65535 == 0x112) {
                        require(tiff.getShort(entry + 2).toInt() == 3 && tiff.getInt(entry + 4) == 1)
                        return (tiff.getShort(entry + 8).toInt() and 65535).also { require(it in 1..8) }
                    }
                }
            }
            offset += length + 2
        }
        return 1
    }

    private fun orient(source: BufferedImage, orientation: Int): BufferedImage {
        if (orientation == 1) return source
        val w = source.width; val h = source.height
        val result = BufferedImage(if (orientation >= 5) h else w, if (orientation >= 5) w else h,
            BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) {
            val (dx, dy) = when (orientation) {
                2 -> w - 1 - x to y
                3 -> w - 1 - x to h - 1 - y
                4 -> x to h - 1 - y
                5 -> y to x
                6 -> h - 1 - y to x
                7 -> h - 1 - y to w - 1 - x
                8 -> y to w - 1 - x
                else -> x to y
            }
            result.setRGB(dx, dy, source.getRGB(x, y))
        }
        return result
    }

    fun requireStatic(bytes: ByteArray) {
        require(!(bytes.size >= 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF")) {
            "GIF 请使用动画工具，裁剪会丢失动画"
        }
        // PNG chunk walk, never a substring scan of compressed image data.
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
            var offset = 8
            while (offset + 12 <= bytes.size) {
                val length = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
                require(length >= 0 && offset.toLong() + length + 12 <= bytes.size) { "PNG 数据损坏" }
                require(String(bytes, offset + 4, 4, Charsets.US_ASCII) != "acTL") { "APNG 请使用动画工具" }
                offset += length + 12
            }
        }
    }

    fun crop(source: BufferedImage, transform: DesktopImageTransform, width: Int, height: Int): BufferedImage {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
        val crop = desktopImageCrop(source.width, source.height, width, height, transform)
        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        result.createGraphics().let { graphics ->
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(source, 0, 0, width, height, crop.left, crop.top,
                    crop.left + crop.width, crop.top + crop.height, null)
            } finally { graphics.dispose() }
        }
        return result
    }

    fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().use {
        check(ImageIO.write(image, "png", it)) { "PNG encoder unavailable" }
        it.toByteArray()
    }
}
