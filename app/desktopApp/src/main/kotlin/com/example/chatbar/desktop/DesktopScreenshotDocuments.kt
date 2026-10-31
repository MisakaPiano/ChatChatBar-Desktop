package com.example.chatbar.desktop

import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.roleplayBlockMessageId
import com.example.chatbar.domain.chat.roleplayImageBlockId
import com.example.chatbar.domain.chat.roleplayLegacyTextBlockId
import java.io.OutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.math.min

internal enum class DesktopScreenshotExportFormat(val label: String, val extension: String) {
    PNG("PNG", "png"), PDF("PDF", "pdf"), WORD("Word", "docx");
    fun fileName(title: String) = buildChatScreenshotFileName(title).removeSuffix(".png") + ".$extension"
}

internal sealed interface DesktopScreenshotDocumentBlock {
    data class Text(val text: String, val heading: Boolean = false) : DesktopScreenshotDocumentBlock
    data class Picture(val reference: String) : DesktopScreenshotDocumentBlock
}

/** Immutable selection projection: no repository reads, audio, reasoning, or unselected source text. */
internal fun desktopScreenshotDocument(request: DesktopScreenshotRequest): List<DesktopScreenshotDocumentBlock> {
    val blocks = mutableListOf<DesktopScreenshotDocumentBlock>()
    val parser = Parser.builder().build()
    val textRenderer = TextContentRenderer.builder().build()
    fun text(value: String, heading: Boolean = false) {
        if (value.isNotBlank()) blocks += DesktopScreenshotDocumentBlock.Text(value, heading)
    }
    text(request.title, true)
    request.messages.forEach { message ->
        if (request.selected.none { roleplayBlockMessageId(it) == message.id }) return@forEach
        val presented = desktopPresentMessage(message, request.state.selectedCharacter, request.state.globalPlayerName,
            request.state.assistantSegmentedBubblesEnabled, session = request.state.selectedSession)
        if (presented.showWholeMessageHeader) text(presented.speakerLabel, true)
        message.images.forEachIndexed { index, reference ->
            if (roleplayImageBlockId(message.id, index) in request.selected)
                blocks += DesktopScreenshotDocumentBlock.Picture(reference)
        }
        presented.segments.forEach { segment ->
            val id = if (presented.segmented) checkNotNull(segment.blockId) else roleplayLegacyTextBlockId(message.id)
            if (id in request.selected) {
                if (segment.kind == RoleplaySegmentKind.STATUS) {
                    text("状态 / 选项", true)
                    if (request.statusExpanded[id] ?: segment.statusDefaultExpanded) text(segment.text)
                } else {
                    segment.speaker?.displayName?.let { text(it, true) }
                    text(textRenderer.render(parser.parse(desktopSanitizeRoleplayMarkdown(segment.text))).trimEnd())
                }
            }
        }
    }
    return blocks.toList()
}

/** Revalidate after the native chooser for all formats, preserving the original PNG save contract. */
internal suspend fun saveDesktopScreenshotDocumentVerified(format: DesktopScreenshotExportFormat,
    png: DesktopScreenshotPng, document: List<DesktopScreenshotDocumentBlock>,
    verifyMessages: suspend () -> Unit, read: (String) -> ByteArray, choose: suspend () -> Path?): Path? {
    verifyMessages()
    withContext(Dispatchers.IO) { DesktopChatScreenshotRenderer.verifyResources(png, read) }
    val target = choose() ?: return null
    verifyMessages()
    withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        DesktopChatScreenshotRenderer.verifyResources(png, read)
        writeDesktopScreenshotFile(target, format.extension, { context.ensureActive() }) { output ->
            when (format) {
                DesktopScreenshotExportFormat.PNG -> output.write(png.bytes)
                DesktopScreenshotExportFormat.PDF -> DesktopScreenshotDocuments.pdf(png, output) { context.ensureActive() }
                DesktopScreenshotExportFormat.WORD -> DesktopScreenshotDocuments.word(document, png.resourceHashes,
                    read, output) { context.ensureActive() }
            }
        }
    }
    return target.toAbsolutePath().normalize()
}

