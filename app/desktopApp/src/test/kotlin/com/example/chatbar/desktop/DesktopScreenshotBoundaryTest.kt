package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.data.repository.VoiceMessagePlacement
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopScreenshotBoundaryTest {
    private val output = Path.of("build/p11-completion-20261021/boundary-previews")

    @Test fun `audio is excluded from selection and rejected before preview resource access`(): Unit = runBlocking {
        val empty = ChatMessage("empty", "s", MessageRole.ASSISTANT, "", createdAt = 1, updatedAt = 1)
        val text = empty.copy(id = "text", content = "A visible reply")
        val recording = roleplayVoiceBlockId(text.id, "voice")
        assertFalse(empty.isSelectableForChatScreenshot(true, listOf("voice")))
        assertTrue(cleanChatScreenshotSelection(setOf(recording), listOf(text), true, mapOf(text.id to listOf("voice"))).isEmpty())
        assertTrue(orderedChatScreenshotMessages(listOf(text), setOf(recording), true, mapOf(text.id to listOf("voice"))).isEmpty())
        val all = desktopScreenshotBlockIds(text).toSet()
        assertTrue(all.isNotEmpty()); assertFalse(recording in all)
        val colors = desktopSemanticColors(ThemeMode.LIGHT, DefaultThemeColorHsv, false)
        var reads = 0
        assertFailsWith<IllegalArgumentException> {
            DesktopChatScreenshotRenderer.render(DesktopScreenshotRequest("test", DesktopPrimaryChatState(), listOf(text), all + recording), colors, { reads++; error("must not read") })
        }
        assertEquals(0, reads)
        val png = DesktopChatScreenshotRenderer.render(DesktopScreenshotRequest("test", DesktopPrimaryChatState(), listOf(text), all), colors, { error("no resources") })
        assertEquals(640, ImageIO.read(ByteArrayInputStream(png.bytes)).width)
    }

    @Test fun `fit window can go below manual minimum for budget valid extreme PNG without changing export`(): Unit = runBlocking {
        DesktopChatScreenshotRenderer.validatePlan(320, 25000)
        val original = DesktopImageEditing.png(BufferedImage(320, 25000, BufferedImage.TYPE_INT_RGB))
        val png = DesktopScreenshotPng(original, 320, 25000)
        val decoded = ImageIO.read(ByteArrayInputStream(original))
        val viewport = DesktopScreenshotViewport(decoded.width, decoded.height)
        viewport.resize(360, 100)
        viewport.fitTo(DesktopScreenshotFit.WINDOW)
        assertTrue(viewport.contentHeight <= 100.0001, "entire long PNG must fit preview height")
        assertTrue(viewport.contentWidth <= 360.0001)
        assertEquals(.004, viewport.zoom, .000001)
        assertEquals("0.40%", desktopScreenshotZoomLabel(viewport.zoom))
        viewport.resize(280, 60)
        assertTrue(viewport.contentHeight <= 60.0001)
        assertEquals(0.0, viewport.x); assertEquals(0.0, viewport.y)
        viewport.manualZoom(.00001); assertEquals(.01, viewport.zoom)
        viewport.manualZoom(100.0); assertEquals(8.0, viewport.zoom)
        viewport.fitTo(DesktopScreenshotFit.WINDOW)
        Files.createDirectories(output)
        val target = Files.createTempDirectory(output, "extreme-").resolve("original.png")
        DesktopChatScreenshotRenderer.save(png, target)
        assertContentEquals(original, Files.readAllBytes(target))
        assertContentEquals(original, png.bytes)
        val saved = ImageIO.read(target.toFile())
        assertEquals(320, saved.width); assertEquals(25000, saved.height)
    }
}
