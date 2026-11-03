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
import androidx.compose.ui.graphics.toArgb
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.math.min

internal enum class DesktopScreenshotExportFormat(val label: String, val extension: String) {
    PNG("PNG", "png"), PDF("PDF", "pdf"), WORD("Word", "docx");
    fun fileName(title: String) = buildChatScreenshotFileName(title).removeSuffix(".png") + ".$extension"
}

/** Shared physical page grid; background painting stays independent of bounded render-frame sizes. */
internal fun desktopScreenshotPdfRows(width: Int): Int =
    ((PDRectangle.A4.height - 48f) / ((PDRectangle.A4.width - 48f) / width)).toInt().coerceAtLeast(1)

internal sealed interface DesktopScreenshotDocumentBlock {
    data class Text(val text: String, val heading: Boolean = false, val right: Boolean = false,
        val runs: List<DesktopWordTextRun> = emptyList()) : DesktopScreenshotDocumentBlock
    data class Picture(val reference: String, val right: Boolean = false) : DesktopScreenshotDocumentBlock
    data class Header(val name: String, val avatar: String, val right: Boolean = false) : DesktopScreenshotDocumentBlock
    data class Appearance(val background: String?, val opacity: Float, val backgroundArgb: Int,
        val foregroundArgb: Int) : DesktopScreenshotDocumentBlock
}

internal fun desktopScreenshotDocumentReferences(blocks: List<DesktopScreenshotDocumentBlock>): List<String> =
    blocks.mapNotNull { when (it) {
        is DesktopScreenshotDocumentBlock.Picture -> it.reference
        is DesktopScreenshotDocumentBlock.Header -> it.avatar
        is DesktopScreenshotDocumentBlock.Appearance -> it.background
        is DesktopScreenshotDocumentBlock.Text -> null
    } }.distinct()

private fun desktopWordXmlText(text: String): String = buildString {
    text.codePoints().forEach { cp ->
        if (cp == 9 || cp == 10 || cp == 13 || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF)
            appendCodePoint(cp)
    }
}

/** Immutable selection projection: no repository reads, audio, reasoning, or unselected source text. */
internal fun desktopScreenshotDocument(request: DesktopScreenshotRequest, colors: DesktopSemanticColors? = null): List<DesktopScreenshotDocumentBlock> {
    val blocks = mutableListOf<DesktopScreenshotDocumentBlock>()
    if (colors != null) blocks += DesktopScreenshotDocumentBlock.Appearance(
        request.background?.takeIf { request.contentOptions.includeBackground }, request.state.backgroundOpacity,
        colors.background.toArgb(), colors.foreground.toArgb())
    fun text(value: String, heading: Boolean = false, right: Boolean = false) {
        if (value.isNotBlank()) blocks += DesktopScreenshotDocumentBlock.Text(value, heading, right)
    }
    fun header(value: String, avatar: String?, right: Boolean = false) {
        if (avatar == null) text(value, true, right) else blocks += DesktopScreenshotDocumentBlock.Header(value, avatar, right)
    }
    text(request.title, true)
    request.messages.forEach { message ->
        if (request.selected.none { roleplayBlockMessageId(it) == message.id }) return@forEach
        val presented = desktopPresentMessage(message, request.state.selectedCharacter, request.state.globalPlayerName,
            request.state.assistantSegmentedBubblesEnabled, session = request.state.selectedSession)
        if (!desktopScreenshotMessageVisible(message, presented, request.selected, request.contentOptions)) return@forEach
        val right = message.role == com.example.chatbar.data.local.entity.MessageRole.USER
        val speakerHeaders = desktopScreenshotSpeakerHeaders(presented, request.selected, request.contentOptions)
        if (presented.showWholeMessageHeader) header(presented.speakerLabel, desktopScreenshotHeaderAvatarReference(message, request.state), right)
        message.images.forEachIndexed { index, reference ->
            if (roleplayImageBlockId(message.id, index) in request.selected)
                blocks += DesktopScreenshotDocumentBlock.Picture(reference, right)
        }
        presented.segments.forEachIndexed { index, segment ->
            val id = if (presented.segmented) checkNotNull(segment.blockId) else roleplayLegacyTextBlockId(message.id)
            if (id in request.selected && desktopScreenshotIncludes(segment, request.contentOptions)) {
                if (segment.kind == RoleplaySegmentKind.STATUS) {
                    text(desktopScreenshotStatusLabel(segment), true, right)
                    text(segment.text, right = right)
                } else {
                    if (index in speakerHeaders) header(segment.speaker?.displayName ?: presented.speakerLabel,
                        desktopPresentedAvatarReference(segment.speaker, request.state.selectedCharacter))
                    blocks += desktopWordMarkdown(desktopSanitizeRoleplayMarkdown(segment.markdownSource), right)
                }
            }
        }
    }
    return blocks.toList()
}

