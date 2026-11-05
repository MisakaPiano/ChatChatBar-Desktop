package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.repository.VoiceMessageRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceGenerationCommitFailureTest {
    @get:Rule val temp = TemporaryFolder()
    private suspend fun fixture() = VoiceGenerationTestFixture(temp.newFolder()).also { it.initialize() }

    @Test fun confirmedMissingRecordCleansOnlyNewAudio() = runTest {
        val f = fixture(); val voice = f.voice()
        val failing = VoiceGenerationCommitter(f.chats, f.voices, deleteUncommittedAudio = f::deleteAudio,
            save = { _, _, _ -> throw IOException("synthetic failure before write") })
        assertTrue(runCatching { failing.commit(f.snapshot, voice) }.isFailure)
        assertFalse(File(voice.audioPath).exists())
        assertFalse(f.storage.exists("generated_voice_messages", voice.id))
    }

    @Test fun failedAcknowledgementAfterActualDiskWriteKeepsReferencedAudio() = runTest {
        val f = fixture(); val voice = f.voice()
        val failing = VoiceGenerationCommitter(f.chats, f.voices, deleteUncommittedAudio = f::deleteAudio,
            save = { v, _, active ->
                active()
                f.storage.saveEntityUncached("generated_voice_messages", v.id, v, GeneratedVoiceMessage.serializer())
                throw IOException("synthetic acknowledgement failure after atomic disk write, before repository cache update")
            })
        assertTrue(runCatching { failing.commit(f.snapshot, voice) }.isFailure)
        assertTrue(File(voice.audioPath).isFile); assertTrue(f.removed.isEmpty())
        val reopened = VoiceMessageRepository(f.storage)
        assertEquals(voice.audioPath, reopened.get(voice.id)?.audioPath)
        assertEquals(voice.audioPath, f.voices.get(voice.id)?.audioPath)
        assertEquals(listOf(voice.id), f.voices.deleteForMessage("old").map { it.id })
        assertFalse(f.storage.exists("generated_voice_messages", voice.id))
    }

    @Test fun unreadableOrCorruptCommitResultRetainsAudioAndReportsUncertainty() = runTest {
        val f = fixture(); val voice = f.voice()
        val failing = VoiceGenerationCommitter(f.chats, f.voices, deleteUncommittedAudio = f::deleteAudio,
            save = { v, _, _ ->
                File(f.files, "entities/generated_voice_messages/${v.id}.json").apply { parentFile!!.mkdirs(); writeText("{corrupt") }
                throw IOException("synthetic failed save with unreadable result")
            })
        val error = runCatching { failing.commit(f.snapshot, voice) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.suppressed.any { it.message?.contains("无法确认") == true })
        assertTrue(File(voice.audioPath).isFile); assertTrue(f.removed.isEmpty())
    }
}