internal object DesktopScreenshotDocuments {
    /** One lossless raster shared by A4 pages; clipping preserves every source pixel without stretching. */
    fun pdf(png: DesktopScreenshotPng, output: OutputStream, checkCancelled: () -> Unit = {}) {
        DesktopChatScreenshotRenderer.validatePlan(png.width, png.height)
        ImageIO.createImageInputStream(png.bytes.inputStream()).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("截图图片无法读取")
            try {
                reader.input = input
                require(reader.getWidth(0) == png.width && reader.getHeight(0) == png.height) { "截图尺寸已变化" }
            } finally { reader.dispose() }
        }
        PDDocument().use { pdf ->
            val image = PDImageXObject.createFromByteArray(pdf, png.bytes, "chat-screenshot")
            val pageSize = PDRectangle.A4
            val margin = 24f
            val scale = (pageSize.width - margin * 2) / png.width
            val rowsPerPage = ((pageSize.height - margin * 2) / scale).toInt().coerceAtLeast(1)
            var top = 0
            while (top < png.height) {
                checkCancelled()
                val rows = min(rowsPerPage, png.height - top)
                val page = PDPage(pageSize)
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.addRect(margin, pageSize.height - margin - rows * scale, png.width * scale, rows * scale)
                    content.clip()
                    content.drawImage(image, margin, pageSize.height - margin - (png.height - top) * scale,
                        png.width * scale, png.height * scale)
                }
                top += rows
            }
            checkCancelled()
            pdf.save(output)
        }
    }

    private data class Picture(val id: Int, val bytes: ByteArray, val width: Int, val height: Int)

    /** Minimal OOXML document: real text and embedded PNGs; no macros, external assets, or HTML parts. */
    fun word(blocks: List<DesktopScreenshotDocumentBlock>, capturedHashes: Map<String, String>,
        read: (String) -> ByteArray, output: OutputStream, checkCancelled: () -> Unit = {}) {
        require(blocks.filterIsInstance<DesktopScreenshotDocumentBlock.Text>().sumOf { it.text.length.toLong() } <= 240_000)
        val references = blocks.filterIsInstance<DesktopScreenshotDocumentBlock.Picture>().map { it.reference }.distinct()
        require(references.size <= 40 && references.all { it in capturedHashes }) { "图片不属于当前预览" }
        var encodedBytes = 0L
        var pixels = 0L
        val pictures = references.mapIndexed { index, reference ->
            checkCancelled()
            val bytes = read(reference)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash == capturedHashes[reference]) { "截图资源已变化，请重新预览" }
            encodedBytes += bytes.size
            require(encodedBytes <= 32L * 1024 * 1024)
            val picture = Image.makeFromEncoded(bytes).use { image ->
                pixels += image.width.toLong() * image.height
                require(pixels <= DesktopChatScreenshotRenderer.MAX_PIXELS)
                val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
                Picture(index + 1, png, image.width, image.height)
            }
            reference to picture
        }.toMap()
        ZipOutputStream(output).use { zip ->
            fun xml(name: String, body: (XMLStreamWriter) -> Unit) {
                checkCancelled()
                zip.putNextEntry(ZipEntry(name))
                val writer = XMLOutputFactory.newFactory().createXMLStreamWriter(zip, "UTF-8")
                writer.writeStartDocument("UTF-8", "1.0")
                body(writer)
                writer.writeEndDocument()
                writer.flush()
                zip.closeEntry()
            }
            xml("[Content_Types].xml") { x ->
                x.writeStartElement("Types"); x.writeDefaultNamespace(CONTENT_TYPES)
                listOf("rels" to "application/vnd.openxmlformats-package.relationships+xml", "xml" to "application/xml", "png" to "image/png").forEach { (extension, type) ->
                    x.writeEmptyElement("Default"); x.writeAttribute("Extension", extension); x.writeAttribute("ContentType", type)
                }
                x.writeEmptyElement("Override"); x.writeAttribute("PartName", "/word/document.xml")
                x.writeAttribute("ContentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")
                x.writeEndElement()
            }
            xml("_rels/.rels") { x ->
                x.writeStartElement("Relationships"); x.writeDefaultNamespace(RELATIONSHIPS)
                x.relationship("officeDocument", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument", "word/document.xml")
                x.writeEndElement()
            }
            xml("word/_rels/document.xml.rels") { x ->
                x.writeStartElement("Relationships"); x.writeDefaultNamespace(RELATIONSHIPS)
                pictures.values.forEach { x.relationship("image${it.id}", "$R/image", "media/image${it.id}.png") }
                x.writeEndElement()
            }
            xml("word/document.xml") { x ->
                x.writeStartElement("w", "document", W)
                mapOf("w" to W, "r" to R, "wp" to WP, "a" to A, "pic" to PIC).forEach { (prefix, ns) -> x.writeNamespace(prefix, ns) }
                x.start("w", "body", W)
                var occurrence = 0
                blocks.forEach { block ->
                    checkCancelled()
                    when (block) {
                        is DesktopScreenshotDocumentBlock.Text -> x.paragraph(block)
                        is DesktopScreenshotDocumentBlock.Picture -> x.picture(checkNotNull(pictures[block.reference]), ++occurrence)
                    }
                }
                x.start("w", "sectPr", W)
                x.empty("w", "pgSz", W); x.attr("w", "w", W, "11906"); x.attr("w", "h", W, "16838")
                x.empty("w", "pgMar", W)
                for (side in listOf("top", "right", "bottom", "left")) x.attr("w", side, W, "720")
                x.writeEndElement(); x.writeEndElement(); x.writeEndElement()
            }
            pictures.values.forEach { picture ->
                checkCancelled(); zip.putNextEntry(ZipEntry("word/media/image${picture.id}.png"))
                zip.write(picture.bytes); zip.closeEntry()
            }
        }
    }

    private fun XMLStreamWriter.relationship(id: String, type: String, target: String) {
        writeEmptyElement("Relationship"); writeAttribute("Id", id); writeAttribute("Type", type); writeAttribute("Target", target)
    }
    private fun XMLStreamWriter.start(prefix: String, name: String, ns: String) = writeStartElement(prefix, name, ns)
    private fun XMLStreamWriter.empty(prefix: String, name: String, ns: String) = writeEmptyElement(prefix, name, ns)
    private fun XMLStreamWriter.attr(prefix: String, name: String, ns: String, value: String) = writeAttribute(prefix, ns, name, value)

    private fun XMLStreamWriter.paragraph(block: DesktopScreenshotDocumentBlock.Text) {
        start("w", "p", W)
        start("w", "r", W)
        start("w", "rPr", W)
        empty("w", "rFonts", W); attr("w", "eastAsia", W, "Microsoft YaHei"); attr("w", "ascii", W, "Calibri")
        if (block.heading) empty("w", "b", W)
        empty("w", "sz", W); attr("w", "val", W, if (block.heading) "26" else "22")
        writeEndElement()
        // XML 1.0 cannot represent isolated controls/surrogates. Keep all valid Chinese/emoji text.
        val clean = StringBuilder()
        block.text.codePoints().forEach { cp ->
            if (cp == 9 || cp == 10 || cp == 13 || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF) clean.appendCodePoint(cp)
        }
        clean.toString().replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, line ->
            if (index > 0) empty("w", "br", W)
            line.split('\t').forEachIndexed { tab, value ->
                if (tab > 0) empty("w", "tab", W)
                start("w", "t", W); attr("xml", "space", "http://www.w3.org/XML/1998/namespace", "preserve")
                writeCharacters(value); writeEndElement()
            }
        }
        writeEndElement(); writeEndElement()
    }

    private fun XMLStreamWriter.picture(picture: Picture, occurrence: Int) {
        val scale = min(1.0, min(6_640_000.0 / (picture.width * 9525.0), 8_686_800.0 / (picture.height * 9525.0)))
        val cx = (picture.width * 9525.0 * scale).toLong().coerceAtLeast(1).toString()
        val cy = (picture.height * 9525.0 * scale).toLong().coerceAtLeast(1).toString()
        start("w", "p", W); start("w", "r", W); start("w", "drawing", W); start("wp", "inline", WP)
        empty("wp", "extent", WP); writeAttribute("cx", cx); writeAttribute("cy", cy)
        empty("wp", "docPr", WP); writeAttribute("id", occurrence.toString()); writeAttribute("name", "图片 $occurrence")
        start("a", "graphic", A); start("a", "graphicData", A); writeAttribute("uri", PIC)
        start("pic", "pic", PIC); start("pic", "nvPicPr", PIC)
        empty("pic", "cNvPr", PIC); writeAttribute("id", "0"); writeAttribute("name", "image${picture.id}.png")
        empty("pic", "cNvPicPr", PIC); writeEndElement()
        start("pic", "blipFill", PIC); empty("a", "blip", A); attr("r", "embed", R, "image${picture.id}")
        start("a", "stretch", A); empty("a", "fillRect", A); writeEndElement(); writeEndElement()
        start("pic", "spPr", PIC); start("a", "xfrm", A)
        empty("a", "off", A); writeAttribute("x", "0"); writeAttribute("y", "0")
        empty("a", "ext", A); writeAttribute("cx", cx); writeAttribute("cy", cy); writeEndElement()
        start("a", "prstGeom", A); writeAttribute("prst", "rect"); empty("a", "avLst", A); writeEndElement()
        writeEndElement(); writeEndElement(); writeEndElement(); writeEndElement(); writeEndElement(); writeEndElement(); writeEndElement(); writeEndElement()
    }

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
}
