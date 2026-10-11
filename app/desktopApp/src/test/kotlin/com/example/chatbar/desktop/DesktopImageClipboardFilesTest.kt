package com.example.chatbar.desktop

import com.example.chatbar.domain.image.ApngDisguiseCodec
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.ClipboardOwner
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopImageClipboardFilesTest {
    private fun png() = DesktopImageEditing.png(raster(3, 2, IntArray(6) { 0xffff0000.toInt() }))
    private fun file(clipboard: Clipboard) = ((clipboard.getData(DataFlavor.javaFileListFlavor) as List<*>).single() as java.io.File).toPath()
    private fun fixture(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("ccb-clipboard-test-")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `static result retains image flavor without staging a file`() = fixture { root ->
        val clipboard = Clipboard("fixture")
        copyDesktopImageResult(png(), clipboard, DesktopImageClipboardFiles(root.resolve("cache")))
        assertTrue(clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))
        assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor))
        val image = clipboard.getData(DataFlavor.imageFlavor) as BufferedImage
        assertEquals(3, image.width); assertEquals(2, image.height)
        assertEquals(0xffff0000.toInt(), image.getRGB(0, 0))
        assertFalse(Files.exists(root.resolve("cache")))
    }

    @Test fun `disguised APNG file flavor preserves every byte marker and survives owner scope`() = fixture { root -> runBlocking {
        val bytes = DesktopImageTools.disguise(png()); val before = bytes.copyOf()
        val clipboard = Clipboard("fixture")
        copyDesktopImageResult(bytes, clipboard, DesktopImageClipboardFiles(root))
        val path = file(clipboard)
        assertTrue(path.toString().endsWith(".png"))
        assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))
        assertContentEquals(before, Files.readAllBytes(path)); assertContentEquals(before, bytes)
        assertNotNull(ApngDisguiseCodec.inspectDisguise(path.toFile()))
        // New cache instance represents reopening the tool/app; the active file is never TTL-evicted.
        DesktopImageClipboardFiles(root, { System.currentTimeMillis() + 2 * DesktopImageClipboardFiles.RETENTION_MILLIS }).copy(bytes, clipboard)
        assertContentEquals(before, Files.readAllBytes(path))
    } }

    @Test fun `GIF derived disguise and restored animation keep frames and timing when copied`() = fixture { root -> runBlocking {
        val output = java.io.ByteArrayOutputStream()
        val writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next()
        javax.imageio.ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream; writer.prepareWriteSequence(null)
            listOf(0xffff0000.toInt(), 0xff0000ff.toInt()).forEachIndexed { index, color ->
                val frame = raster(3, 2, IntArray(6) { color })
                val metadata = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(frame), writer.defaultWriteParam)
                val tree = metadata.getAsTree("javax_imageio_gif_image_1.0") as javax.imageio.metadata.IIOMetadataNode
                val control = tree.getElementsByTagName("GraphicControlExtension").item(0) as javax.imageio.metadata.IIOMetadataNode
                control.setAttribute("delayTime", (index + 2).toString()); control.setAttribute("disposalMethod", "restoreToBackgroundColor")
                metadata.setFromTree("javax_imageio_gif_image_1.0", tree)
                writer.writeToSequence(javax.imageio.IIOImage(frame, null, metadata), writer.defaultWriteParam)
            }
            writer.endWriteSequence(); writer.dispose()
        }
        val disguised = DesktopImageTools.disguise(output.toByteArray())
        val restored = DesktopImageTools.restore(disguised)
        for (bytes in listOf(output.toByteArray(), disguised, restored)) {
            val clipboard = Clipboard("fixture")
            copyDesktopImageResult(bytes, clipboard, DesktopImageClipboardFiles(root))
            assertContentEquals(bytes, Files.readAllBytes(file(clipboard)))
            assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))
        }
        val colors = mutableListOf<Int>(); val delays = mutableListOf<Long>()
        assertTrue(playDesktopApng(restored, maxPlays = 1) { frame, delay -> colors += frame.getRGB(0, 0); delays += delay })
        assertEquals(listOf(0xffff0000.toInt(), 0xff0000ff.toInt()), colors)
        assertEquals(listOf(20L, 30L), delays)
    } }

    @Test fun `expired superseded entries clean up but current and young files survive`() = fixture { root ->
        var time = 100L
        val cache = DesktopImageClipboardFiles(root, { time }); val clipboard = Clipboard("fixture")
        cache.copy(png(), clipboard); val old = file(clipboard)
        time += DesktopImageClipboardFiles.RETENTION_MILLIS - 1
        clipboard.setContents(StringSelection("superseded"), null)
        cache.copy(png(), clipboard); val young = file(clipboard)
        assertTrue(Files.exists(old))
        time += 2
        cache.copy(png(), clipboard)
        assertFalse(Files.exists(old)); assertTrue(Files.exists(young))
    }

    @Test fun `unknown modified or foreign entries never deleted`() = fixture { root ->
        var time = 10L; val clipboard = Clipboard("fixture"); val cache = DesktopImageClipboardFiles(root, { time })
        cache.copy(png(), clipboard); val path = file(clipboard)
        val replacement = byteArrayOf(1, 2, 3); Files.write(path, replacement)
        val foreign = root.resolve("user.png"); Files.write(foreign, png())
        time += 2 * DesktopImageClipboardFiles.RETENTION_MILLIS
        clipboard.setContents(StringSelection("superseded"), null)
        assertFails { cache.copy(png(), clipboard) }
        assertContentEquals(replacement, Files.readAllBytes(path)); assertTrue(Files.exists(foreign))
    }

    @Test fun `capacity failure preserves active file and input`() = fixture { root ->
        val cache = DesktopImageClipboardFiles(root, maxEntries = 1); val clipboard = Clipboard("fixture")
        val bytes = png(); val before = bytes.copyOf()
        cache.copy(bytes, clipboard); val path = file(clipboard)
        assertFails { cache.copy(bytes, clipboard) }
        assertEquals(path, file(clipboard)); assertContentEquals(before, bytes); assertContentEquals(before, Files.readAllBytes(path))
        assertFails { DesktopImageClipboardFiles(root.resolve("small"), maxBytes = 1).copy(bytes, clipboard) }
        assertEquals(path, file(clipboard))
    }

    @Test fun `unavailable clipboard retains expired entries and failed publication retains staged bytes`() = fixture { root ->
        var time = 10L
        val cache = DesktopImageClipboardFiles(root, { time }); val clipboard = Clipboard("fixture")
        cache.copy(png(), clipboard); val old = file(clipboard)
        time += 2 * DesktopImageClipboardFiles.RETENTION_MILLIS
        val unavailable = object : Clipboard("unavailable") {
            override fun getContents(requestor: Any?): Transferable = error("busy")
            override fun setContents(contents: Transferable, owner: ClipboardOwner?) = error("busy")
        }
        assertFails { cache.copy(png(), unavailable) }
        assertTrue(Files.exists(old))
        val images = Files.walk(root).use { it.filter { p -> p.fileName.toString() == "image.png" }.toList() }
        assertEquals(2, images.size)
        images.forEach { assertContentEquals(png(), Files.readAllBytes(it)) }
    }
}
