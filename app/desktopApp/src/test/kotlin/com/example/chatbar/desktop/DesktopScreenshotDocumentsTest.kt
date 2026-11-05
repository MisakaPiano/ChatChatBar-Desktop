package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.domain.chat.roleplayImageBlockId
import com.example.chatbar.domain.chat.roleplayLegacyTextBlockId
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.w3c.dom.Document
import kotlin.test.*

class DesktopScreenshotDocumentsTest {
    private fun raster(width: Int = 320, height: Int = 100): DesktopScreenshotPng {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width)
            image.setRGB(x, y, if ((y / 200) % 2 == 0) 0xD02020 else 0x2040D0)
        return DesktopScreenshotPng(DesktopImageEditing.png(image), width, height)
    }
    private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun entries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; assertNull(put(entry.name, zip.readBytes())); zip.closeEntry() }
        }
    }
    private fun xml(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(bytes.inputStream())

    @Test fun `document includes exactly selected visible text and images without reasoning or unselected messages`() {
        val selected = ChatMessage("selected", "session", MessageRole.ASSISTANT,
            "正文 **可编辑** & <literal>", createdAt = 1, updatedAt = 1).copy(
            images = listOf("images/selected.png", "images/excluded.png"), reasoningContent = "PRIVATE_REASONING")
        val excluded = selected.copy(id = "excluded", content = "UNSELECTED_MESSAGE")
        val request = DesktopScreenshotRequest("选中内容", DesktopPrimaryChatState(assistantSegmentedBubblesEnabled = false),
            listOf(selected, excluded), setOf(roleplayLegacyTextBlockId("selected"), roleplayImageBlockId("selected", 0)))
        val blocks = desktopScreenshotDocument(request)
        val text = blocks.filterIsInstance<DesktopScreenshotDocumentBlock.Text>().joinToString { it.text }
        assertTrue("正文" in text && "可编辑" in text)
        assertFalse("PRIVATE_REASONING" in text || "UNSELECTED_MESSAGE" in text)
        assertEquals(listOf("images/selected.png"), blocks.filterIsInstance<DesktopScreenshotDocumentBlock.Picture>().map { it.reference })
    }

    @Test fun `included selected status is fully expanded independently from chat or legacy export collapse`() {
        val message = ChatMessage("assistant", "session", MessageRole.ASSISTANT,
            "旁白。\n```status\n姓名：林音\nHIDDEN_STATE\n```", createdAt = 1, updatedAt = 1)
        val ids = desktopScreenshotBlockIds(message)
        val request = DesktopScreenshotRequest("折叠状态", DesktopPrimaryChatState(), listOf(message), ids.toSet(),
            statusExpanded = ids.associateWith { false })
        val included = desktopScreenshotDocument(request).filterIsInstance<DesktopScreenshotDocumentBlock.Text>().joinToString { it.text }
        assertTrue("HIDDEN_STATE" in included)
        val excluded = desktopScreenshotDocument(request.copy(statusExpanded = ids.associateWith { true },
            contentOptions = DesktopScreenshotContentOptions(includeStatus = false)))
            .filterIsInstance<DesktopScreenshotDocumentBlock.Text>().joinToString { it.text }
        assertFalse("HIDDEN_STATE" in excluded); assertFalse("状态栏" in excluded)
    }

    @Test fun `Word contains escaped editable Chinese emoji paragraphs and occurrence preserving embedded pictures`() {
        val picture = raster().bytes
        val text = "可编辑中文 😀 <tag> & \"引用\"\n第二行\t尾部\u0001"
        val blocks = listOf(DesktopScreenshotDocumentBlock.Text(text),
            DesktopScreenshotDocumentBlock.Picture("images/a.png"), DesktopScreenshotDocumentBlock.Picture("images/a.png"))
        val result = ByteArrayOutputStream().also { DesktopScreenshotDocuments.word(blocks, mapOf("images/a.png" to hash(picture)), { picture }, it) }.toByteArray()
        val parts = entries(result)
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels", "word/media/image1.png"), parts.keys)
        val doc = xml(parts.getValue("word/document.xml"))
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val elements = doc.getElementsByTagNameNS(w, "t")
        val retained = (0 until elements.length).joinToString { elements.item(it).textContent }
        assertTrue("可编辑中文 😀 <tag> & \"引用\"" in retained)
        assertTrue("第二行" in retained && "尾部" in retained)
        assertEquals(1, doc.getElementsByTagNameNS(w, "br").length)
        assertEquals(1, doc.getElementsByTagNameNS(w, "tab").length)
        val drawings = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing", "docPr")
        assertEquals(2, drawings.length)
        assertNotEquals(drawings.item(0).attributes.getNamedItem("id").nodeValue, drawings.item(1).attributes.getNamedItem("id").nodeValue)
        assertEquals(1, xml(parts.getValue("word/_rels/document.xml.rels")).getElementsByTagName("Relationship").length)
        val embedded = ImageIO.read(parts.getValue("word/media/image1.png").inputStream())
        assertEquals(320, embedded.width); assertEquals(100, embedded.height)
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach {
            assertFalse("TargetMode=\"External\"" in it.toString(Charsets.UTF_8)); xml(it)
        }
        reviewDirectory()?.let { Files.write(it.resolve("editable-selection.docx"), result) }
    }

    @Test fun `editable Word keeps narration dialogue thought styles and shared section filtering with embedded media`() {
        val role = com.example.chatbar.data.local.entity.CharacterInfo("synthetic-role", "Alice", appearanceImage = "images/role.png")
        val card = com.example.chatbar.data.local.entity.CharacterCard.create("Synthetic card").copy(characters = listOf(role))
        val raw = "NarrationEditable **bold**<n=\"Alice\"/>[DialogueEditable]()『ThoughtEditable』\n" +
            "```status\n姓名：Alice\nStatusEditable\n```\n" +
            "────────────────\n**【行动选项】**\n1. ActionEditableOne\n2. ActionEditableTwo\n────────────────"
        val message = ChatMessage("styled", "s", MessageRole.ASSISTANT, raw,
            images = listOf("images/picture.png"), createdAt = 1, updatedAt = 1)
        val state = DesktopPrimaryChatState(selectedCharacter = card, assistantSegmentedBubblesEnabled = true,
            chatBubbleFontScale = 1.2f)
        val colors = desktopSemanticColors(ThemeMode.DARK, com.example.chatbar.domain.appearance.DefaultThemeColorHsv, false)
        val picture = raster(8, 8).bytes
        for (include in listOf(false, true)) {
            val request = DesktopScreenshotRequest("Synthetic styled Word", state, listOf(message),
                desktopScreenshotBlockIds(message).toSet(), background = "images/background.png",
                contentOptions = DesktopScreenshotContentOptions(include, include))
            val blocks = desktopScreenshotDocument(request, colors)
            val refs = desktopScreenshotDocumentReferences(blocks)
            assertTrue("images/role.png" in refs && "images/picture.png" in refs && "images/background.png" in refs)
            val result = ByteArrayOutputStream().also { out ->
                DesktopScreenshotDocuments.word(blocks, refs.associateWith { hash(picture) }, { picture }, out)
            }.toByteArray()
            val parts = entries(result); assertTrue("word/styles.xml" in parts)
            val doc = xml(parts.getValue("word/document.xml")); val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            val paragraphs = doc.getElementsByTagNameNS(w, "p")
            fun paragraph(token: String) = (0 until paragraphs.length).map { paragraphs.item(it) as org.w3c.dom.Element }
                .first { token in it.textContent }
            fun value(p: org.w3c.dom.Element, tag: String, attr: String = "val") =
                (p.getElementsByTagNameNS(w, tag).item(0) as org.w3c.dom.Element).getAttributeNS(w, attr)
            val n = paragraph("NarrationEditable"); val d = paragraph("DialogueEditable"); val t = paragraph("ThoughtEditable")
            assertEquals("CCBNARRATION", value(n, "pStyle")); assertEquals("CCBDIALOGUE", value(d, "pStyle"))
            assertEquals("CCBTHOUGHT", value(t, "pStyle"))
            assertEquals(3, listOf(n, d, t).map { value(it, "sz") }.distinct().size)
            for (paragraph in listOf(n, d, t)) {
                val properties = paragraph.getElementsByTagNameNS(w, "pPr").item(0).childNodes
                val order = (0 until properties.length).mapNotNull { (properties.item(it) as? org.w3c.dom.Element)?.localName }
                val ranks = order.map { listOf("pStyle", "shd", "spacing", "ind", "jc").indexOf(it) }
                assertTrue(ranks.all { it >= 0 }); assertEquals(ranks.sorted(), ranks, "Word pPr schema order")
            }
            assertNotEquals(value(n, "ind", "left"), value(d, "ind", "left"))
            assertNotEquals(value(d, "ind", "right"), value(t, "ind", "right"))
            assertTrue(d.getElementsByTagNameNS(w, "shd").length > 0 && t.getElementsByTagNameNS(w, "shd").length > 0)
            assertTrue(n.getElementsByTagNameNS(w, "b").length > 0)
            assertEquals(0, t.getElementsByTagNameNS(w, "i").length, "Inner speech is a semantic paragraph kind, not forced italic")
            assertEquals(include, "StatusEditable" in doc.documentElement.textContent)
            assertEquals(include, "ActionEditableOne" in doc.documentElement.textContent)
            assertTrue(doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/picture", "pic").length >= 2)
            assertTrue("word/header.xml" in parts && parts.keys.any { it.startsWith("word/media/") })
            val definitions = xml(parts.getValue("word/styles.xml")).getElementsByTagNameNS(w, "style")
            assertEquals(setOf("CCBNARRATION", "CCBDIALOGUE", "CCBTHOUGHT", "CCBSTATUS"),
                (0 until definitions.length).map { (definitions.item(it) as org.w3c.dom.Element).getAttributeNS(w, "styleId") }.toSet())
            parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { xml(it) }
            assertEquals(raw, message.content)
            reviewDirectory()?.let { Files.write(it.resolve("editable-role-kinds-sections$include.docx"), result) }
        }
    }

    @Test fun `Word refuses unselected or changed resources and never creates an export on either failure`() = runBlocking {
        val original = raster().bytes
        val changed = raster(height = 110).bytes
        val png = raster().copy(resourceHashes = mapOf("images/a.png" to hash(original)))
        val dir = Files.createTempDirectory("ccb-word-boundary-")
        try {
            for ((index, reference) in listOf("images/unselected.png", "images/a.png").withIndex()) {
                var reads = 0
                assertFails {
                    saveDesktopScreenshotDocumentVerified(DesktopScreenshotExportFormat.WORD, png,
                        listOf(DesktopScreenshotDocumentBlock.Picture(reference)), {}, {
                            reads++; if (reads > 2) changed else original
                        }) { dir.resolve("failure$index.docx") }
                }
            }
            Files.list(dir).use { assertEquals(0, it.count()) }
        } finally { Files.delete(dir) }
    }

    @Test fun `PDF reopens and all page boundaries preserve the long screenshot row order`() {
        val png = raster(height = 2500)
        val result = ByteArrayOutputStream().also { DesktopScreenshotDocuments.pdf(png, it) }.toByteArray()
        Loader.loadPDF(result).use { pdf ->
            val page = pdf.getPage(0).mediaBox
            val scale = (page.width - 48f) / png.width
            val rowsPerPage = ((page.height - 48f) / scale).toInt()
            assertEquals((png.height + rowsPerPage - 1) / rowsPerPage, pdf.numberOfPages)
            val renderer = PDFRenderer(pdf)
            for (index in 0 until pdf.numberOfPages) {
                val image = renderer.renderImage(index)
                val sampleY = 24 + (20 * scale).toInt()
                val actual = image.getRGB(60, sampleY) and 0xFFFFFF
                val expected = if (((index * rowsPerPage + 20) / 200) % 2 == 0) 0xD02020 else 0x2040D0
                assertEquals(expected, actual, "page $index must start with the next source rows")
            }
        }
        reviewDirectory()?.let { Files.write(it.resolve("paginated-selection.pdf"), result) }
    }

    @Test fun `all formats preserve cancellation new destination and post chooser message proofs`() = runBlocking {
        val png = raster()
        val document = listOf(DesktopScreenshotDocumentBlock.Text("纯本地合成样本"))
        val root = Files.createTempDirectory("ccb-doc-export-")
        try {
            for (format in DesktopScreenshotExportFormat.entries) {
                var version = 1
                val verify: suspend () -> Unit = { check(version == 1) }
                val target = root.resolve("export-${format.name}.${format.extension}")
                assertNull(saveDesktopScreenshotDocumentVerified(format, png, document, verify, { error("no images") }) { null })
                assertFailsWith<IllegalStateException> {
                    saveDesktopScreenshotDocumentVerified(format, png, document, verify, { error("no images") }) { version = 2; target }
                }
                assertFalse(Files.exists(target))
                version = 1
                assertEquals(target, saveDesktopScreenshotDocumentVerified(format, png, document, verify, { error("no images") }) { target })
                val before = Files.readAllBytes(target)
                assertFailsWith<IllegalArgumentException> {
                    saveDesktopScreenshotDocumentVerified(format, png, document, verify, { error("no images") }) { target }
                }
                assertContentEquals(before, Files.readAllBytes(target))
                Files.delete(target)
            }
            Files.list(root).use { assertEquals(0, it.count()) }
        } finally { Files.list(root).use { it.forEach(Files::delete) }; Files.delete(root) }
    }

    @Test fun `PDF cancellation and invalid dimensions leave no final or partial files`() {
        val root = Files.createTempDirectory("ccb-pdf-cancel-")
        try {
            val png = raster(height = 2500)
            assertFailsWith<CancellationException> {
                writeDesktopScreenshotFile(root.resolve("cancelled.pdf"), "pdf") { output ->
                    var checks = 0
                    DesktopScreenshotDocuments.pdf(png, output) { if (++checks == 2) throw CancellationException() }
                }
            }
            assertFailsWith<IllegalArgumentException> {
                writeDesktopScreenshotFile(root.resolve("invalid.pdf"), "pdf") { DesktopScreenshotDocuments.pdf(png.copy(height = 5), it) }
            }
            Files.list(root).use { assertEquals(0, it.count()) }
        } finally { Files.delete(root) }
    }

    private fun reviewDirectory(): Path? = System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { Files.createDirectories(Path.of(it)) }
}
