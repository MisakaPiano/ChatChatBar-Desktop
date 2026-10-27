package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.data.operation.AppDataOperationGate
import java.io.IOException
import java.nio.file.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopVoiceSafetyTest {
    @Test fun `reference proof handles Windows case identity and rejects relative legacy ambiguity`() = runBlocking {
        val root = Files.createTempDirectory("s7-reference-identity-")
        val store = DesktopVoiceAudioStore(root)
        val clip = store.persist("session", "clip", fishPreviewMp3().inputStream())
        val repo = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
        val v = GeneratedVoiceMessage.create("session", "message", "version", null, 0, "WHOLE_MESSAGE", "", "hello", "hello",
            "hello", "alice", "Alice", FishAudioVoiceBinding("reference", "snapshot"), "s2.1-pro-free", clip.path, clip.durationMs, clip.byteLength)
        if (System.getProperty("os.name").startsWith("Windows")) {
            repo.save(v.copy(audioPath = clip.path.uppercase()))
            assertTrue(repo.isAudioReferenced(clip.path))
        }
        repo.save(v.copy(audioPath = "relative-legacy.mp3"))
        assertFails { repo.isAudioReferenced(clip.path) }
        assertTrue(Files.exists(Path.of(clip.path)))
    }
    @Test fun `playback closes the native alias before releasing its data operation lease`() = runBlocking {
        val root = Files.createTempDirectory("s7-playback-lease-")
        val audio = DesktopVoiceAudioStore(root)
        val clip = audio.persist("session", "artifact", fishPreviewMp3().inputStream())
        var active = false; var alias = false; var closedUnderLease = false
        val gate = object : AppDataOperationGate {
            override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
                active = true; try { return operation() } finally { assertFalse(alias); active = false }
            }
        }
        val output = object : DesktopAudioOutput {
            override suspend fun play(path: Path) { assertTrue(active); alias = true }
            override suspend fun isPlaying() = alias
            override suspend fun stopAndDrain() { if (alias) { assertTrue(active); closedUnderLease = true; alias = false } }
            override suspend fun closeAndDrain() { stopAndDrain() }
        }
        val voice = GeneratedVoiceMessage.create("session", "message", "version", null, 0, "WHOLE_MESSAGE", "", "hello", "hello",
            "hello", "alice", "Alice", FishAudioVoiceBinding("reference", "snapshot"), "s2.1-pro-free", clip.path, clip.durationMs, clip.byteLength)
        val playback = DesktopVoicePlayback(audio, output, gate)
        try {
            playback.playSequence(listOf(voice))
            withTimeout(3000) { while (!playback.state.value.playing) delay(10) }
            playback.stop(); assertTrue(closedUnderLease); assertFalse(active)
        } finally { playback.closeAndDrain() }
    }
    @Test fun `formal recording exceeds preview duration without weakening the preview profile`() = runBlocking {
        val root = Files.createTempDirectory("s7-long-formal-")
        val store = DesktopVoiceAudioStore(root)
        val bytes = fishPreviewMp3(5000) // about 130 seconds, bounded to about 2 MiB
        val audio = store.persist("session", "long-formal", bytes.inputStream())
        assertTrue(audio.durationMs > 120_000)
        assertFails { DesktopMp3Validation.validate(Path.of(audio.path), 16L * 1024 * 1024) }
        val commands = DesktopMciCommands { command -> if (command.contains(" length ")) "130612" else "" }
        val native = DesktopWindowsAudioOutput(commands)
        try {
            assertFails { native.play(Path.of(audio.path)) }
            native.playFormal(Path.of(audio.path)); native.stopAndDrain()
        } finally { native.closeAndDrain() }
    }
    @Test fun `post commit exception has positive strict reference proof and restart retains audio`() = runBlocking {
        val root = Files.createTempDirectory("s7-uncertain-voice-")
        var fail = true
        val gate = object : AppDataOperationGate {
            override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
                val result = operation()
                if (fail && Files.exists(root.resolve("entities/generated_voice_messages"))) {
                    fail = false; throw IOException("synthetic post-write failure")
                }
                return result
            }
        }
        val audio = DesktopVoiceAudioStore(root)
        val clip = audio.persist("session", "artifact", fishPreviewMp3().inputStream())
        val voice = GeneratedVoiceMessage.create("session", "message", "version", null, 0, "WHOLE_MESSAGE", "", "hello", "hello",
            "hello", "alice", "Alice", FishAudioVoiceBinding("reference", "snapshot"), "s2.1-pro-free", clip.path, clip.durationMs, clip.byteLength)
        val repo = VoiceMessageRepository(JsonFileStorage(root, gate), strictReads = true)
        assertFailsWith<IOException> { repo.save(voice) }
        assertEquals(voice, (repo.readDurable(voice.id) as JsonFileStorage.EntityReadResult.Valid).value)
        assertTrue(repo.isAudioReferenced(clip.path)); repo.reload(); assertEquals(voice, repo.get(voice.id))
        assertEquals(voice, VoiceMessageRepository(JsonFileStorage(root), strictReads = true).get(voice.id))
        assertTrue(Files.exists(Path.of(clip.path)))
    }

    @Test fun `unknown inventory cannot authorize deleting an orphan from an interrupted commit`() = runBlocking {
        val root = Files.createTempDirectory("s7-orphan-preserve-")
        val store = DesktopVoiceAudioStore(root)
        val clip = store.persist("session", "crash-before-entity", fishPreviewMp3().inputStream())
        val dir = Files.createDirectories(root.resolve("entities/generated_voice_messages"))
        Files.writeString(dir.resolve("future.json"), """{"futureSchema":999}""")
        val repo = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
        assertFails { repo.isAudioReferenced(clip.path) }
        assertFails { repo.initialize() }
        assertEquals(Path.of(clip.path), DesktopVoiceAudioStore(root).verify(clip.path))
    }
}
