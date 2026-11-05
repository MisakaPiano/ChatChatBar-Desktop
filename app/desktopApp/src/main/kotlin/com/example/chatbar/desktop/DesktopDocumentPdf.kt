package com.example.chatbar.desktop

import com.example.chatbar.domain.chat.RoleplaySegmentKind
import java.awt.Color
import java.io.OutputStream
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.util.Matrix

/** Selectable text, independent pagination and embedded owned media from the same Word snapshot. */
internal object DesktopDocumentPdf {
    fun write(blocks: List<DesktopScreenshotDocumentBlock>, hashes: Map<String, String>,
        read: (String) -> ByteArray, output: OutputStream, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        val pictures = DesktopScreenshotDocuments.pictures(blocks, hashes, read, checkCancelled)
        val appearances = blocks.filterIsInstance<DesktopScreenshotDocumentBlock.Appearance>()
        require(appearances.size <= 1) { "文档页面外观快照重复" }
        val appearance = appearances.singleOrNull()
        PDDocument().use { pdf ->
            val fonts = listOf("NotoSans-Regular.ttf", "NotoEmoji-Variable.ttf", "DroidSansFallback.ttf").map { name ->
                checkCancelled()
                checkNotNull(javaClass.getResourceAsStream("/document-fonts/$name")) { "文档导出字体缺失" }
                    .use { PDType0Font.load(pdf, it, true) }
            }
            val images = pictures.mapValues { (_, picture) ->
                checkCancelled(); PDImageXObject.createFromByteArray(pdf, picture.bytes, "document-image-${picture.id}")
            }
            val background = appearance?.let {
                DesktopScreenshotDocuments.pageBackground(it, it.background?.let(pictures::getValue), pictures.size + 1)
            }?.let { PDImageXObject.createFromByteArray(pdf, it.bytes, "document-page-background") }
            val writer = Writer(pdf, fonts, images, background, appearance?.foregroundArgb ?: 0xff172033.toInt(), checkCancelled)
            try {
                blocks.forEach { block ->
                    checkCancelled()
                    when (block) {
                        is DesktopScreenshotDocumentBlock.Appearance -> Unit
                        is DesktopScreenshotDocumentBlock.Text -> writer.text(block)
                        is DesktopScreenshotDocumentBlock.Header -> writer.header(block)
                        is DesktopScreenshotDocumentBlock.Picture -> writer.picture(block)
                    }
                }
                writer.close()
                checkCancelled()
                pdf.documentInformation.producer = "ChatChatBar Desktop"
                pdf.save(output)
            } finally { writer.close() }
        }
    }

    private data class Glyph(val text: String, val font: PDFont, val size: Float, val bold: Boolean,
        val italic: Boolean, val width: Float)

