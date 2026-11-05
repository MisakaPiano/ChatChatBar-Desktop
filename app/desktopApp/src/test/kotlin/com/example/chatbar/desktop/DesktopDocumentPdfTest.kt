package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopDocumentPdfTest {
    private fun image(color: Color = Color(90, 160, 210)): ByteArray {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try { graphics.color = color; graphics.fillRect(0, 0, image.width, image.height) }
        finally { graphics.dispose() }
        return DesktopImageEditing.png(image)
    }

    private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `document PDF shares Word selection styles resources and four independent status action combinations`() {
        val person = CharacterInfo("person", "林音", appearanceImage = "images/avatar.png")
        val card = CharacterCard.create("Synthetic card").copy(characters = listOf(person))
        val raw = "Narration中文旁白 **强调**<n=\"林音\"/>[Dialogue中文对白]()『Thought中文内心』\n" +
            "```status\n姓名：林音\nStatusOnly状态证据\n```\n" +
            "────────────────\n**【行动选项】**\n1. ActionOnly行动一\n2. ActionOnly行动二\n────────────────"
        val message = ChatMessage("message", "session", MessageRole.ASSISTANT, raw,
            images = listOf("images/picture.png"), reasoningContent = "NotSelectedReasoning", createdAt = 1, updatedAt = 1)
        val state = DesktopPrimaryChatState(selectedCharacter = card, assistantSegmentedBubblesEnabled = true)
        val bytes = image()
        for (status in listOf(false, true)) for (actions in listOf(false, true)) {
            val request = DesktopScreenshotRequest("文档导出", state, listOf(message), desktopScreenshotBlockIds(message).toSet(),
                background = "images/background.png", contentOptions = DesktopScreenshotContentOptions(status, actions))
            val blocks = desktopScreenshotDocument(request, desktopSemanticColors(ThemeMode.LIGHT,
                com.example.chatbar.domain.appearance.DefaultThemeColorHsv, false))
            val hashes = desktopScreenshotDocumentReferences(blocks).associateWith { hash(bytes) }
            val output = ByteArrayOutputStream()
            DesktopDocumentPdf.write(blocks, hashes, { bytes }, output)
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                val text = PDFTextStripper().getText(pdf)
                assertTrue("Narration中文旁白" in text && "Dialogue中文对白" in text && "Thought中文内心" in text)
                assertFalse("NotSelectedReasoning" in text)
                assertEquals(status, "StatusOnly" in text)
                assertEquals(actions, "ActionOnly" in text)
                val content = pdf.pages.joinToString { it.contents.use { stream -> stream.readBytes().toString(Charsets.ISO_8859_1) } }
                for (kind in listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE, RoleplaySegmentKind.THOUGHT))
                    assertTrue("CCB${kind.name}" in content)
                assertTrue(pdf.pages.any { page -> page.resources.xObjectNames.count() >= 3 })
                assertTrue(pdf.pages.any { page -> page.resources.fontNames.any { page.resources.getFont(it).isEmbedded } })
                if (!status && !actions) review()?.let { folder ->
                    Files.write(folder.resolve("document-pdf-filter-both-off.pdf"), output.toByteArray())
                    ImageIO.write(PDFRenderer(pdf).renderImageWithDPI(0, 96f), "png", folder.resolve("document-pdf-filter-both-off.png").toFile())
                }
            }
        }
        assertTrue(DesktopScreenshotExportFormat.DOCUMENT_PDF.structured)
        assertFalse(DesktopScreenshotExportFormat.PDF.structured)
        assertNotEquals(DesktopScreenshotExportFormat.DOCUMENT_PDF.label, DesktopScreenshotExportFormat.PDF.label)
    }

    @Test fun `Chinese Unicode and rich runs remain selectable across real document pages with embedded background`() {
        val background = image(Color(235, 241, 250))
        val appearance = DesktopScreenshotDocumentBlock.Appearance("images/background.png", .3f, 0xfffafafa.toInt(), 0xff172033.toInt())
        val content = "可选择的中文，English words，表情 😀 🥰 🫠 ❤️ 与连接 👩‍💻。"
        val blocks = listOf(appearance, DesktopScreenshotDocumentBlock.Text("可搜索文档", heading = true),
            DesktopScreenshotDocumentBlock.Header("玩家姓名", "images/avatar.png", right = true)) +
            (0 until 90).map { index -> DesktopScreenshotDocumentBlock.Text("第${index}段 $content\n后续正文结束${index}",
                runs = listOf(DesktopWordTextRun("第${index}段 ", bold = true), DesktopWordTextRun(content, italic = true),
                    DesktopWordTextRun("\n后续正文结束${index}", code = true))) }
        val refs = desktopScreenshotDocumentReferences(blocks)
        val bytes = ByteArrayOutputStream().also { DesktopDocumentPdf.write(blocks, refs.associateWith { hash(background) }, { background }, it) }.toByteArray()
        Loader.loadPDF(bytes).use { pdf ->
            assertTrue(pdf.numberOfPages > 2)
            val text = PDFTextStripper().getText(pdf).replace("\r", "").replace("\n", "")
            assertTrue("可搜索文档" in text && "玩家姓名" in text)
            for (index in 0 until 90) assertTrue("第${index}段 $content" in text && "后续正文结束${index}" in text)
            assertTrue(pdf.pages.all { page -> page.resources.xObjectNames.any { page.resources.isImageXObject(it) } })
            review()?.let { folder ->
                Files.write(folder.resolve("document-pdf-chinese-pages.pdf"), bytes)
                ImageIO.write(PDFRenderer(pdf).renderImageWithDPI(0, 96f), "png", folder.resolve("document-pdf-first-page.png").toFile())
                ImageIO.write(PDFRenderer(pdf).renderImageWithDPI(pdf.numberOfPages - 1, 96f), "png", folder.resolve("document-pdf-last-page.png").toFile())
            }
        }
    }

    @Test fun `document PDF chooser cancellation and source or resource changes preserve existing destination`() = runBlocking {
        val parent = Files.createTempDirectory("document-pdf-verified-")
        try {
            val target = parent.resolve("existing.pdf")
            val original = "original destination".toByteArray(); Files.write(target, original)
            val picture = image()
            val blocks = listOf(DesktopScreenshotDocumentBlock.Text("中文正文"), DesktopScreenshotDocumentBlock.Picture("images/picture.png"))
            var checks = 0
            assertNull(saveDesktopStructuredDocumentVerified(DesktopScreenshotExportFormat.DOCUMENT_PDF, blocks,
                { checks++ }, { picture }) { null })
            assertEquals(1, checks); assertContentEquals(original, Files.readAllBytes(target))
            var changed = false
            assertFails {
                saveDesktopStructuredDocumentVerified(DesktopScreenshotExportFormat.DOCUMENT_PDF, blocks,
                    { check(!changed) }, { picture }) { changed = true; target }
            }
            assertContentEquals(original, Files.readAllBytes(target))
            var currentPicture = picture
            assertFails {
                saveDesktopStructuredDocumentVerified(DesktopScreenshotExportFormat.DOCUMENT_PDF, blocks,
                    {}, { currentPicture }) { currentPicture = image(Color.RED); target }
            }
            assertContentEquals(original, Files.readAllBytes(target))
            val saved = saveDesktopStructuredDocumentVerified(DesktopScreenshotExportFormat.DOCUMENT_PDF, blocks,
                {}, { picture }) { target }
            assertEquals(target.toAbsolutePath().normalize(), saved)
            Loader.loadPDF(Files.readAllBytes(target)).use { assertTrue("中文正文" in PDFTextStripper().getText(it)) }
            assertEquals(1, Files.list(parent).use { it.count() }.toInt())
        } finally { check(parent.toFile().deleteRecursively()) }
    }

    @Test fun `mid document cancellation and unsupported glyph refuse without raster replacement or partial overwrite`() {
        val parent = Files.createTempDirectory("document-pdf-cancel-")
        try {
            val target = parent.resolve("preserved.pdf"); val original = byteArrayOf(1, 2, 3); Files.write(target, original)
            var checks = 0
            assertFailsWith<CancellationException> {
                writeDesktopScreenshotFile(target, "pdf", {}) { out ->
                    DesktopDocumentPdf.write(listOf(DesktopScreenshotDocumentBlock.Text("长篇中文正文。".repeat(4000))), emptyMap(),
                        { error("No media") }, out) { if (++checks == 20) throw CancellationException("Synthetic stop") }
                }
            }
            assertContentEquals(original, Files.readAllBytes(target))
            assertFails {
                writeDesktopScreenshotFile(target, "pdf", {}) { out ->
                    DesktopDocumentPdf.write(listOf(DesktopScreenshotDocumentBlock.Text("\uDBFF\uDFFF")), emptyMap(),
                        { error("No media") }, out)
                }
            }
            assertContentEquals(original, Files.readAllBytes(target))
            assertEquals(1, Files.list(parent).use { it.count() }.toInt())
        } finally { check(parent.toFile().deleteRecursively()) }
    }

    private fun review(): Path? = System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let {
        Files.createDirectories(Path.of(it))
    }
}