/** Revalidate after the native chooser for all formats, preserving the original PNG save contract. */
internal suspend fun saveDesktopScreenshotDocumentVerified(format: DesktopScreenshotExportFormat,
    png: DesktopScreenshotPng, document: List<DesktopScreenshotDocumentBlock>,
    verifyMessages: suspend () -> Unit, read: (String) -> ByteArray, choose: suspend () -> Path?): Path? =
    saveDesktopScreenshotPagesVerified(format, listOf(png), document, verifyMessages, read, choose)

internal suspend fun saveDesktopScreenshotPagesVerified(format: DesktopScreenshotExportFormat,
    pages: List<DesktopScreenshotPng>, document: List<DesktopScreenshotDocumentBlock>,
    verifyMessages: suspend () -> Unit, read: (String) -> ByteArray, choose: suspend () -> Path?): Path? {
    require(pages.isNotEmpty())
    val hashes = buildMap {
        pages.forEach { page -> page.resourceHashes.forEach { (reference, hash) ->
            val old = put(reference, hash)
            check(old == null || old == hash) { "截图资源身份不一致" }
        } }
    }
    verifyMessages()
    withContext(Dispatchers.IO) { DesktopChatScreenshotRenderer.verifyResourceHashes(hashes, read) }
    val target = choose() ?: return null
    verifyMessages()
    withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        DesktopChatScreenshotRenderer.verifyResourceHashes(hashes, read)
        writeDesktopScreenshotFile(target, format.extension, { context.ensureActive() }) { output ->
            when (format) {
                DesktopScreenshotExportFormat.PNG -> writeDesktopScreenshotPngPages(pages, output) { context.ensureActive() }
                DesktopScreenshotExportFormat.PDF -> DesktopScreenshotDocuments.pdf(pages, output) { context.ensureActive() }
                DesktopScreenshotExportFormat.WORD -> DesktopScreenshotDocuments.word(document, hashes,
                    read, output) { context.ensureActive() }
            }
        }
    }
    return target.toAbsolutePath().normalize()
}

