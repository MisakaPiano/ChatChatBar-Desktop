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
    @Test fun `excluding the only status leaves no reply header bubble or unused avatar dependency`() = runBlocking {
        val message = ChatMessage("status-only", "s", MessageRole.ASSISTANT, "```status\n姓名：Synthetic\nState\n```", createdAt = 1, updatedAt = 1)
        for (segmented in listOf(false, true)) {
            val state = DesktopPrimaryChatState(assistantSegmentedBubblesEnabled = segmented,
                selectedCharacter = CharacterCard("card", "Synthetic", avatar = "images/unused-missing.png", createdAt = 1, updatedAt = 1))
            val request = DesktopScreenshotRequest("Synthetic excluded", state, listOf(message),
                desktopScreenshotBlockIds(message, segmented).toSet(), contentOptions = DesktopScreenshotContentOptions(false, false))
            val document = desktopScreenshotDocument(request)
            assertEquals(listOf(DesktopScreenshotDocumentBlock.Text(request.title, true)), document)
            val png = DesktopChatScreenshotRenderer.render(request, desktopSemanticColors(ThemeMode.DARK, DefaultThemeColorHsv, false),
                { error("Excluded avatar must not be loaded") })
            assertTrue(png.resourceHashes.isEmpty())
            assertEquals("```status\n姓名：Synthetic\nState\n```", message.content)
        }
    }

    @Test fun `long dash inline actions follow all four export choices without changing narration images or chat classification`() = runBlocking {
        val content = "前方叙述提到行动选项，应保留。\n" +
            "────────────────\n**【行动选项】**\n1. 合成选择甲\n2. 合成选择乙\n────────────────\n" +
            "后续叙述保持原样。\n```status\n姓名：合成角色\n合成状态保持可选\n```"
        val message = ChatMessage("inline-actions", "s", MessageRole.ASSISTANT, content,
            images = listOf("images/synthetic-kept.png"), createdAt = 1, updatedAt = 1)
        assertTrue(com.example.chatbar.domain.chat.parseRoleplayTextSegments(content).any {
            it.kind == com.example.chatbar.domain.chat.RoleplaySegmentKind.NARRATION && "合成选择甲" in it.rawText
        })
        val bytes = org.jetbrains.skia.Surface.makeRasterN32Premul(8, 8).use { surface ->
            surface.canvas.clear(0xff336699.toInt())
            surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { it.bytes } }
        }
        for (segmented in listOf(false, true)) for (status in listOf(false, true)) for (actions in listOf(false, true)) {
            val request = DesktopScreenshotRequest("Inline export", DesktopPrimaryChatState(assistantSegmentedBubblesEnabled = segmented),
                listOf(message), desktopScreenshotBlockIds(message, segmented).toSet(),
                contentOptions = DesktopScreenshotContentOptions(status, actions))
            val doc = desktopScreenshotDocument(request)
            val text = doc.filterIsInstance<DesktopScreenshotDocumentBlock.Text>().joinToString { it.text }
            assertEquals(actions, "合成选择甲" in text); assertEquals(status, "合成状态保持可选" in text)
            assertTrue("前方叙述提到行动选项，应保留。" in text); assertTrue("后续叙述保持原样。" in text)
            assertEquals(listOf("images/synthetic-kept.png"), desktopScreenshotDocumentReferences(doc))
            val png = DesktopChatScreenshotRenderer.render(request,
                desktopSemanticColors(ThemeMode.DARK, DefaultThemeColorHsv, false), { bytes })
            assertTrue(png.resourceHashes.containsKey("images/synthetic-kept.png"))
            val pdf = ByteArrayOutputStream().also { DesktopScreenshotDocuments.pdf(png, it) }.toByteArray()
            Loader.loadPDF(pdf).use { assertTrue(it.numberOfPages >= 1) }
            val word = ByteArrayOutputStream().also { DesktopScreenshotDocuments.word(doc, png.resourceHashes, { bytes }, it) }.toByteArray()
            var xml = ""
            ZipInputStream(word.inputStream()).use { zip -> while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") xml = zip.readBytes().decodeToString()
                zip.closeEntry()
            } }
            assertEquals(actions, "合成选择甲" in xml); assertEquals(status, "合成状态保持可选" in xml)
            assertEquals(content, message.content)
            System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { folder ->
                val path = Path.of(folder).resolve("inline-segmented$segmented-status$status-actions$actions.png")
                Files.createDirectories(path.parent); Files.write(path, png.bytes)
            }
        }
    }

    @Test fun `inline action exclusion refuses ordinary prose and ambiguous numbered wrappers`() {
        for (text in listOf("普通叙述中的行动选项不能被删除。", "──────\n普通叙述\n1. 保留甲\n2. 保留乙\n──────",
            "──────\n**【行动选项】**\n2. 不是完整顺序\n3. 不推测删除\n──────")) {
            assertEquals(text, desktopScreenshotWithoutInlineActions(text))
        }
    }

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
