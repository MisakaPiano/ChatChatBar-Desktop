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
    private val output = Path.of("build/post-p8-screenshot-r2-boundary-20261021/boundary-previews")

    @Test fun `voice only reply retains selectable recording through preview and PNG export`(): Unit = runBlocking {
        val message = ChatMessage("voice-only", "s", MessageRole.ASSISTANT, "", createdAt = 1, updatedAt = 1)
        val voice = GeneratedVoiceMessage(
            id = "v", sessionId = "s", messageId = message.id, sourceOrder = 0,
            sourceSegmentKind = "narration", sourceSpeakerName = "Alice", sourceText = "",
            taggedText = "", characterId = "c", characterName = "Alice recording",
            voice = FishAudioVoiceBinding("synthetic", "fixture"), fishModelId = "fixture",
            audioPath = "audio/never-read.mp3", durationMs = 31000, byteLength = 1,
            createdAt = 1, updatedAt = 1,
        )
        val voices = mapOf(message.id to listOf(VoiceMessagePlacement(voice, null)))
        val voiceIds = mapOf(message.id to listOf(voice.id))
        val ids = roleplayScreenshotBlockIds(message, true, voiceIds.getValue(message.id)).toSet()
        assertEquals(setOf(roleplayVoiceBlockId(message.id, voice.id)), ids)
        assertEquals(listOf(message), desktopScreenshotGroups(listOf(message)).single().messages)
        val selected = toggleChatScreenshotMessageSelection(emptySet(), ids, ids)
        assertEquals(ids, cleanChatScreenshotSelection(selected, listOf(message), true, voiceIds))
        val chosen = orderedChatScreenshotMessages(listOf(message), selected)
        assertEquals(listOf(message), chosen, "recording-only reply must survive preview filtering")
        val colors = desktopSemanticColors(ThemeMode.LIGHT, DefaultThemeColorHsv, false)
        val request = DesktopScreenshotRequest("recording fixture", DesktopPrimaryChatState(), chosen, selected, voices)
        val png = DesktopChatScreenshotRenderer.render(request, colors, { error("recording export must not read audio or network") })
        val empty = DesktopChatScreenshotRenderer.render(request.copy(voices = mapOf(message.id to listOf(VoiceMessagePlacement(voice.copy(characterName = "Different recording", durationMs = 9000), null)))), colors, { error("no resources") })
        assertFalse(png.bytes.contentEquals(empty.bytes), "recording name and duration must contribute actual raster content")
        Files.createDirectories(output)
        val target = output.resolve("voice-only.png")
        // Each test stage has a separate working output, so no previous evidence is replaced.
        DesktopChatScreenshotRenderer.save(png, target)
        assertContentEquals(png.bytes, Files.readAllBytes(target))
        val image = ImageIO.read(target.toFile())
        assertEquals(png.width, image.width); assertEquals(png.height, image.height)
        assertFailsWith<IllegalArgumentException> { DesktopChatScreenshotRenderer.save(png, target) }
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
        viewport.resize(280, 60)
        assertTrue(viewport.contentHeight <= 60.0001)
        assertEquals(0.0, viewport.x); assertEquals(0.0, viewport.y)
        viewport.manualZoom(.00001); assertEquals(.01, viewport.zoom)
        viewport.manualZoom(100.0); assertEquals(8.0, viewport.zoom)
        viewport.fitTo(DesktopScreenshotFit.WINDOW)
        Files.createDirectories(output)
        val target = output.resolve("extreme-original.png")
        DesktopChatScreenshotRenderer.save(png, target)
        assertContentEquals(original, Files.readAllBytes(target))
        assertContentEquals(original, png.bytes)
        val saved = ImageIO.read(target.toFile())
        assertEquals(320, saved.width); assertEquals(25000, saved.height)
    }
}