    private class Writer(private val pdf: PDDocument, private val fonts: List<PDType0Font>,
        private val images: Map<String, PDImageXObject>, private val background: PDImageXObject?,
        private val foreground: Int, private val checkCancelled: () -> Unit) {
        private val pageSize = PDRectangle.A4
        private val margin = 36f
        private val available = pageSize.width - margin * 2
        private val fontByCodepoint = mutableMapOf<Int, PDFont>()
        private val mono = PDType1Font(Standard14Fonts.FontName.COURIER)
        private var content: PDPageContentStream? = null
        private var y = pageSize.height - margin

        private fun stream(): PDPageContentStream {
            if (content == null) newPage()
            return checkNotNull(content)
        }

        private fun newPage() {
            checkCancelled(); require(pdf.numberOfPages < 1024) { "文档页数超过限制" }
            close()
            val page = PDPage(pageSize); pdf.addPage(page)
            content = PDPageContentStream(pdf, page)
            background?.let { stream().drawImage(it, 0f, 0f, pageSize.width, pageSize.height) }
            y = pageSize.height - margin
        }

        private fun room(height: Float) {
            require(height.isFinite() && height >= 0 && height <= pageSize.height - margin * 2)
            if (content == null || y - height < margin) newPage()
        }

        private fun glyph(cp: Int, run: DesktopWordTextRun, defaultSize: Float): Glyph {
            val text = String(Character.toChars(cp))
            val font = if (run.code && cp in 32..126) mono else fontByCodepoint.getOrPut(cp) {
                fonts.firstOrNull { it.hasGlyph(cp) } ?: error("文档字体不支持 Unicode U+${cp.toString(16).uppercase()}")
            }
            val size = run.fontSizeSp?.times(.75f) ?: defaultSize
            require(size.isFinite() && size in 4f..72f) { "文档文字尺寸无效" }
            val width = font.getStringWidth(text) / 1000f * size
            require(width.isFinite() && width >= 0 && width <= available)
            return Glyph(text, font, size, run.bold, run.italic, width)
        }

        fun text(block: DesktopScreenshotDocumentBlock.Text, extraLeft: Float = 0f, extraRight: Float = 0f, after: Float = 7f) {
            require(block.runs.isEmpty() || block.runs.joinToString("") { it.text } == block.text) { "文档格式化文字快照不一致" }
            val style = block.segmentStyle
            val defaultSize = style?.fontSizeSp?.times(.75f) ?: if (block.heading) 13f else 11f
            val fraction = style?.widthFraction ?: 1f
            require(fraction.isFinite() && fraction in .1f..1f)
            val gap = available * (1f - fraction)
            val inset = if (style?.kind in listOf(RoleplaySegmentKind.DIALOGUE, RoleplaySegmentKind.THOUGHT))
                minOf(36f, available / 4) else gap / 2
            val left = margin + extraLeft + if (block.right) gap - inset.coerceAtMost(gap) else inset
            val width = minOf(available * fraction, available - extraLeft - extraRight).coerceAtLeast(36f)
            val line = mutableListOf<Glyph>()
            var used = 0f
            fun flush() {
                val height = maxOf(style?.lineHeightSp?.times(.75f) ?: defaultSize * 1.45f,
                    (line.maxOfOrNull { it.size } ?: defaultSize) * 1.35f)
                val padding = if (style?.fillArgb == null) 0f else 3f
                room(height + padding * 2)
                val drawing = stream()
                drawing.beginMarkedContent(COSName.getPDFName("CCB${style?.kind?.name ?: "TEXT"}"))
                style?.fillArgb?.let { fill ->
                    drawing.setNonStrokingColor(Color(fill, true)); drawing.addRect(left - padding, y - height - padding, width + padding * 2, height + padding * 2); drawing.fill()
                }
                val color = Color(style?.foregroundArgb ?: foreground, true)
                drawing.setNonStrokingColor(color); drawing.setStrokingColor(color)
                var x = if (block.right) left + width - used else left
                var index = 0
                while (index < line.size) {
                    val first = line[index]
                    var end = index + 1
                    while (end < line.size && line[end].font === first.font && line[end].size == first.size &&
                        line[end].bold == first.bold && line[end].italic == first.italic) end++
                    val value = line.subList(index, end).joinToString("") { it.text }
                    drawing.setLineWidth(.2f)
                    drawing.beginText(); drawing.setFont(first.font, first.size)
                    drawing.setRenderingMode(if (first.bold || block.heading) RenderingMode.FILL_STROKE else RenderingMode.FILL)
                    drawing.setTextMatrix(Matrix(1f, 0f, if (first.italic) .18f else 0f, 1f, x, y - first.size))
                    drawing.showText(value); drawing.endText()
                    x += line.subList(index, end).sumOf { it.width.toDouble() }.toFloat()
                    index = end
                }
                drawing.endMarkedContent()
                y -= height + padding * 2
                line.clear(); used = 0f
            }
            val runs = block.runs.ifEmpty { listOf(DesktopWordTextRun(block.text)) }
            var codepoints = 0
            runs.forEach { run ->
                val clean = desktopWordXmlText(run.text).replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
                clean.codePoints().forEach { cp ->
                    if (++codepoints % 256 == 0) checkCancelled()
                    if (cp == 10) flush() else {
                        val next = glyph(cp, run, defaultSize)
                        if (line.isNotEmpty() && used + next.width > width) flush()
                        line += next; used += next.width
                    }
                }
                checkCancelled()
            }
            if (line.isNotEmpty()) flush()
            y -= after
        }

        fun header(block: DesktopScreenshotDocumentBlock.Header) {
            room(36f)
            val top = y
            val image = images.getValue(block.avatar)
            val size = 26f
            val scale = minOf(size / image.width, size / image.height)
            val width = image.width * scale; val height = image.height * scale
            stream().drawImage(image, (if (block.right) pageSize.width - margin - size else margin) + (size - width) / 2,
                top - (size + height) / 2, width, height)
            text(DesktopScreenshotDocumentBlock.Text(block.name, right = block.right),
                extraLeft = if (block.right) 0f else 34f, extraRight = if (block.right) 34f else 0f, after = 0f)
            y = minOf(y, top - size - 7f)
        }

        fun picture(block: DesktopScreenshotDocumentBlock.Picture) {
            val image = images.getValue(block.reference)
            val naturalWidth = image.width * .75f; val naturalHeight = image.height * .75f
            val scale = minOf(1f, available / naturalWidth, (pageSize.height - margin * 2 - 8f) / naturalHeight)
            val width = naturalWidth * scale; val height = naturalHeight * scale
            room(height + 8f)
            stream().drawImage(image, if (block.right) pageSize.width - margin - width else margin, y - height, width, height)
            y -= height + 8f
        }

        fun close() { content?.close(); content = null }
    }
}
