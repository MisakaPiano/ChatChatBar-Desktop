package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.*

internal object DesktopImageTools {
    /** Paint a continuous stroke on a copy; coordinates/radius are normalized to the image. */
    fun paint(source: BufferedImage, points: List<Pair<Float, Float>>, radius: Float, solid: Int?): BufferedImage {
        require(radius > 0 && radius <= .25f)
        val out = raster(source.width, source.height, source.pixels())
        val r = (radius * minOf(source.width, source.height)).toInt().coerceAtLeast(1)
        val block = (r / 3).coerceIn(2, 128)
        fun dot(x: Int, y: Int) {
            for (dy in maxOf(0, y - r)..minOf(out.height - 1, y + r)) for (dx in maxOf(0, x - r)..minOf(out.width - 1, x + r)) {
                if ((dx - x).toLong() * (dx - x) + (dy - y).toLong() * (dy - y) <= r.toLong() * r)
                    out.setRGB(dx, dy, solid ?: source.getRGB((dx / block * block).coerceAtMost(source.width - 1), (dy / block * block).coerceAtMost(source.height - 1)))
            }
        }
        var last: Pair<Int, Int>? = null
        points.forEach { (nx, ny) ->
            val x = (nx.coerceIn(0f, 1f) * (source.width - 1)).toInt()
            val y = (ny.coerceIn(0f, 1f) * (source.height - 1)).toInt()
            last?.let { (px, py) ->
                val steps = (maxOf(kotlin.math.abs(x - px), kotlin.math.abs(y - py)) / maxOf(1, r / 2)).coerceAtLeast(1)
                for (i in 0..steps) dot(px + (x - px) * i / steps, py + (y - py) * i / steps)
            } ?: dot(x, y)
            last = x to y
        }
        return out
    }
    fun rotate(source: BufferedImage): BufferedImage = BufferedImage(source.height, source.width, BufferedImage.TYPE_INT_ARGB).also { out ->
        for (y in 0 until source.height) for (x in 0 until source.width) out.setRGB(source.height - y - 1, x, source.getRGB(x, y))
    }

    fun mosaic(source: BufferedImage, region: NovelAiFocusedInpaintRegion, block: Int = 16): BufferedImage {
        require(region.isValid && block in 2..128)
        val out = raster(source.width, source.height, source.pixels())
        val left = (region.x * source.width).toInt(); val top = (region.y * source.height).toInt()
        val right = ((region.x + region.width) * source.width).toInt().coerceAtMost(source.width)
        val bottom = ((region.y + region.height) * source.height).toInt().coerceAtMost(source.height)
        for (y in top until bottom step block) for (x in left until right step block) {
            val color = source.getRGB((x + block / 2).coerceAtMost(right - 1), (y + block / 2).coerceAtMost(bottom - 1))
            for (dy in y until minOf(y + block, bottom)) for (dx in x until minOf(x + block, right)) out.setRGB(dx, dy, color)
        }
        return out
    }

