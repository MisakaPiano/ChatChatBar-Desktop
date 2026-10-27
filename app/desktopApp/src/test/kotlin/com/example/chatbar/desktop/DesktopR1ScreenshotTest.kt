package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopR1ScreenshotTest {
    @Test fun `persisted recording is not screenshot content and must not reject text`() = runBlocking {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize()
            val c = f.c.primaryChatController
            c.refresh(); c.selectSession("session")
            val timeline = c.screenshotTimeline()
            val colors = desktopSemanticColors(ThemeMode.LIGHT, com.example.chatbar.domain.appearance.DefaultThemeColorHsv, false)
            val selected = timeline.flatMap { desktopScreenshotBlockIds(it, true) }.toSet()
            val request = DesktopScreenshotRequest("synthetic", c.state.value, timeline, selected)
            val before = DesktopChatScreenshotRenderer.render(request, colors, { error("unexpected resource") })
            val voice = persistR1Recording(f)
            val repo = f.c.voiceRepository
            // Visual validation must not assert that the persisted audio inventory is empty.
            c.verifyScreenshotMessages(c.screenshotTimeline())
            val after = DesktopChatScreenshotRenderer.render(request.copy(voices = mapOf(f.source.id to repo.placementsForMessage(f.source))), colors, { error("unexpected resource") })
            assertContentEquals(before.bytes, after.bytes, "persisted audio must not change PNG pixels")
            assertFalse(selected.any { it.contains(voice.id) })
            assertFailsWith<IllegalArgumentException> {
                DesktopChatScreenshotRenderer.render(request.copy(selected = selected + com.example.chatbar.domain.chat.roleplayVoiceBlockId(f.source.id, voice.id)), colors, { error("audio is never a resource") })
            }
            val export = f.c.appDataRoot.resolve("r1-export.png")
            saveDesktopScreenshotVerified(after, { c.verifyScreenshotMessages(timeline) }, { error("no images") }) { export }
            assertContentEquals(after.bytes, java.nio.file.Files.readAllBytes(export))
            assertFailsWith<IllegalStateException> { c.verifyScreenshotMessages(timeline.reversed()) }
            assertFailsWith<IllegalStateException> { c.verifyScreenshotMessages(listOf(timeline.first(), timeline.first())) }
            assertFailsWith<IllegalStateException> { c.verifyScreenshotMessages(listOf(timeline.first().copy(sessionId = "foreign"))) }
            assertEquals(voice, com.example.chatbar.data.repository.VoiceMessageRepository(com.example.chatbar.data.local.JsonFileStorage(f.c.appDataRoot), strictReads = true).get(voice.id))
            f.c.voiceAudioStore.verify(voice.audioPath)
            assertTrue(f.requests.isEmpty())
        } finally { f.close() }
    }
}

internal suspend fun persistR1Recording(f: DesktopAssistantImageActionTest.Fixture): GeneratedVoiceMessage {
    val repo = f.c.voiceRepository
    val anchor = repo.ensureAnchors(f.source).first().anchor
    val audio = f.c.voiceAudioStore.persist("session", "r1-synthetic", fishPreviewMp3().inputStream())
    val voice = GeneratedVoiceMessage.create("session", f.source.id,
        MessageAlternativeVersionPolicy.activeVersion(f.source).id, anchor.id,
        0, "NARRATION", "", f.source.content, f.source.content, f.source.content,
        "alice", "Alice", FishAudioVoiceBinding("synthetic", "synthetic"),
        "s2.1-pro-free", audio.path, audio.durationMs, java.nio.file.Files.size(java.nio.file.Path.of(audio.path)))
    repo.save(voice)
    assertEquals(voice, com.example.chatbar.data.repository.VoiceMessageRepository(com.example.chatbar.data.local.JsonFileStorage(f.c.appDataRoot), strictReads = true).get(voice.id))
    assertEquals(listOf(voice.id), repo.placementsForMessage(f.source).map { it.voice.id })
    return voice
}
