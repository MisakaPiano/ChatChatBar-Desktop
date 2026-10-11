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
        assertTrue(Files.exists(old)); assertTrue(Files.exists(young))
        time += DesktopImageClipboardFiles.RETENTION_MILLIS - 2
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
        cache.copy(png(), clipboard)
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

    @Test fun `incomplete truncated and foreign staging remains intact while copy succeeds`() = fixture { root ->
        val partial = Files.createDirectory(root.resolve("copy-${java.util.UUID.randomUUID()}"))
        val orphan = partial.resolve("image.png"); Files.write(orphan, byteArrayOf(4, 5, 6))
        val corrupt = Files.createDirectory(root.resolve("copy-${java.util.UUID.randomUUID()}"))
        val record = corrupt.resolve("owner.txt"); Files.writeString(record, "CCB-clipboard-v1\n")
        val foreign = root.resolve("foreign.bin"); Files.write(foreign, byteArrayOf(7, 8))
        val clipboard = Clipboard("fixture")
        val bytes = runBlocking { DesktopImageTools.disguise(png()) }
        DesktopImageClipboardFiles(root).copy(bytes, clipboard)
        assertContentEquals(bytes, Files.readAllBytes(file(clipboard)))
        assertContentEquals(byteArrayOf(4, 5, 6), Files.readAllBytes(orphan))
        assertEquals("CCB-clipboard-v1\n", Files.readString(record))
        assertContentEquals(byteArrayOf(7, 8), Files.readAllBytes(foreign))
    }

    @Test fun `long active legacy copy receives seven days after confirmed release across instances`() = fixture { root ->
        val day = DesktopImageClipboardFiles.RETENTION_MILLIS / 7
        var time = 0L
        val clipboard = Clipboard("fixture")
        fun copy(): Path { DesktopImageClipboardFiles(root, { time }).copy(png(), clipboard); return file(clipboard) }
        val a = copy()
        assertEquals(4, Files.readAllLines(a.parent.resolve("owner.txt")).size)
        time = 10 * day
        val b = copy()
        assertTrue(Files.exists(a))
        time += 3000
        copy() // First observation of A's release, not its creation or B's attempted publication.
        val releasedAt = time
        assertTrue(Files.exists(a)); assertTrue(Files.exists(b))
        time = releasedAt + 7 * day - 1
        copy()
        assertTrue(Files.exists(a))
        time += 2
        copy()
        assertFalse(Files.exists(a))
    }

    @Test fun `reactivation and backwards clock restart grace without early removal`() = fixture { root ->
        var time = 10000L; val clipboard = Clipboard("fixture")
        fun copy() { DesktopImageClipboardFiles(root, { time }).copy(png(), clipboard) }
        copy(); val a = file(clipboard)
        clipboard.setContents(StringSelection("released"), null); copy()
        time = 5000L; copy()
        assertTrue(Files.exists(a))
        assertEquals("10000", Files.readAllLines(a.parent.resolve("owner.txt")).last())
        clipboard.setContents(desktopImageTransferable(png(), a), null)
        time += DesktopImageClipboardFiles.RETENTION_MILLIS * 2; copy()
        assertTrue(Files.exists(a))
        time += 1; copy()
        assertTrue(Files.exists(a))
        assertEquals(time.toString(), Files.readAllLines(a.parent.resolve("owner.txt")).last())
        time += DesktopImageClipboardFiles.RETENTION_MILLIS - 1; copy()
        assertTrue(Files.exists(a))
        time += 2; copy()
        assertFalse(Files.exists(a))
    }

    @Test fun `confirmed day ten release survives rollback and recovery without being shortened`() = fixture { root ->
        val day = DesktopImageClipboardFiles.RETENTION_MILLIS / 7
        var time = 0L; val clipboard = Clipboard("fixture")
        fun copy() { DesktopImageClipboardFiles(root, { time }).copy(png(), clipboard) }
        copy(); val a = file(clipboard)
        time = 10 * day
        clipboard.setContents(StringSelection("released"), null); copy()
        val record = a.parent.resolve("owner.txt")
        val confirmed = Files.readAllBytes(record)
        assertEquals((10 * day).toString(), Files.readAllLines(record).last())
        time = day; copy()
        assertContentEquals(confirmed, Files.readAllBytes(record))
        time = 10 * day + 3000; copy()
        assertTrue(Files.exists(a)); assertContentEquals(confirmed, Files.readAllBytes(record))
        time = 17 * day - 1; copy(); assertTrue(Files.exists(a))
        time = 17 * day + 1; copy(); assertFalse(Files.exists(a))
    }

    @Test fun `first release during rollback cannot precede day ten creation`() = fixture { root ->
        val day = DesktopImageClipboardFiles.RETENTION_MILLIS / 7
        var time = 10 * day; val clipboard = Clipboard("fixture")
        fun copy() { DesktopImageClipboardFiles(root, { time }).copy(png(), clipboard) }
        copy(); val a = file(clipboard)
        val record = a.parent.resolve("owner.txt")
        val before = Files.readAllBytes(record)
        time = day
        clipboard.setContents(StringSelection("released"), null); copy()
        assertContentEquals(before, Files.readAllBytes(record))
        time = 10 * day + 3000; copy(); assertTrue(Files.exists(a))
        assertEquals(time.toString(), Files.readAllLines(record).last())
        time = 17 * day - 1; copy(); assertTrue(Files.exists(a))
        time = 17 * day + 1; copy(); assertTrue(Files.exists(a))
        time = 17 * day + 3001; copy(); assertFalse(Files.exists(a))
    }

    @Test fun `first release waits for trustworthy day eighteen observation across instances`() = fixture { root ->
        val day = DesktopImageClipboardFiles.RETENTION_MILLIS / 7
        var time = 10 * day; val clipboard = Clipboard("fixture")
        // Each operation reconstructs cache state solely from the persisted records.
        fun copy() { DesktopImageClipboardFiles(root, { time }).copy(png(), clipboard) }
        copy(); val a = file(clipboard)
        val record = a.parent.resolve("owner.txt")
        val before = Files.readAllBytes(record)
        time = day
        clipboard.setContents(StringSelection("released"), null); copy()
        assertTrue(Files.exists(a)); assertContentEquals(before, Files.readAllBytes(record))
        copy()
        assertContentEquals(before, Files.readAllBytes(record))
        time = 18 * day; copy()
        assertTrue(Files.exists(a))
        assertEquals(time.toString(), Files.readAllLines(record).last())
        copy(); assertTrue(Files.exists(a))
        time = 25 * day - 1; copy(); assertTrue(Files.exists(a))
        assertEquals((18 * day).toString(), Files.readAllLines(record).last())
        time = 25 * day + 1; copy(); assertFalse(Files.exists(a))
    }

    @Test fun `legacy release preceding creation is retained as uncertain`() = fixture { root ->
        val day = DesktopImageClipboardFiles.RETENTION_MILLIS / 7
        var time = 10 * day; val clipboard = Clipboard("fixture")
        fun copy() { DesktopImageClipboardFiles(root, { time }).copy(png(), clipboard) }
        copy(); val a = file(clipboard)
        val record = a.parent.resolve("owner.txt")
        val fields = Files.readAllLines(record).toMutableList()
        fields[0] = "CCB-clipboard-v2"; fields += day.toString()
        Files.writeString(record, fields.joinToString("\n"))
        val before = Files.readAllBytes(record)
        clipboard.setContents(StringSelection("released"), null)
        time = 30 * day; copy()
        assertTrue(Files.exists(a)); assertContentEquals(before, Files.readAllBytes(record))
    }

    @Test fun `unknown entries consume default entry and byte quotas without deletion`() = fixture { root ->
        val clipboard = Clipboard("fixture"); clipboard.setContents(StringSelection("keep"), null)
        repeat(31) { Files.createDirectory(root.resolve("partial-$it")) }
        val bytes = png(); DesktopImageClipboardFiles(root).copy(bytes, clipboard)
        val active = file(clipboard)
        assertFails { DesktopImageClipboardFiles(root).copy(bytes, clipboard) }
        assertEquals(active, file(clipboard)); assertTrue(Files.exists(root.resolve("partial-0")))
        val largeRoot = Files.createDirectory(root.resolve("separate"))
        val foreign = largeRoot.resolve("foreign.bin")
        java.io.RandomAccessFile(foreign.toFile(), "rw").use { it.setLength(256L * 1024 * 1024) }
        assertFails { DesktopImageClipboardFiles(largeRoot).copy(bytes, clipboard) }
        assertEquals(256L * 1024 * 1024, Files.size(foreign)); assertEquals(active, file(clipboard))
    }

    @Test fun `junction target is never inspected changed or cleaned`() = fixture { root ->
        val target = Files.createDirectory(root.resolve("outside"))
        val secret = target.resolve("untouched.bin"); Files.write(secret, byteArrayOf(1, 3, 5))
        val cacheRoot = Files.createDirectory(root.resolve("cache"))
        val link = cacheRoot.resolve("copy-${java.util.UUID.randomUUID()}")
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        } else Files.createSymbolicLink(link, target)
        try {
            val clipboard = Clipboard("fixture")
            DesktopImageClipboardFiles(cacheRoot).copy(png(), clipboard)
            assertContentEquals(byteArrayOf(1, 3, 5), Files.readAllBytes(secret))
            assertTrue(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals(1, Files.list(target).use { it.count() }.toInt())
        } finally { Files.delete(link) } // Remove the fixture link only, never traverse it during cleanup.
    }

    @Test fun `failed publication does not start prior active release and interrupted metadata stays intact`() = fixture { root ->
        var time = 0L
        val clipboard = Clipboard("fixture")
        DesktopImageClipboardFiles(root, { time }).copy(png(), clipboard)
        val active = file(clipboard)
        val ownerBefore = Files.readAllBytes(active.parent.resolve("owner.txt"))
        time = 20 * DesktopImageClipboardFiles.RETENTION_MILLIS
        val failing = object : Clipboard("publication fails") {
            override fun getContents(requestor: Any?): Transferable = clipboard.getContents(null)
            override fun setContents(contents: Transferable, owner: ClipboardOwner?) = error("busy")
        }
        assertFails { DesktopImageClipboardFiles(root, { time }).copy(png(), failing) }
        assertEquals(active, file(clipboard))
        assertContentEquals(ownerBefore, Files.readAllBytes(active.parent.resolve("owner.txt")))
        // A crash during release-record replacement leaves a temp; neither record is guessed away.
        val interrupted = active.parent.resolve("owner-interrupted.tmp")
        Files.writeString(interrupted, "CCB-clipboard-v2\npartial")
        clipboard.setContents(StringSelection("released"), null)
        DesktopImageClipboardFiles(root, { time }).copy(png(), clipboard)
        assertContentEquals(ownerBefore, Files.readAllBytes(active.parent.resolve("owner.txt")))
        assertEquals("CCB-clipboard-v2\npartial", Files.readString(interrupted))
        assertTrue(Files.exists(active))
    }
}
