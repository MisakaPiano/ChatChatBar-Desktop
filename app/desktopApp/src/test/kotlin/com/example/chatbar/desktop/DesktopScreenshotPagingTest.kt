package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.chat.roleplayLegacyTextBlockId
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import kotlin.test.*

class DesktopScreenshotPagingTest {
    private fun raster(height: Int, offset: Int = 0): DesktopScreenshotPng {
        val bitmap = BufferedImage(320, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until 320) bitmap.setRGB(x, y,
            if (((y + offset) / 100) % 2 == 0) 0xffcc2233.toInt() else 0xff2255cc.toInt())
        return DesktopScreenshotPng(DesktopImageEditing.png(bitmap), 320, height)
    }

    @Test fun `streamed PNG keeps every row across frame seams without allocating a combined renderer`() {
        val pages = listOf(raster(137), raster(213, 137), raster(79, 350))
        val bytes = ByteArrayOutputStream().also { writeDesktopScreenshotPngPages(pages, it) }.toByteArray()
        val image = ImageIO.read(bytes.inputStream())
        val expected = ImageIO.read(raster(429).bytes.inputStream())
        try {
            assertEquals(320, image.width); assertEquals(429, image.height)
            for (y in 0 until image.height) assertEquals(expected.getRGB(140, y), image.getRGB(140, y), "row $y")
        } finally { image.flush(); expected.flush() }
        var checks = 0
        assertFailsWith<CancellationException> { writeDesktopScreenshotPngPages(pages, ByteArrayOutputStream()) {
            if (++checks == 3) throw CancellationException("synthetic-stop")
        } }
    }

    @Test fun `PDF pages stay continuous across render frames rather than adding a page break per frame`() {
        val height = 1703
        val pages = (0 until height step 137).map { top -> raster(minOf(137, height - top), top) }
        val reference = ByteArrayOutputStream().also { DesktopScreenshotDocuments.pdf(raster(height), it) }.toByteArray()
        val sliced = ByteArrayOutputStream().also { DesktopScreenshotDocuments.pdf(pages, it) }.toByteArray()
        Loader.loadPDF(reference).use { full -> Loader.loadPDF(sliced).use { paged ->
            assertEquals(full.numberOfPages, paged.numberOfPages)
            val fullRenderer = PDFRenderer(full); val pagedRenderer = PDFRenderer(paged)
            repeat(full.numberOfPages) { index ->
                val a = fullRenderer.renderImage(index); val b = pagedRenderer.renderImage(index)
                try {
                    assertEquals(a.width, b.width); assertEquals(a.height, b.height)
                    for (y in 38 until a.height - 26 step 47) assertEquals(a.getRGB(100, y), b.getRGB(100, y), "page $index row $y")
                } finally { a.flush(); b.flush() }
            }
        } }
    }

    @Test fun `bounded rendering of one layout stitches to original PNG with no text lost at boundaries`() = runBlocking {
        val messages = (1..4).map { index -> ChatMessage("m$index", "synthetic", MessageRole.USER,
            "合成段落 $index\n" + "完整合成文字 ".repeat(35), createdAt = index.toLong(), updatedAt = index.toLong()) }
        val request = DesktopScreenshotRequest("合成分页", DesktopPrimaryChatState(assistantSegmentedBubblesEnabled = false),
            messages, messages.map { roleplayLegacyTextBlockId(it.id) }.toSet())
        val colors = desktopSemanticColors(ThemeMode.DARK, DefaultThemeColorHsv, false)
        val single = DesktopChatScreenshotRenderer.render(request, colors, { error("NO_RESOURCE") }, width = 320)
        val frames = DesktopChatScreenshotRenderer.renderPages(request, colors, { error("NO_RESOURCE") }, width = 320, pageRows = 137)
        assertTrue(frames.size > 1); assertEquals(single.height, frames.sumOf { it.height })
        val stitched = ByteArrayOutputStream().also { writeDesktopScreenshotPngPages(frames, it) }.toByteArray()
        val full = ImageIO.read(single.bytes.inputStream()); val paged = ImageIO.read(stitched.inputStream())
        try {
            assertEquals(full.width, paged.width); assertEquals(full.height, paged.height)
            for (y in 0 until full.height) for (x in 0 until full.width) assertEquals(full.getRGB(x, y), paged.getRGB(x, y), "pixel $x,$y")
        } finally { full.flush(); paged.flush() }
    }