/** Independent Word export captures requested text and image evidence without requiring a PNG preview. */
internal suspend fun saveDesktopWordDocumentVerified(document: List<DesktopScreenshotDocumentBlock>,
    verifyMessages: suspend () -> Unit, read: (String) -> ByteArray, choose: suspend () -> Path?): Path? {
    verifyMessages()
    val references = desktopScreenshotDocumentReferences(document)
    if (references.size > 40) throw DesktopScreenshotLimitException("RESOURCE_COUNT", references.size.toLong(), 40)
    val hashes = withContext(Dispatchers.IO) {
        var bytesRead = 0L
        references.associateWith { reference ->
            currentCoroutineContext().ensureActive()
            val bytes = read(reference)
            bytesRead += bytes.size
            if (bytesRead > 32L*1024*1024) throw DesktopScreenshotLimitException("RESOURCE_BYTES", bytesRead, 32L*1024*1024)
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
    val target = choose() ?: return null
    verifyMessages()
    withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        DesktopChatScreenshotRenderer.verifyResourceHashes(hashes, read)
        writeDesktopScreenshotFile(target, "docx", { context.ensureActive() }) { output ->
            DesktopScreenshotDocuments.word(document, hashes, read, output) { context.ensureActive() }
        }
    }
    return target.toAbsolutePath().normalize()
}

internal object DesktopScreenshotDocuments {
    /** One lossless raster shared by A4 pages; clipping preserves every source pixel without stretching. */
    fun pdf(png: DesktopScreenshotPng, output: OutputStream, checkCancelled: () -> Unit = {}) {
        pdf(listOf(png), output, checkCancelled)
    }

    /** Bounded source rasters fill continuous A4 pages, including across render-frame boundaries. */
    fun pdf(pages: List<DesktopScreenshotPng>, output: OutputStream, checkCancelled: () -> Unit = {}) {
        require(pages.isNotEmpty() && pages.map { it.width }.distinct().size == 1)
        pages.forEach { png ->
            DesktopChatScreenshotRenderer.validatePlan(png.width, png.height)
            ImageIO.createImageInputStream(png.bytes.inputStream()).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("截图图片无法读取")
                try {
                    reader.input = input
                    require(reader.getWidth(0) == png.width && reader.getHeight(0) == png.height) { "截图尺寸已变化" }
                } finally { reader.dispose() }
            }
        }
        PDDocument().use { pdf ->
            val pageSize = PDRectangle.A4
            val margin = 24f
            val width = pages.first().width
            val scale = (pageSize.width - margin * 2) / width
            val rowsPerPage = desktopScreenshotPdfRows(width)
            var page: PDPage? = null
            var occupied = rowsPerPage
            pages.forEachIndexed { index, png ->
                checkCancelled()
                val image = PDImageXObject.createFromByteArray(pdf, png.bytes, "chat-screenshot-$index")
                var top = 0
                while (top < png.height) {
                    checkCancelled()
                    if (occupied == rowsPerPage) {
                        page = PDPage(pageSize).also(pdf::addPage)
                        occupied = 0
                    }
                    val rows = min(rowsPerPage - occupied, png.height - top)
                    PDPageContentStream(pdf, checkNotNull(page), PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                        content.addRect(margin, pageSize.height - margin - (occupied + rows) * scale, width * scale, rows * scale)
                        content.clip()
                        content.drawImage(image, margin, pageSize.height - margin - (occupied + png.height - top) * scale,
                            width * scale, png.height * scale)
                    }
                    top += rows
                    occupied += rows
                }
            }
            checkCancelled()
            pdf.save(output)
        }
    }

    private data class Picture(val id: Int, val bytes: ByteArray, val width: Int, val height: Int)

    /** One bounded opaque page image; source opacity is composed over the captured app theme. */
    private fun pageBackground(appearance: DesktopScreenshotDocumentBlock.Appearance, source: Picture?, id: Int): Picture {
        require(appearance.opacity.isFinite() && appearance.opacity in 0f..1f)
        val page = BufferedImage(640, 905, BufferedImage.TYPE_INT_RGB)
        val graphics = page.createGraphics()
        try {
            graphics.color = java.awt.Color(appearance.backgroundArgb, true)
            graphics.fillRect(0, 0, page.width, page.height)
            if (source != null) {
                val image = checkNotNull(ImageIO.read(source.bytes.inputStream()))
                try {
                    val scale = maxOf(page.width.toDouble() / image.width, page.height.toDouble() / image.height)
                    val width = kotlin.math.ceil(image.width * scale).toInt(); val height = kotlin.math.ceil(image.height * scale).toInt()
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, appearance.opacity)
                    graphics.drawImage(image, (page.width - width) / 2, (page.height - height) / 2, width, height, null)
                } finally { image.flush() }
            }
        } finally { graphics.dispose() }
        return try {
            val bytes = ByteArrayOutputStream().also { check(ImageIO.write(page, "png", it)) }.toByteArray()
            Picture(id, bytes, page.width, page.height)
        } finally { page.flush() }
    }

    /** Minimal OOXML document: real text and embedded PNGs; no macros, external assets, or HTML parts. */
    fun word(blocks: List<DesktopScreenshotDocumentBlock>, capturedHashes: Map<String, String>,
        read: (String) -> ByteArray, output: OutputStream, checkCancelled: () -> Unit = {}) {
        require(blocks.sumOf { when (it) {
            is DesktopScreenshotDocumentBlock.Text -> it.text.length.toLong()
            is DesktopScreenshotDocumentBlock.Header -> it.name.length.toLong()
            is DesktopScreenshotDocumentBlock.Picture -> 0L
            is DesktopScreenshotDocumentBlock.Appearance -> 0L
        } } <= 240_000)
        val references = desktopScreenshotDocumentReferences(blocks)
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
        val appearances = blocks.filterIsInstance<DesktopScreenshotDocumentBlock.Appearance>()
        require(appearances.size <= 1) { "文档页面外观快照重复" }
        val appearance = appearances.singleOrNull()
        val pagePicture = appearance?.let { pageBackground(it, it.background?.let(pictures::getValue), pictures.size + 1) }
        val foreground = appearance?.foregroundArgb?.let { "%06x".format(it and 0xffffff) }
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
                if (pagePicture != null) {
                    x.writeEmptyElement("Override"); x.writeAttribute("PartName", "/word/header.xml")
                    x.writeAttribute("ContentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml")
                }
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
                if (pagePicture != null) x.relationship("pageBackground", "$R/header", "header.xml")
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
                        is DesktopScreenshotDocumentBlock.Text -> x.paragraph(block, foreground)
                        is DesktopScreenshotDocumentBlock.Picture -> x.picture(checkNotNull(pictures[block.reference]), ++occurrence, right = block.right)
                        is DesktopScreenshotDocumentBlock.Header -> {
                            x.start("w", "p", W); x.alignment(block.right)
                            x.picture(checkNotNull(pictures[block.avatar]), ++occurrence, avatar = true, inline = true)
                            x.start("w", "r", W)
                            if (foreground != null) { x.start("w", "rPr", W); x.empty("w", "color", W); x.attr("w", "val", W, foreground); x.writeEndElement() }
                            x.start("w", "t", W)
                            x.attr("xml", "space", "http://www.w3.org/XML/1998/namespace", "preserve")
                            x.writeCharacters("  ${desktopWordXmlText(block.name)}"); x.writeEndElement(); x.writeEndElement(); x.writeEndElement()
                        }
                        is DesktopScreenshotDocumentBlock.Appearance -> Unit
                    }
                }
                x.start("w", "sectPr", W)
                if (pagePicture != null) {
                    x.empty("w", "headerReference", W); x.attr("w", "type", W, "default"); x.attr("r", "id", R, "pageBackground")
                }
                x.empty("w", "pgSz", W); x.attr("w", "w", W, "11906"); x.attr("w", "h", W, "16838")
                x.empty("w", "pgMar", W)
                for (side in listOf("top", "right", "bottom", "left")) x.attr("w", side, W, "720")
                x.writeEndElement(); x.writeEndElement(); x.writeEndElement()
            }
            if (pagePicture != null) {
                xml("word/_rels/header.xml.rels") { x ->
                    x.writeStartElement("Relationships"); x.writeDefaultNamespace(RELATIONSHIPS)
                    x.relationship("image${pagePicture.id}", "$R/image", "media/image${pagePicture.id}.png"); x.writeEndElement()
                }
                xml("word/header.xml") { x ->
                    x.writeStartElement("w", "hdr", W)
                    mapOf("w" to W, "r" to R, "wp" to WP, "a" to A, "pic" to PIC).forEach { (prefix, ns) -> x.writeNamespace(prefix, ns) }
                    x.picture(pagePicture, 1_000_001, background = true); x.writeEndElement()
                }
                checkCancelled(); zip.putNextEntry(ZipEntry("word/media/image${pagePicture.id}.png"))
                zip.write(pagePicture.bytes); zip.closeEntry()
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

    private fun XMLStreamWriter.paragraph(block: DesktopScreenshotDocumentBlock.Text, color: String? = null) {
        start("w", "p", W)
        alignment(block.right)
        require(block.runs.isEmpty() || block.runs.joinToString("") { it.text } == block.text) { "文档格式化文字快照不一致" }
        val runs = block.runs.ifEmpty { listOf(DesktopWordTextRun(block.text)) }
        runs.forEach { run -> textRun(run, block.heading, color) }
        writeEndElement()
    }

    private fun XMLStreamWriter.textRun(run: DesktopWordTextRun, heading: Boolean, color: String?) {
        start("w", "r", W)
        start("w", "rPr", W)
        empty("w", "rFonts", W); attr("w", "eastAsia", W, "Microsoft YaHei"); attr("w", "ascii", W, if (run.code) "Consolas" else "Calibri")
        if (heading || run.bold) empty("w", "b", W)
        if (run.italic) empty("w", "i", W)
        if (color != null) { empty("w", "color", W); attr("w", "val", W, color) }
        empty("w", "sz", W); attr("w", "val", W, run.fontSizeSp?.let { (it * 1.5f).toInt().coerceAtLeast(2).toString() }
            ?: if (heading) "26" else "22")
        writeEndElement()
        // XML 1.0 cannot represent isolated controls/surrogates. Keep all valid Chinese/emoji text.
        desktopWordXmlText(run.text).replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, line ->
            if (index > 0) empty("w", "br", W)
            line.split('\t').forEachIndexed { tab, value ->
                if (tab > 0) empty("w", "tab", W)
                start("w", "t", W); attr("xml", "space", "http://www.w3.org/XML/1998/namespace", "preserve")
                writeCharacters(value); writeEndElement()
            }
        }
        writeEndElement()
    }

    private fun XMLStreamWriter.alignment(right: Boolean) {
        if (right) {
            start("w", "pPr", W)
            empty("w", "jc", W); attr("w", "val", W, "right")
            writeEndElement()
        }
    }

    private fun XMLStreamWriter.picture(picture: Picture, occurrence: Int, avatar: Boolean = false,
        right: Boolean = false, inline: Boolean = false, background: Boolean = false) {
        val maxWidth = if (avatar) 381_000.0 else 6_640_000.0
        val maxHeight = if (avatar) 381_000.0 else 8_686_800.0
        val scale = min(1.0, min(maxWidth / (picture.width * 9525.0), maxHeight / (picture.height * 9525.0)))
        val cx = if (background) "7560310" else (picture.width * 9525.0 * scale).toLong().coerceAtLeast(1).toString()
        val cy = if (background) "10692130" else (picture.height * 9525.0 * scale).toLong().coerceAtLeast(1).toString()
        if (!inline) { start("w", "p", W); alignment(right) }
        start("w", "r", W); start("w", "drawing", W); start("wp", if (background) "anchor" else "inline", WP)
        if (background) {
            for ((name, value) in mapOf("distT" to "0", "distB" to "0", "distL" to "0", "distR" to "0", "simplePos" to "0",
                "relativeHeight" to "0", "behindDoc" to "1", "locked" to "0", "layoutInCell" to "1", "allowOverlap" to "1")) writeAttribute(name, value)
            empty("wp", "simplePos", WP); writeAttribute("x", "0"); writeAttribute("y", "0")
            for (position in listOf("positionH", "positionV")) {
                start("wp", position, WP); writeAttribute("relativeFrom", "page")
                start("wp", "posOffset", WP); writeCharacters("0"); writeEndElement(); writeEndElement()
            }
        }
        empty("wp", "extent", WP); writeAttribute("cx", cx); writeAttribute("cy", cy)
        if (background) empty("wp", "wrapNone", WP)
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
        writeEndElement(); writeEndElement(); writeEndElement(); writeEndElement(); writeEndElement(); writeEndElement(); writeEndElement()
        if (!inline) writeEndElement()
    }

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
}
