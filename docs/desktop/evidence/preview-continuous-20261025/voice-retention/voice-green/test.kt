package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.voice.VoiceMessageVersionPolicy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class DesktopHistoricalVoiceRetentionTest {
    private fun message() = ChatMessage("m", "s", MessageRole.ASSISTANT, "Current synthetic text",
        alternatives = listOf("Synthetic alternate", "Current synthetic text"),
        alternativeVersionIds = listOf("previous", "current"), currentAlternativeIndex = 1,
        currentAlternativeVersionId = "current", createdAt = 1, updatedAt = 2)

    private fun voice(version: String?) = GeneratedVoiceMessage(
        "v", "s", "m", version, anchorId = "old-anchor", sourceOrder = 1,
        sourceSegmentKind = "DIALOGUE", sourceSpeakerName = "Synthetic", sourceText = "Old synthetic text",
        taggedText = "Old synthetic text", characterId = "c", characterName = "Synthetic",
        voice = FishAudioVoiceBinding("fake-ref", "Synthetic"), fishModelId = "synthetic",
        audioPath = "synthetic-preserved-audio.mp3", durationMs = 1, byteLength = 3,
        createdAt = 1, updatedAt = 1)

    @Test fun strictDesktopRestorePreservesUnknownExplicitVersionAndNeverShowsItAsCurrent() = runBlocking<Unit> {
        val root = Files.createTempDirectory("desktop-historical-voice-")
        val original = voice("historic-pruned")
        val repository = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
        repository.restoreForSession("s", listOf(original), listOf(message()))
        val restored = repository.listForSession("s").single()
        assertEquals("historic-pruned", restored.messageVersionId)
        assertEquals(original.audioPath, restored.audioPath)
        assertEquals(original.sourceText, restored.sourceText)
        assertEquals(original.anchorId, restored.anchorId)
        assertTrue(VoiceMessageVersionPolicy.visibleVoices(message(), listOf(restored)).isEmpty())
        assertEquals("historic-pruned", VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
            .listForSession("s").single().messageVersionId)
    }

    @Test fun explicitHistoricalIdentityWinsEvenWhenTextMatchesCurrentVersion() = runBlocking<Unit> {
        val repository = VoiceMessageRepository(JsonFileStorage(Files.createTempDirectory("desktop-historical-match-")), strictReads = true)
        repository.restoreForSession("s", listOf(voice("historic").copy(sourceText = message().content)), listOf(message()))
        val restored = repository.listForSession("s").single()
        assertEquals("historic", restored.messageVersionId)
        assertTrue(VoiceMessageVersionPolicy.visibleVoices(message(), listOf(restored)).isEmpty())
    }

    @Test fun knownIdentityAndNullableLegacyKeepTheirExistingSemantics() = runBlocking<Unit> {
        for (version in listOf("previous", null)) {
            val repository = VoiceMessageRepository(JsonFileStorage(Files.createTempDirectory("desktop-known-voice-")), strictReads = true)
            repository.restoreForSession("s", listOf(voice(version)), listOf(message()))
            assertEquals(version ?: MessageAlternativeVersionPolicy.activeVersionId(message()),
                repository.listForSession("s").single().messageVersionId)
        }
    }

    @Test fun nonStrictAndroidPolicyIsUnchangedUntilUpstreamFixesItsOwnRestore() = runBlocking<Unit> {
        val repository = VoiceMessageRepository(JsonFileStorage(Files.createTempDirectory("android-policy-voice-")))
        repository.restoreForSession("s", listOf(voice("historic")), listOf(message()))
        assertEquals("current", repository.listForSession("s").single().messageVersionId)
    }
}