    @Test fun `67 selected synthetic messages export all three formats beyond the single raster limit`() = runBlocking {
        val messages = (1..67).map { index -> ChatMessage("synthetic-$index", "synthetic", MessageRole.USER,
            "合成消息$index\n" + "完整合成段落文字。".repeat(150), createdAt = index.toLong(), updatedAt = index.toLong()) }
        val selected = messages.map { roleplayLegacyTextBlockId(it.id) }.toSet()
        val request = DesktopScreenshotRequest("合成67条", DesktopPrimaryChatState(assistantSegmentedBubblesEnabled = false), messages, selected)
        val colors = desktopSemanticColors(ThemeMode.DARK, DefaultThemeColorHsv, false)
        val oldFailure = assertFailsWith<DesktopScreenshotLimitException> {
            DesktopChatScreenshotRenderer.render(request, colors, { error("NO_RESOURCE") })
        }
        assertTrue(oldFailure.category in setOf("HEIGHT", "PIXELS"))
        val frames = DesktopChatScreenshotRenderer.renderPages(request, colors, { error("NO_RESOURCE") })
        assertTrue(frames.size > 1)
        frames.forEach { DesktopChatScreenshotRenderer.validatePlan(it.width, it.height) }
        val document = desktopScreenshotDocument(request)
        val root = Files.createTempDirectory("screenshot-paging-fixture-").toAbsolutePath().normalize()
        try {
            DesktopScreenshotExportFormat.entries.forEach { format ->
                val target = root.resolve("synthetic.${format.extension}")
                var proofs = 0
                assertEquals(target, saveDesktopScreenshotPagesVerified(format, frames, document, { proofs++ }, { error("NO_RESOURCE") }) { target })
                assertEquals(2, proofs); assertTrue(Files.size(target) > 0)
                when (format) {
                    DesktopScreenshotExportFormat.PNG -> ImageIO.createImageInputStream(target.toFile()).use { input ->
                        val reader = ImageIO.getImageReaders(input).next()
                        try { reader.input = input; assertEquals(frames.sumOf { it.height }, reader.getHeight(0)) }
                        finally { reader.dispose() }
                    }
                    DesktopScreenshotExportFormat.PDF -> Loader.loadPDF(target.toFile()).use { assertTrue(it.numberOfPages > frames.size) }
                    DesktopScreenshotExportFormat.WORD -> ZipInputStream(Files.newInputStream(target)).use { zip ->
                        var text = ""
                        while (true) { val entry = zip.nextEntry ?: break
                            if (entry.name == "word/document.xml") text = zip.readBytes().decodeToString()
                            zip.closeEntry()
                        }
                        messages.forEach { assertTrue(text.contains("合成消息${it.id.removePrefix("synthetic-")}")) }
                    }
                }
            }
            assertNull(saveDesktopScreenshotPagesVerified(DesktopScreenshotExportFormat.PNG, frames, document, {}, { error("NO_RESOURCE") }) { null })
            var checks = 0
            val stopped = root.resolve("cancelled.png")
            assertFailsWith<CancellationException> {
                writeDesktopScreenshotFile(stopped, "png") { output -> writeDesktopScreenshotPngPages(frames, output) {
                    if (++checks == 3) throw CancellationException("synthetic-stop")
                } }
            }
            assertFalse(Files.exists(stopped))
            assertTrue(Files.list(root).use { stream -> stream.noneMatch { it.fileName.toString().endsWith(".part") } })
            val review = System.getenv("CCB_SCREENSHOT_PAGING_REVIEW_DIR")?.let { Path.of(it) }
            if (review != null) {
                Files.createDirectories(review)
                Files.write(review.resolve("synthetic-first.png"), frames.first().bytes)
                Files.write(review.resolve("synthetic-last.png"), frames.last().bytes)
            }
        } finally {
            check(root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `resource limits remain and diagnostics never include an arbitrary private exception message`() {
        assertFailsWith<DesktopScreenshotLimitException> { DesktopChatScreenshotRenderer.validatePlan(1600, 32000) }
        val text = desktopScreenshotFailureDetails(DesktopScreenshotLimitException("RESOURCE_COUNT", 41, 40))
        assertTrue("41" in text && "40" in text)
        assertFalse(desktopScreenshotFailureDetails(IllegalStateException("PRIVATE_SYNTHETIC_BODY" )).contains("PRIVATE_SYNTHETIC_BODY"))
    }
}
