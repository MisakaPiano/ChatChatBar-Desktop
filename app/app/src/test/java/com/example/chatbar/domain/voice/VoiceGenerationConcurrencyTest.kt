package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.backup.LocalDataMaintenance
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.image.GlobalImageGenerationConcurrencyGate
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceGenerationConcurrencyTest {
    @get:Rule val temp = TemporaryFolder()
    private suspend fun fixture() = VoiceGenerationTestFixture(temp.newFolder()).also { it.initialize() }

    @Test fun queuedCancellationAndTerminalFailureKeepTheirOwnStatusAndCompletedResults() {
        val queued = VoiceGenerationBatchState("q", "session", "old", VoiceGenerationPhase.QUEUED, completedCount = 1)
        val cancelled = queued.cancelled()
        assertEquals(VoiceGenerationPhase.CANCELLED, cancelled.phase)
        assertEquals(1, cancelled.completedCount)
        assertEquals(cancelled, cancelled.failed(IllegalStateException("late transport error")))
        val failed = queued.failed(IllegalStateException("original failure"))
        assertEquals(failed, failed.cancelled())
        assertEquals("original failure", failed.errors["batch"])
    }

    @Test fun completedVoiceReplyAndImageActuallyOverlapAndReplyCancelIsIsolated() = runTest {
        val f = fixture()
        val replyEntered = CompletableDeferred<Unit>(); val imageEntered = CompletableDeferred<Unit>(); val voiceEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reply = backgroundScope.launch { LocalDataMaintenance.enter().use { replyEntered.complete(Unit); awaitCancellation() } }
        val image = backgroundScope.launch {
            GlobalImageGenerationConcurrencyGate.instance.run {
                LocalDataMaintenance.enter().use {
                    imageEntered.complete(Unit); release.await()
                    f.chats.addMessageAfter(ChatMessage(id = "image", sessionId = "session", role = MessageRole.ASSISTANT,
                        content = "", images = listOf("synthetic-owned-image"), createdAt = 2, updatedAt = 2), "old", requireLiveAnchor = true)
                }
            }
        }
        val voice = f.voice()
        val jobs = VoiceBatchJobRegistry(backgroundScope)
        val tts = jobs.launch(VoiceBatchJobRegistry.Target("tts", "session", "old")) {
            LocalDataMaintenance.enter().use { voiceEntered.complete(Unit); release.await(); f.committer.commit(f.snapshot, voice) }
        }
        replyEntered.await(); imageEntered.await(); voiceEntered.await()
        assertTrue(reply.isActive && image.isActive && tts.isActive)
        reply.cancelAndJoin()
        assertTrue(image.isActive && tts.isActive)
        release.complete(Unit); image.join(); tts.join()
        assertEquals(voice.id, f.voices.listForMessage("old").single().id)
        assertEquals("old", f.chats.getMessage("image", "session")?.generatedFromMessageId)
        assertTrue(File(voice.audioPath).isFile)
    }

    @Test fun cancellingOneVoiceBatchLeavesOtherVoiceAndReplyRunning() = runTest {
        val jobs = VoiceBatchJobRegistry(backgroundScope)
        val enteredA = CompletableDeferred<Unit>(); val enteredB = CompletableDeferred<Unit>()
        val reply = backgroundScope.launch { awaitCancellation() }
        val a = jobs.launch(VoiceBatchJobRegistry.Target("a", "session", "old")) { enteredA.complete(Unit); awaitCancellation() }
        val b = jobs.launch(VoiceBatchJobRegistry.Target("b", "other", "other")) { enteredB.complete(Unit); awaitCancellation() }
        enteredA.await(); enteredB.await()
        jobs.cancel("a"); a.join()
        assertTrue(a.isCancelled); assertTrue(b.isActive); assertTrue(reply.isActive)
        jobs.cancelAndJoinMatching { it.sessionId == "other" }
        assertTrue(b.isCancelled); assertTrue(reply.isActive)
    }

    @Test fun queuedJobsAreRegisteredBeforeTheyCanRunOrBeDrained() = runTest {
        val jobs = VoiceBatchJobRegistry(backgroundScope)
        var entered = false
        val a = jobs.launch(VoiceBatchJobRegistry.Target("queued", "session", "old")) { entered = true }
        jobs.cancelAndJoinMatching { it.sessionId == "session" && it.messageId == "old" }
        assertTrue(a.isCancelled); assertFalse(entered)
        // Synchronous completion cannot leave an old registry entry behind.
        val immediate = VoiceBatchJobRegistry(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        immediate.launch(VoiceBatchJobRegistry.Target("same", "session", "old")) { }.join()
        immediate.launch(VoiceBatchJobRegistry.Target("same", "session", "old")) { }.join()
    }

    @Test fun confirmationHandoffWaitsForItsPredecessorAndBothJobsRemainCancellable() = runTest {
        val jobs = VoiceBatchJobRegistry(backgroundScope)
        val entered = CompletableDeferred<Unit>(); var synthesisEntered = false
        val first = jobs.launch(VoiceBatchJobRegistry.Target("same", "session", "old")) {
            entered.complete(Unit); awaitCancellation()
        }
        entered.await()
        val resumed = jobs.launch(VoiceBatchJobRegistry.Target("same", "session", "old")) { synthesisEntered = true }
        runCurrent(); assertFalse(synthesisEntered)
        jobs.cancelAndJoinMatching { it.sessionId == "session" }
        assertTrue(first.isCancelled); assertTrue(resumed.isCancelled); assertFalse(synthesisEntered)
    }

    @Test fun wholeMessageWithoutAnchorRejectsAnEditDuringSynthesis() = runTest {
        val f = fixture(); val voice = f.voice(); val source = f.snapshot
        f.chats.updateMessage(MessageAlternativeVersionPolicy.editCurrentContent(f.message, "changed"))
        assertTrue(runCatching { f.committer.commit(source, voice) }.exceptionOrNull() is IllegalStateException)
        assertTrue(f.voices.listForMessage("old").isEmpty()); assertFalse(File(voice.audioPath).exists())
    }

    @Test fun deletedMessageAndSessionCannotBeResurrectedByLateVoice() = runTest {
        for (deleteSession in listOf(false, true)) {
            val f = fixture(); val voice = f.voice()
            if (deleteSession) f.chats.deleteSessionRecord("session") else f.chats.deleteMessage("old", "session")
            assertTrue(runCatching { f.committer.commit(f.snapshot, voice) }.isFailure)
            assertTrue(f.voices.listForMessage("old").isEmpty()); assertFalse(File(voice.audioPath).exists())
        }
    }

    @Test fun switchedButRetainedVersionKeepsItsOwnVoiceAndRetiredVersionRejects() = runTest {
        val f = fixture(); val source = f.snapshot
        val switched = MessageAlternativeVersionPolicy.append(f.message, "second version", "version2")
        f.chats.updateMessage(switched)
        assertFalse(source.matches(switched, requireVisible = true))
        val voice = f.voice(); f.committer.commit(source, voice)
        assertTrue(f.voices.listForCurrentVersion(switched).isEmpty())
        assertEquals(voice.id, f.voices.listForCurrentVersion(MessageAlternativeVersionPolicy.select(switched, 0)).single().id)
        val retired = MessageAlternativeVersionPolicy.append(switched, "third", "version3", maxVersions = 1)
        f.chats.updateMessage(retired)
        val late = f.voice()
        assertTrue(runCatching { f.committer.commit(source, late) }.isFailure)
        assertFalse(File(late.audioPath).exists()); assertTrue(File(voice.audioPath).exists())
    }

    @Test fun segmentAnchorMustStillBelongToTheSnapshottedVersion() = runTest {
        val f = fixture(); val anchor = f.voices.ensureAnchors(f.message).single().anchor.id
        val voice = f.voice(anchor = anchor)
        f.committer.commit(f.snapshot, voice)
        val wrongAnchor = f.voice(anchor = "not-an-anchor")
        assertTrue(runCatching { f.committer.commit(f.snapshot, wrongAnchor) }.isFailure)
        assertEquals(listOf(voice.id), f.voices.listForMessage("old").map { it.id })
        assertFalse(File(wrongAnchor.audioPath).exists())
    }

    @Test fun deletedOrConcurrentlyReplacedVoiceRejectsRegeneration() = runTest {
        for (delete in listOf(false, true)) {
            val f = fixture(); val old = f.voice(); f.committer.commit(f.snapshot, old)
            val incoming = old.copy(audioPath = f.audio().absolutePath, updatedAt = 2)
            if (delete) f.voices.delete(old.id) else f.voices.replace(old.copy(taggedText = "other confirmed text", updatedAt = 3))
            assertTrue(runCatching { f.committer.commit(f.snapshot, incoming, old) }.isFailure)
            assertFalse(File(incoming.audioPath).exists()); assertTrue(File(old.audioPath).exists())
            assertEquals(if (delete) null else "other confirmed text", f.voices.get(old.id)?.taggedText)
        }
    }

    @Test fun finalCommitAndSourceMutationShareOneShortAtomicBoundary() = runTest {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val committer = VoiceGenerationCommitter(f.chats, f.voices, deleteUncommittedAudio = f::deleteAudio,
            save = { v, previous, active -> entered.complete(Unit); release.await(); f.voices.saveIfUnchanged(v, previous, active) })
        val voice = f.voice()
        val commit = async { committer.commit(f.snapshot, voice) }
        entered.await()
        val edit = launch { f.chats.updateMessage(MessageAlternativeVersionPolicy.editCurrentContent(f.message, "edited later")) }
        try { runCurrent(); assertFalse(edit.isCompleted) } finally { release.complete(Unit) }
        assertEquals(voice.id, commit.await().id); edit.join()
        assertEquals("edited later", f.chats.getMessage("old", "session")?.content)
        assertTrue(File(voice.audioPath).exists())
    }

    @Test fun imageResultWithDeletedAnchorDoesNotAppendAtTheEndOfAnotherTurn() = runTest {
        val f = fixture(); f.chats.deleteMessage("old", "session")
        val image = ChatMessage(id = "late-image", sessionId = "session", role = MessageRole.ASSISTANT,
            content = "", images = listOf("synthetic-image"), createdAt = 3, updatedAt = 3)
        assertTrue(runCatching { f.chats.addMessageAfter(image, "old", requireLiveAnchor = true) }.isFailure)
        assertNull(f.chats.getMessage("late-image", "session"))
    }

    @Test fun cancellationBeforeTheWritePointDoesNotCommitOrCancelOtherWork() = runTest {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val committer = VoiceGenerationCommitter(f.chats, f.voices, deleteUncommittedAudio = f::deleteAudio,
            save = { v, previous, active -> entered.complete(Unit); release.await(); f.voices.saveIfUnchanged(v, previous, active) })
        val voice = f.voice()
        val unrelated = backgroundScope.launch { awaitCancellation() }
        val task = launch { committer.commit(f.snapshot, voice) }
        entered.await(); task.cancel(); release.complete(Unit); task.join()
        assertTrue(task.isCancelled); assertTrue(unrelated.isActive)
        assertTrue(f.voices.listForMessage("old").isEmpty()); assertFalse(File(voice.audioPath).exists())
    }
}
