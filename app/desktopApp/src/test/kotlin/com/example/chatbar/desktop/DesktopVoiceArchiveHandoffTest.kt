package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import java.io.ByteArrayOutputStream
import java.nio.file.*
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopVoiceArchiveHandoffTest {
    @Test fun `v8 voice JSONL and bounded audio restore remap IDs and preserve anchor version contract`() = runBlocking {
        DesktopChatVoiceRuntimeTest.Fixture().use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val original = f.repo.listForSession(f.session.id).single()
            val handoff = DesktopVoiceArchiveHandoff(f.audio, f.repo)
            val plan = handoff.planExport(listOf(original), true)
            val records = ByteArrayOutputStream(); handoff.writeVoices(plan, records)
            assertTrue(records.toString("UTF-8").contains(DesktopVoiceArchiveHandoff.PREFIX))
            assertFalse(records.toString("UTF-8").contains(original.audioPath.replace("\\", "\\\\")))
            val bytes = ByteArrayOutputStream(); handoff.writeAudio(plan, plan.sources.keys.single(), bytes)
            assertContentEquals(Files.readAllBytes(Path.of(original.audioPath)), bytes.toByteArray())
            val target = MessageAlternativeVersionPolicy.normalize(f.message).copy(id = "restored-message", sessionId = "restored-session")
            val staged = handoff.stage(plan.voices, target.sessionId, mapOf(original.messageId to target)) { bytes.toByteArray().inputStream() }
            assertNotEquals(original.id, staged.voices.single().id)
            assertEquals(target.id, staged.voices.single().messageId)
            f.repo.restoreForSession(target.sessionId, staged.voices, listOf(target))
            val restored = f.repo.listForSession(target.sessionId).single()
            assertNotNull(restored.anchorId); assertEquals(original.messageVersionId, restored.messageVersionId)
            staged.rollbackUnreferenced() // Must preserve a committed resource, even after uncertain completion.
            assertTrue(Files.exists(Path.of(restored.audioPath))); assertTrue(Files.exists(Path.of(original.audioPath)))
        }
    }

    @Test fun `precommit partial import failure rolls back only its known unreferenced audio`() = runBlocking {
        DesktopChatVoiceRuntimeTest.Fixture().use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val handoff = DesktopVoiceArchiveHandoff(f.audio, f.repo)
            val old = f.repo.listForSession(f.session.id).single()
            val target = MessageAlternativeVersionPolicy.normalize(f.message).copy(id = "restored", sessionId = "target")
            val voice = old.copy(audioPath = DesktopVoiceArchiveHandoff.PREFIX + "one")
            assertFails { handoff.stage(listOf(voice, voice.copy(id = "two", audioPath = DesktopVoiceArchiveHandoff.PREFIX + "two")), "target",
                mapOf(old.messageId to target)) { resource -> if (resource == "two") error("synthetic late failure") else fishPreviewMp3().inputStream() } }
            assertTrue(Files.exists(Path.of(old.audioPath)))
            Files.walk(f.root.resolve("audio/generated/target")).use { assertEquals(0, it.filter { Files.isRegularFile(it) }.count()) }
            assertFails { handoff.stage(listOf(voice.copy(audioPath = DesktopVoiceArchiveHandoff.PREFIX + "../bad")), "target",
                mapOf(old.messageId to target)) { error("must not open") } }
            assertEquals(0, handoff.planExport(listOf(old.copy(audioPath = "missing")), false).sources.size)
        }
    }

    @Test fun `legacy one through seven use inline media only and never follow stored paths`() = runBlocking {
        DesktopChatVoiceRuntimeTest.Fixture().use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val original = f.repo.listForSession(f.session.id).single()
            val target = MessageAlternativeVersionPolicy.normalize(f.message).copy(id = "new", sessionId = "new-session")
            val handoff = DesktopVoiceArchiveHandoff(f.audio, f.repo)
            for (schema in 1..7) {
                val slot = SaveSlot(schemaVersion = schema, id = "slot", sessionId = f.session.id, name = "synthetic", createdAt = 1,
                    voiceMessages = listOf(original.copy(audioPath = DesktopVoiceArchiveHandoff.PREFIX + "resource", messageVersionId = null)),
                    audioResources = mapOf("resource" to SaveSlotAudioResource("ignored.mp3", Base64.getEncoder().encodeToString(fishPreviewMp3()))))
                val staged = handoff.stageLegacy(slot, target.sessionId, mapOf(original.messageId to target))
                assertEquals(1, staged.voices.size); assertNotNull(staged.voices.single().messageVersionId)
                val path = Path.of(staged.voices.single().audioPath); staged.rollbackUnreferenced(); assertFalse(Files.exists(path))
            }
        }
    }
}