    suspend fun disguise(source: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val gif = source.take(3).toByteArray().toString(Charsets.US_ASCII) == "GIF"
        if (!gif) DesktopImageEditing.requireStatic(source)
        val output = object : ByteArrayOutputStream() {
            override fun write(value: Int) { check(count < ApngDisguiseCodec.MAX_OUTPUT_BYTES); super.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                check(count.toLong() + length <= ApngDisguiseCodec.MAX_OUTPUT_BYTES); super.write(bytes, offset, length)
            }
        }
        if (gif) {
            ImageIO.createImageInputStream(ByteArrayInputStream(source)).use { input ->
                val reader = ImageIO.getImageReadersByFormatName("gif").next()
                try {
                    reader.input = input
                    val count = reader.getNumImages(true)
                    require(count in 1..1000)
                    val tree = reader.streamMetadata.getAsTree("javax_imageio_gif_stream_1.0")
                    fun child(node: org.w3c.dom.Node, name: String): org.w3c.dom.Node? =
                        (0 until node.childNodes.length).map { node.childNodes.item(it) }.firstOrNull { it.nodeName == name }
                    val screen = requireNotNull(child(tree, "LogicalScreenDescriptor"))
                    val width = screen.attributes.getNamedItem("logicalScreenWidth").nodeValue.toInt()
                    val height = screen.attributes.getNamedItem("logicalScreenHeight").nodeValue.toInt()
                    require(width.toLong() * height <= DesktopImageEditing.MAX_PIXELS)
                    val firstMetadata = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0")
                    val palette = child(tree, "GlobalColorTable")
                    val backgroundIndex = palette?.attributes?.getNamedItem("backgroundColorIndex")?.nodeValue?.toInt()
                    val backgroundEntry = palette?.let { node -> (0 until node.childNodes.length).map { node.childNodes.item(it) }
                        .firstOrNull { it.attributes?.getNamedItem("index")?.nodeValue?.toInt() == backgroundIndex } }
                    fun background(control: org.w3c.dom.Node?): Color {
                        val transparent = control?.attributes?.getNamedItem("transparentColorFlag")?.nodeValue == "TRUE"
                        val index = control?.attributes?.getNamedItem("transparentColorIndex")?.nodeValue?.toInt()
                        if (backgroundEntry == null || transparent && index == backgroundIndex) return Color(0, true)
                        return Color(backgroundEntry.attributes.getNamedItem("red").nodeValue.toInt(),
                            backgroundEntry.attributes.getNamedItem("green").nodeValue.toInt(), backgroundEntry.attributes.getNamedItem("blue").nodeValue.toInt())
                    }
                    val extensions = child(firstMetadata, "ApplicationExtensions")
                    val loop = extensions?.let { node -> (0 until node.childNodes.length).mapNotNull { index ->
                        val app = node.childNodes.item(index) as? javax.imageio.metadata.IIOMetadataNode
                        val bytes = app?.userObject as? ByteArray
                        if (bytes != null && bytes.size >= 3 && bytes[0].toInt() == 1) (bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8) else null
                    }.firstOrNull() } ?: -1
                    val writer = ApngDisguiseCodec.Writer(output, width, height, if (count == 1) 2 else count,
                        if (count == 1) 0 else ApngDisguiseCodec.gifLoopCountToApngPlayCount(loop),
                        if (count == 1) ApngDisguiseContentKind.STATIC else ApngDisguiseContentKind.ANIMATED, count)
                    writer.writeDefaultImage(cover(width, height).asApngRaster())
                    var canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    canvas.createGraphics().let { g -> try { g.composite = java.awt.AlphaComposite.Src
                        g.color = background(child(firstMetadata, "GraphicControlExtension")); g.fillRect(0, 0, width, height) } finally { g.dispose() } }
                    repeat(count) { i ->
                        currentCoroutineContext().ensureActive()
                        val meta = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0")
                        val descriptor = requireNotNull(child(meta, "ImageDescriptor"))
                        val control = child(meta, "GraphicControlExtension")
                        val left = descriptor.attributes.getNamedItem("imageLeftPosition").nodeValue.toInt()
                        val top = descriptor.attributes.getNamedItem("imageTopPosition").nodeValue.toInt()
                        val frame = reader.read(i)
                        val previous = raster(width, height, canvas.pixels())
                        canvas.createGraphics().let { graphics -> try { graphics.drawImage(frame, left, top, null) } finally { graphics.dispose() } }
                        val delay = control?.attributes?.getNamedItem("delayTime")?.nodeValue?.toInt() ?: 10
                        writer.writeFrame(canvas.asApngRaster(), delay.coerceAtLeast(1), 100)
                        when (control?.attributes?.getNamedItem("disposalMethod")?.nodeValue) {
                            "restoreToPrevious" -> canvas = previous
                            "restoreToBackgroundColor" -> canvas.createGraphics().let { g -> try {
                                g.composite = java.awt.AlphaComposite.Src; g.color = background(control); g.fillRect(left, top, frame.width, frame.height)
                            } finally { g.dispose() } }
                        }
                        require(output.size() <= ApngDisguiseCodec.MAX_OUTPUT_BYTES)
                    }
                    if (count == 1) writer.writeStaticHeartbeatFrame()
                    writer.finish()
                } finally { reader.dispose() }
            }
        } else {
            val image = DesktopImageEditing.decode(source)
            val writer = ApngDisguiseCodec.Writer(output, image.width, image.height, 2, 0, ApngDisguiseContentKind.STATIC, 1)
            writer.writeDefaultImage(cover(image.width, image.height).asApngRaster())
            writer.writeFrame(image.asApngRaster(), 10, 100)
            writer.writeStaticHeartbeatFrame(); writer.finish()
        }
        require(output.size() <= ApngDisguiseCodec.MAX_OUTPUT_BYTES)
        output.toByteArray()
    }

    suspend fun restore(source: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val input = Files.createTempFile("ccb-disguise-", ".png")
        val output = Files.createTempFile("ccb-restored-", ".png")
        try {
            Files.write(input, source)
            Files.delete(output) // Exact newly allocated empty target; codec publishes by rename.
            ApngDisguiseCodec.restoreDisguise(input.toFile(), output.toFile())
            Files.readAllBytes(output)
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); Files.deleteIfExists(output.resolveSibling(output.fileName.toString() + ".tmp")) }
    }

    suspend fun strip(source: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        DesktopImageEditing.requireStatic(source)
        val input = Files.createTempFile("ccb-metadata-", ".image")
        var output: java.io.File? = null
        try { Files.write(input, source); output = ImageMetadataStripper.stripToCopy(input.toFile(), input.parent.toFile()); output.readBytes() }
        finally { Files.deleteIfExists(input); output?.delete() }
    }

    private fun cover(width: Int, height: Int): BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        image.createGraphics().let { g -> try {
            g.color = Color(25, 27, 32); g.fillRect(0, 0, width, height)
            val side = minOf(width, height) / 3
            g.drawImage(DesktopImageEditing.decode(DesktopBrandResources.logoBytes()), (width - side) / 2, (height - side) / 2, side, side, null)
        } finally { g.dispose() } }
    }
}
private fun BufferedImage.asApngRaster() = ApngRaster(width, height) { y, row -> getRGB(0, y, width, 1, row, 0, width) }
