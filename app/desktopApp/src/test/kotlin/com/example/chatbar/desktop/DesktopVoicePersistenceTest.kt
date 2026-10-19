package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import java.nio.file.*
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopVoicePersistenceTest {
    private fun voice(path: String) = GeneratedVoiceMessage.create("session", "message", "version", "anchor",
        0, "DIALOGUE", "Alice", "hello", "hello", "hello", "alice", "Alice",
        FishAudioVoiceBinding(referenceId = "synthetic", title = "synthetic"), "s2.1-pro-free", path, 1300, 20850)

    @Test fun `durable artifact and official entity survive restart without credentials`() = runBlocking {
        val root = Files.createTempDirectory("s3-restart-")
        val store = DesktopVoiceAudioStore(root)
        val bytes = fishPreviewMp3()
        val audio = store.persist("session", "artifact", bytes.inputStream(), bytes.size.toLong())
        assertTrue(audio.durationMs > 0)
        val repo = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
        val v = voice(audio.path); repo.save(v)
        val restarted = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
        assertEquals(v, restarted.get(v.id))
        assertEquals(Path.of(audio.path), DesktopVoiceAudioStore(root).verify(audio.path))
        val output = PreviewOutputFake()
        val player = DesktopVoicePlayback(store, output)
        player.playSequence(listOf(v))
        withTimeout(3000) { while (!player.state.value.playing) delay(10) }
        player.stop(); assertFalse(output.playing)
        player.closeAndDrain()
        assertEquals(1, output.plays)
        restarted.delete(v.id); assertTrue(store.deleteIfOwned(audio.path))
        assertFalse(Files.exists(Path.of(audio.path)))
    }

    @Test fun `corrupt inventory fails closed and preserves all audio and unknown files`() = runBlocking {
        val root = Files.createTempDirectory("s3-corrupt-")
        val store = DesktopVoiceAudioStore(root)
        val audio = store.persist("session", "kept", fishPreviewMp3().inputStream())
        val unknown = root.resolve("audio/generated/session/unknown.mp3")
        Files.writeString(unknown, "unknown evidence")
        val dir = Files.createDirectories(root.resolve("entities/generated_voice_messages"))
        Files.writeString(dir.resolve("broken.json"), "{broken")
        assertFails { VoiceMessageRepository(JsonFileStorage(root), strictReads = true).initialize() }
        assertTrue(Files.exists(Path.of(audio.path))); assertTrue(Files.exists(unknown))
        assertFails { store.deleteIfOwned(unknown.toString()) }
        assertTrue(Files.exists(unknown))
    }

    @Test fun `invalid truncated oversized and cancelled streams never publish audio`() = runBlocking {
        val root = Files.createTempDirectory("s3-failed-")
        val store = DesktopVoiceAudioStore(root)
        assertFails { store.persist("session", "empty", byteArrayOf().inputStream()) }
        assertFails { store.persist("session", "truncated", fishPreviewMp3().copyOf(100).inputStream()) }
        assertFails { store.persist("session", "length", fishPreviewMp3().inputStream(), 7) }
        assertFails { store.persist("session", "large", byteArrayOf().inputStream(), 17L * 1024 * 1024) }
        assertFailsWith<CancellationException> {
            store.persist("session", "cancel", fishPreviewMp3().inputStream()) { throw CancellationException() }
        }
        Files.walk(root).use { paths -> assertEquals(0, paths.filter { Files.isRegularFile(it) }.count()) }
    }

    @Test fun `path traversal replacement mutation and external deletion are rejected`() = runBlocking {
        val root = Files.createTempDirectory("s3-owned-")
        val store = DesktopVoiceAudioStore(root)
        assertFails { store.persist("../outside", "bad", fishPreviewMp3().inputStream()) }
        val audio = store.persist("session", "one", fishPreviewMp3().inputStream())
        assertFails { store.persist("session", "one", fishPreviewMp3().inputStream()) }
        val outside = Files.createTempFile("s3-external-", ".mp3")
        assertFalse(store.deleteIfOwned(outside.toString())); assertTrue(Files.exists(outside))
        Files.write(Path.of(audio.path), fishPreviewMp3().also { it[100] = 1 })
        assertFails { store.verify(audio.path) }; assertFails { store.deleteIfOwned(audio.path) }
        assertTrue(Files.exists(Path.of(audio.path)))
    }

    @Test fun `native focus supersedes old port without letting it stop the new owner`() = runBlocking {
        val output = PreviewOutputFake(); val focus = DesktopAudioFocus(output)
        val preview = focus.port(); val formal = focus.port()
        val path = Files.createTempFile("s3-focus-", ".mp3"); Files.write(path, fishPreviewMp3())
        preview.play(path); formal.play(path)
        assertFalse(preview.isPlaying()); assertTrue(formal.isPlaying())
        preview.closeAndDrain(); assertTrue(formal.isPlaying())
        formal.stopAndDrain(); assertFalse(output.playing)
        focus.closeAndDrain(); assertEquals(1, output.closes)
    }
}
