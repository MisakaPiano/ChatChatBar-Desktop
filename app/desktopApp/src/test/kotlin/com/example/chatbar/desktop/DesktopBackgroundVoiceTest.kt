package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class DesktopBackgroundVoiceTest {
    @Test fun `existing recording survives page and session changes with pause stop and adjacent clips`(): Unit = runBlocking {
        DesktopChatVoiceRuntimeTest.Fixture().use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val first = f.repo.listForSession(f.session.id).single()
            f.enqueue(); f.done(f.runtime.generate(f.message, "alice", additional = first))
            val clips = f.repo.placementsForMessage(f.message).map { it.voice }
            assertEquals(2, clips.size)
            f.runtime.foreground(f.session.id).join()
            f.runtime.play(clips.first(), f.message)?.join()
            suspend fun playing(id: String) = withTimeout(5000) {
                while (f.runtime.playback.state.value.let { !it.playing || it.currentVoiceId != id }) delay(10)
            }
            playing(clips.first().id)
            val requests = f.server.requestCount
            f.runtime.foreground(null).join()
            assertTrue(f.output.playing)
            assertEquals(f.session.id, f.runtime.playback.state.value.sessionId)
            f.runtime.pausePlayback()
            withTimeout(5000) { while (!f.runtime.playback.state.value.paused) delay(10) }
            f.runtime.foreground("another-session").join()
            f.runtime.resumePlayback(); playing(clips.first().id)
            f.runtime.adjacentPlayback(true).join(); playing(clips.last().id)
            f.runtime.adjacentPlayback(false).join(); playing(clips.first().id)
            assertEquals(requests, f.server.requestCount)
            f.runtime.stopForMaintenance()
            assertFalse(f.output.playing); assertNull(f.runtime.playback.state.value.currentVoiceId)
        }
    }

    @Test fun `session override controls the source session queue rather than the newly selected session`(): Unit = runBlocking {
        val preferences = MutableStateFlow(DesktopChatUiPreferences())
        DesktopChatVoiceRuntimeTest.Fixture(uiPreferences = preferences).use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val first = f.repo.listForSession(f.session.id).single()
            val secondMessage = f.chats.addMessage(ChatMessage("later", f.session.id, MessageRole.ASSISTANT,
                "<n=\"Alice\"/>[later]()", createdAt = 2, updatedAt = 2))
            f.enqueue(); f.done(f.runtime.generate(secondMessage, "alice"))
            val second = f.repo.placementsForMessage(secondMessage).single().voice
            preferences.value = DesktopChatUiPreferences(continuousVoicePlayback = false,
                sessionContinuousVoicePlayback = mapOf(f.session.id to true, "other" to false))
            f.runtime.foreground(f.session.id).join(); f.runtime.play(first, f.message)?.join()
            withTimeout(5000) { while (!f.runtime.playback.state.value.playing) delay(10) }
            assertEquals(listOf(first.id, second.id), f.runtime.playback.state.value.queueVoiceIds)
            f.runtime.foreground("other").join()
            assertTrue(f.runtime.playback.continuousPlayback.value)
            preferences.value = preferences.value.copy(sessionContinuousVoicePlayback = mapOf(f.session.id to false))
            withTimeout(5000) { while (f.runtime.playback.state.value.queueVoiceIds != listOf(first.id)) delay(10) }
            assertTrue(f.output.playing)
            assertEquals(2, f.server.requestCount)
        }
    }
}
