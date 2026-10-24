package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.desktop.SyntheticSaveSlotCorpus as Corpus
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import com.example.chatbar.domain.voice.VoiceMessageVersionPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

/** Reproduction of an undesirable existing behavior, NOT an acceptance of that behavior. */
class HistoricalVoiceUpstreamReproductionTest {
    private fun root(): Path = Files.createTempDirectory(
        Files.createDirectories(Path.of("build/private-entity-voice-fix/synthetic")), "voice-")
    private fun retainedMessage(): ChatMessage {
        var message = Corpus.message(text = "OLD_ONLY_SOURCE").copy(currentAlternativeVersionId = "old")
        repeat(5) { index -> message = MessageAlternativeVersionPolicy.append(message,
            "Synthetic new reply ${index + 1}", "new-${index + 1}", updatedAt = 10L + index) }
        return message
    }
    private fun voice(version: String? = "old") = GeneratedVoiceMessage(
        "synthetic-voice", "s", "m", version, sourceOrder = 1,
        sourceSegmentKind = "DIALOGUE", sourceSpeakerName = "Synthetic",
        sourceText = "OLD_ONLY_SOURCE", taggedText = "OLD_ONLY_SOURCE",
        characterId = "synthetic", characterName = "Synthetic",
        voice = FishAudioVoiceBinding("synthetic", "Synthetic"), fishModelId = "synthetic",
        audioPath = "chatbar-save-slot-audio:a", durationMs = 1, byteLength = 3,
        createdAt = 1, updatedAt = 1)

    @Test fun defaultFiveVersionAppendCanLeaveExplicitVoiceIdentityOutsideRetainedVersions() {
        val message = retainedMessage()
        assertEquals(5, message.alternatives.size)
        assertEquals((1..5).map { "new-$it" }, message.alternativeVersionIds)
        assertFalse(message.alternativeVersionIds.contains(voice().messageVersionId))
        assertEquals("old", voice().messageVersionId)
    }

    @Test fun observedSharedPackageReaderAcceptsMediaWhileStrictDiagnosisDetectsHistoricVersion() = runBlocking<Unit> {
        val dir = root()
        val file = Corpus.archive(dir.resolve("synthetic.cbsave"), messages = listOf(retainedMessage()),
            voices = listOf(voice()), media = mapOf("media/audio/a.mp3" to byteArrayOf(1, 2, 3)))
        val before = Files.readAllBytes(file)
        SaveSlotPackageStorage(dir.resolve("reader").toFile(), { _, _ -> error("NO_IMAGE") })
            .importPackage(Files.newInputStream(file), "target") { it }
        assertEquals(1, NestedSlotClaims.analyze(Files.newInputStream(file), {}, true)
            ["NONNULL_VERSION_NOT_IN_OFFICIAL_VERSIONS"])
        assertFailsWith<NestedSlotClaims.Refusal> { NestedSlotClaims.validate(Files.newInputStream(file)) {} }
        assertContentEquals(before, Files.readAllBytes(file))
    }

    @Test fun observedSharedRestoreRebindsExplicitMissingVersionAndMakesUnrelatedAudioVisible() = runBlocking<Unit> {
        val dir = root(); val message = retainedMessage()
        val repository = VoiceMessageRepository(JsonFileStorage(dir))
        repository.restoreForSession("s", listOf(voice()), listOf(message))
        val restored = repository.listForSession("s").single()
        assertEquals("old", voice().messageVersionId) // Original input is not mutated.
        assertEquals("new-5", restored.messageVersionId) // Undesirable observed rewrite.
        assertEquals("OLD_ONLY_SOURCE", restored.sourceText)
        assertTrue(message.alternatives.none { it.contains(restored.sourceText) })
        assertEquals(listOf(restored), VoiceMessageVersionPolicy.visibleVoices(message, listOf(restored)))
        assertEquals("new-5", VoiceMessageRepository(JsonFileStorage(dir)).listForSession("s").single().messageVersionId)
    }

    @Test fun knownExplicitVersionIsNotRewritten() = runBlocking<Unit> {
        val repository = VoiceMessageRepository(JsonFileStorage(root()))
        repository.restoreForSession("s", listOf(voice("new-2")), listOf(retainedMessage()))
        assertEquals("new-2", repository.listForSession("s").single().messageVersionId)
    }

    @Test fun missingLegacyIdentityRemainsADifferentCase() = runBlocking<Unit> {
        val repository = VoiceMessageRepository(JsonFileStorage(root()))
        repository.restoreForSession("s", listOf(voice(null)), listOf(retainedMessage()))
        assertEquals("new-5", repository.listForSession("s").single().messageVersionId)
        assertNull(voice(null).messageVersionId)
    }
}
