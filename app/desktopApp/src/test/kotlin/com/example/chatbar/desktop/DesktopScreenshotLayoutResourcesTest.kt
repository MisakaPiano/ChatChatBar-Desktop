package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.chat.roleplayImageBlockId
import com.example.chatbar.domain.chat.roleplayLegacyTextBlockId
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import kotlin.test.*

class DesktopScreenshotLayoutResourcesTest {
    private fun solid(color: Color) = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB).let { image ->
        image.createGraphics().let { g -> g.color = color; g.fillRect(0, 0, 64, 64); g.dispose() }
        DesktopImageEditing.png(image).also { image.flush() }
    }
    private fun review(name: String, bytes: ByteArray) {
        System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve(name); Files.createDirectories(path.parent); Files.write(path, bytes)
        }
    }
    @Test fun `exporting a later sentence in a speaker group restores the exact selected person's header`() = runBlocking {
        val person = CharacterInfo("person", "Alice", appearanceImage = "images/person.png")
        val card = CharacterCard("card", "Synthetic", characters = listOf(person), createdAt = 1, updatedAt = 1)
        val message = ChatMessage("m", "s", MessageRole.ASSISTANT, "<n=\"Alice\"/>[First]() [Second]()", createdAt = 1, updatedAt = 1)
        val state = DesktopPrimaryChatState(selectedCharacter = card)
        val presented = desktopPresentMessage(message, card, state.globalPlayerName, true)
        val dialogues = presented.segments.withIndex().filter { it.value.kind == com.example.chatbar.domain.chat.RoleplaySegmentKind.DIALOGUE }
        assertEquals(2, dialogues.size)
        val later = dialogues.last()
        val selected = setOf(assertNotNull(later.value.blockId))
        assertFalse(later.index in presented.speakerHeaderIndexes)
        assertEquals(setOf(later.index), desktopScreenshotSpeakerHeaders(presented, selected, DesktopScreenshotContentOptions()))
        val png = DesktopChatScreenshotRenderer.render(DesktopScreenshotRequest("Selected second", state, listOf(message), selected),
            desktopSemanticColors(ThemeMode.DARK, DefaultThemeColorHsv, false), { reference ->
                assertEquals("images/person.png", reference); solid(Color.GREEN)
            })
        assertEquals(setOf("images/person.png"), png.resourceHashes.keys)
        review("selected-later-person.png", png.bytes)
    }
    @Test fun `actual raster embeds selected player and card avatars and message picture with source ownership`() = runBlocking {
        val user = ChatMessage("u", "s", MessageRole.USER, "Synthetic **player** text", createdAt = 1, updatedAt = 1)
        val assistant = ChatMessage("a", "s", MessageRole.ASSISTANT, "Synthetic *assistant* text", createdAt = 2, updatedAt = 2,
            images = listOf("images/picture.png"))
        val excluded = assistant.copy(id = "excluded", images = listOf("images/missing.png"))
        val state = DesktopPrimaryChatState(assistantSegmentedBubblesEnabled = false, globalPlayerAvatar = "images/player.png",
            selectedCharacter = CharacterCard("card", "Synthetic assistant", avatar = "images/card.png", createdAt = 1, updatedAt = 1))
        val sources = mapOf("images/player.png" to solid(Color.RED), "images/card.png" to solid(Color.BLUE),
            "images/picture.png" to solid(Color.ORANGE))
        val reads = mutableListOf<String>()
        val request = DesktopScreenshotRequest("Synthetic avatars", state, listOf(user, assistant, excluded),
            setOf(roleplayLegacyTextBlockId(user.id), roleplayLegacyTextBlockId(assistant.id), roleplayImageBlockId(assistant.id, 0)))
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            val rendered = DesktopChatScreenshotRenderer.render(request, desktopSemanticColors(theme, DefaultThemeColorHsv, false),
                { reference -> reads += reference; sources.getValue(reference) }, width = 640)
            assertEquals(sources.keys, rendered.resourceHashes.keys)
            assertFalse("images/missing.png" in reads)
            val image = ImageIO.read(rendered.bytes.inputStream())
            try {
                for (color in listOf(Color.RED, Color.BLUE, Color.ORANGE)) {
                    var pixels = 0
                    for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x,y) == color.rgb) pixels++
                    assertTrue(pixels > 100, "Missing actual embedded color $color")
                }
            } finally { image.flush() }
            review("avatar-bubble-${theme.name.lowercase()}.png", rendered.bytes)
        }
        assertEquals("Synthetic **player** text", user.content)
        assertEquals(listOf("images/picture.png"), assistant.images)
        val document = desktopScreenshotDocument(request)
        val headers = document.filterIsInstance<DesktopScreenshotDocumentBlock.Header>()
        assertEquals(listOf("images/player.png", "images/card.png"), headers.map { it.avatar })
        assertTrue(headers.first().right); assertFalse(headers.last().right)
        val hashes = sources.mapValues { (_, bytes) -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
        val word = ByteArrayOutputStream().also { DesktopScreenshotDocuments.word(document, hashes, sources::getValue, it) }.toByteArray()
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(word.inputStream()).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break; assertNull(parts.put(entry.name, zip.readBytes())); zip.closeEntry()
        } }
        assertEquals(3, parts.keys.count { it.startsWith("word/media/") })
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { factory.newDocumentBuilder().parse(it.inputStream()) }
        val xml = factory.newDocumentBuilder().parse(parts.getValue("word/document.xml").inputStream())
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val paragraphs = xml.getElementsByTagNameNS(w, "p")
        val firstHeader = (0 until paragraphs.length).map { paragraphs.item(it) as org.w3c.dom.Element }.first {
            it.getElementsByTagNameNS(w, "drawing").length > 0 && it.textContent.contains(headers.first().name)
        }
        assertEquals("right", (firstHeader.getElementsByTagNameNS(w,"jc").item(0) as org.w3c.dom.Element).getAttributeNS(w, "val"))
        val extent = firstHeader.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing", "extent").item(0) as org.w3c.dom.Element
        assertEquals("381000", extent.getAttribute("cx")); assertEquals("381000", extent.getAttribute("cy"))
        review("avatar-editable-selection.docx", word)
    }
    @Test fun `bounded frames repeat crop at actual PDF page grid and preserve configured opacity`() = runBlocking {
        val background = BufferedImage(128, 512, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until background.height) for (x in 0 until background.width)
            background.setRGB(x,y,Color(y / 2, 70, 255 - y / 2).rgb)
        val bytes = DesktopImageEditing.png(background); background.flush()
        val message = ChatMessage("long", "s", MessageRole.ASSISTANT,
            (1..120).joinToString("\n") { "Synthetic line $it" }, createdAt = 1, updatedAt = 1)
        val state = DesktopPrimaryChatState(assistantSegmentedBubblesEnabled = false, backgroundOpacity = .55f)
        val request = DesktopScreenshotRequest("Synthetic pages", state, listOf(message),
            setOf(roleplayLegacyTextBlockId(message.id)), background = "images/background.png")
        val colors = desktopSemanticColors(ThemeMode.DARK, DefaultThemeColorHsv, false)
        val pages = DesktopChatScreenshotRenderer.renderPages(request, colors, { reference ->
            assertEquals("images/background.png", reference); bytes
        }, width = 320, pageRows = desktopScreenshotPdfRows(320))
        assertTrue(pages.size > 2)
        assertTrue(pages.dropLast(1).all { it.height == desktopScreenshotPdfRows(320) })
        val samples = pages.dropLast(1).map { page ->
            ImageIO.read(page.bytes.inputStream()).let { image -> image.getRGB(319, 200).also { image.flush() } }
        }
        assertEquals(1, samples.distinct().size)
        val opaque = DesktopChatScreenshotRenderer.renderPages(request.copy(state = state.copy(backgroundOpacity = 1f)), colors,
            { bytes }, width = 320, pageRows = desktopScreenshotPdfRows(320))
        val image = ImageIO.read(opaque.first().bytes.inputStream())
        try { assertNotEquals(samples.first(), image.getRGB(319, 200)) } finally { image.flush() }
        val pdf = ByteArrayOutputStream().also { DesktopScreenshotDocuments.pdf(pages, it) }.toByteArray()
        Loader.loadPDF(pdf).use { assertEquals(pages.size, it.numberOfPages) }
        val unchunked = DesktopChatScreenshotRenderer.render(request, colors, { bytes }, width = 320)
        val raster = ImageIO.read(unchunked.bytes.inputStream())
        try {
            assertEquals(samples.first(), raster.getRGB(319, 200))
            assertEquals(samples.first(), raster.getRGB(319, desktopScreenshotPdfRows(320) + 200))
        } finally { raster.flush() }
        review("background-page-first.png", pages.first().bytes); review("background-page-second.png", pages[1].bytes)
        review("background-pages.pdf", pdf)
        assertEquals(.55f, state.backgroundOpacity)
    }
}
