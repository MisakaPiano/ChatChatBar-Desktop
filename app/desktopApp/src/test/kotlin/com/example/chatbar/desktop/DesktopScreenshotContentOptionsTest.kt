package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import kotlin.test.*

class DesktopScreenshotContentOptionsTest {
    @Test fun `four independent section choices apply equally to actual raster PDF and editable Word without message mutation`() = runBlocking {
        val content = """
            普通正文中提到行动选项，仍须保留。
            ```status
            姓名：合成人物
            合成状态正文
            ```
            ---
            【行动选项】
            1. 合成行动甲
            2. 合成行动乙
            ---
            合成结尾正文。
        """.trimIndent()
        val message = ChatMessage("synthetic", "session", MessageRole.ASSISTANT, content, createdAt = 1, updatedAt = 1)
        val colors = desktopSemanticColors(ThemeMode.DARK, DefaultThemeColorHsv, false)
        for (segmented in listOf(false, true)) {
            val ids = desktopScreenshotBlockIds(message, segmented).toSet()
            val heights = mutableMapOf<DesktopScreenshotContentOptions, Int>()
            for (status in listOf(false, true)) for (actions in listOf(false, true)) {
                val options = DesktopScreenshotContentOptions(status, actions)
                val request = DesktopScreenshotRequest("合成导出", DesktopPrimaryChatState(assistantSegmentedBubblesEnabled = segmented),
                    listOf(message), ids, statusExpanded = ids.associateWith { false }, contentOptions = options)
                val document = desktopScreenshotDocument(request)
                val retained = document.filterIsInstance<DesktopScreenshotDocumentBlock.Text>().joinToString { it.text }
                assertEquals(status, "合成状态正文" in retained)
                assertEquals(actions, "合成行动甲" in retained)
                assertTrue("普通正文中提到行动选项，仍须保留。" in retained)
                assertTrue("合成结尾正文。" in retained)
                val png = DesktopChatScreenshotRenderer.render(request, colors, { error("No synthetic resources") })
                heights[options] = png.height
                val pdf = ByteArrayOutputStream().also { DesktopScreenshotDocuments.pdf(png, it) }.toByteArray()
                Loader.loadPDF(pdf).use { assertTrue(it.numberOfPages >= 1) }
                val word = ByteArrayOutputStream().also { DesktopScreenshotDocuments.word(document, emptyMap(), { error("No resources") }, it) }.toByteArray()
                var xml = ""
                ZipInputStream(word.inputStream()).use { zip -> while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") xml = zip.readBytes().decodeToString()
                    zip.closeEntry()
                } }
                assertEquals(status, "合成状态正文" in xml); assertEquals(actions, "合成行动甲" in xml)
                System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { directory ->
                    val path = Path.of(directory); Files.createDirectories(path)
                    val stem = "content-segmented$segmented-status$status-actions$actions"
                    Files.write(path.resolve("$stem.png"), png.bytes)
                    Files.write(path.resolve("$stem.pdf"), pdf)
                    Files.write(path.resolve("$stem.docx"), word)
                }
                assertEquals(content, message.displayContent)
                assertTrue(request.statusExpanded.values.all { !it })
            }
            assertTrue(heights.getValue(DesktopScreenshotContentOptions()) > heights.getValue(DesktopScreenshotContentOptions(false, false)))
        }
    }
}
