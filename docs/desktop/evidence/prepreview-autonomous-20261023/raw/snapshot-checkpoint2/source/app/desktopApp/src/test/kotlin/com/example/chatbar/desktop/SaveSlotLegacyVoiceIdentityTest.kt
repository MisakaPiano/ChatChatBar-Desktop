package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class SaveSlotLegacyVoiceIdentityTest {
    private suspend fun inspect(message: ChatMessage, version: String): DesktopSaveSlotInspection {
        val dir = Files.createTempDirectory("slot-legacy-version-")
        val path = dir.resolve("synthetic.cbsave")
        try {
            val voice = GeneratedVoiceMessage("voice", "s", "m", version, sourceOrder = 1,
                sourceSegmentKind = "DIALOGUE", sourceSpeakerName = "Synthetic", sourceText = "Synthetic",
                taggedText = "Synthetic", characterId = "c", characterName = "Synthetic",
                voice = FishAudioVoiceBinding("ref", "Synthetic"), fishModelId = "synthetic",
                audioPath = "chatbar-save-slot-audio:audio", durationMs = 1, byteLength = 3,
                createdAt = 1, updatedAt = 1)
            SyntheticSaveSlotCorpus.archive(path, listOf(message), voices = listOf(voice),
                media = mapOf("media/audio/audio.mp3" to byteArrayOf(1, 2, 3)))
            val before = DesktopArchiveFiles.hash(path, 1024 * 1024)
            return DesktopSaveSlotInspector().inspect(path).also {
                assertFalse(it.imported); assertFalse(it.desktopCanRestore)
                assertEquals(before, DesktopArchiveFiles.hash(path, 1024 * 1024))
            }
        } finally { Files.deleteIfExists(path); Files.delete(dir) }
    }
    @Test fun `official legacy singleton version is source message ID`() = runBlocking<Unit> {
        val message = SyntheticSaveSlotCorpus.message().copy(currentAlternativeVersionId = null)
        assertEquals(SlotInspectStatus.STRUCTURE_VALID, inspect(message, "m").status)
    }
    @Test fun `official legacy alternative ID is admitted without changing source bytes`() = runBlocking<Unit> {
        val message = SyntheticSaveSlotCorpus.message(text = "first").copy(currentAlternativeVersionId = null,
            alternatives = listOf("first", "second"), alternativeVersionIds = emptyList(), currentAlternativeIndex = 0)
        // Fixed official 1.4.5 MessageAlternativeVersionPolicy wire identity, independent JDK oracle.
        val version = UUID.nameUUIDFromBytes("chatbar-message-version:m:0".toByteArray(Charsets.UTF_8)).toString()
        assertEquals(SlotInspectStatus.STRUCTURE_VALID, inspect(message, version).status)
    }
    @Test fun `unknown explicit voice version is never rebound to the current version`() = runBlocking<Unit> {
        val message = SyntheticSaveSlotCorpus.message().copy(currentAlternativeVersionId = null)
        assertEquals(SlotInspectCode.REFERENCE, inspect(message, "not-a-known-version").code)
    }
}
