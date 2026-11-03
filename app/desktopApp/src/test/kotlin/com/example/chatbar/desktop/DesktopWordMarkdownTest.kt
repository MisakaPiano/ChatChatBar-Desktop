package com.example.chatbar.desktop

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class DesktopWordMarkdownTest {
    @Test fun `editable Word retains real chat markdown text runs lists code and nested emphasis without loading remote content`() {
        val source = """
            # Synthetic heading

            **Strong 中文** and *italic* and `literal <code>`.

            - First item
            - Second **bold** item

            > Quoted content

            [visible link](https://synthetic.invalid/not-fetched) ![image alt](https://synthetic.invalid/not-loaded.png)
            <!-- private hidden -->
        """.trimIndent()
        val blocks = desktopWordMarkdown(source, right = true)
        val text = blocks.joinToString("") { it.text }
        assertEquals(desktopMarkdown(source).text, text)
        assertTrue("Strong 中文" in text && "First item" in text && "Quoted content" in text && "image alt" in text)
        assertFalse("private hidden" in text || "https://" in text || "**" in text)
        val runs = blocks.flatMap { it.runs }
        assertTrue(runs.any { it.bold && "Strong 中文" in it.text })
        assertTrue(runs.any { it.italic && "italic" in it.text })
        assertTrue(runs.any { it.code && "literal <code>" in it.text })
        assertTrue(blocks.all { it.right && it.runs.joinToString("") { run -> run.text } == it.text })
        val bytes = ByteArrayOutputStream().also { output -> DesktopScreenshotDocuments.word(blocks,
            emptyMap(), { error("Remote or local resources must not be requested") }, output) }.toByteArray()
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break; assertNull(parts.put(entry.name, zip.readBytes())); zip.closeEntry()
        } }
        val xml = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(parts.getValue("word/document.xml").inputStream())
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        assertTrue(xml.getElementsByTagNameNS(w,"b").length > 0)
        assertTrue(xml.getElementsByTagNameNS(w,"i").length > 0)
        val fonts = xml.getElementsByTagNameNS(w,"rFonts")
        assertTrue((0 until fonts.length).any { (fonts.item(it) as org.w3c.dom.Element).getAttributeNS(w,"ascii") == "Consolas" })
        assertTrue(xml.getElementsByTagNameNS(w,"br").length > 0)
        assertFalse(parts.keys.any { it.startsWith("word/media/") })
        assertFalse(parts.values.any { "TargetMode=\"External\"" in it.decodeToString() })
        System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("editable-markdown.docx"); Files.createDirectories(path.parent); Files.write(path, bytes)
        }
    }

    @Test fun `formatted Word rejects mismatched text and run snapshots instead of silently losing text`() {
        assertFailsWith<IllegalArgumentException> {
            DesktopScreenshotDocuments.word(listOf(DesktopScreenshotDocumentBlock.Text("Original",
                runs = listOf(DesktopWordTextRun("Different")))), emptyMap(), { error("No resource") }, ByteArrayOutputStream())
        }
    }
}
