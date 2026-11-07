package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopVoiceRepositoryAdmissionTest {
    private class PausedGate(val owner: DesktopDataOperationCoordinator) : AppDataOperationGate {
        val armed = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun <T> withNormalOperation(operation: suspend () -> T): T = owner.withNormalOperation {
            if (currentCoroutineContext()[CoroutineName]?.name == "admitted-playback" && armed.compareAndSet(true, false)) {
                entered.complete(Unit); release.await()
            }
            operation()
        }
    }

    @Test fun `actual placement reader for a new reply cannot block admitted old recording playback during maintenance`(): Unit = runBlocking {
        val owner = DesktopDataOperationCoordinator()
        val gate = PausedGate(owner)
        val f = DesktopChatVoiceRuntimeTest.Fixture(gate)
        val jobs = mutableListOf<Job>()
        val exclusiveEntered = CompletableDeferred<Unit>()
        val releaseExclusive = CompletableDeferred<Unit>()
        try {
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val recording = f.repo.listForSession(f.session.id).single()
            val audioBytes = Files.readAllBytes(Path.of(recording.audioPath))
            val next = f.chats.addMessage(ChatMessage("new-reply", f.session.id, MessageRole.ASSISTANT,
                "<n=\"Alice\"/>[later reply]()", createdAt = 2, updatedAt = 2))
            assertTrue(f.repo.snapshotAnchorsForMessages(setOf(next.id)).isEmpty())
            f.runtime.foreground(f.session.id).join()
            val requests = f.server.requestCount
            gate.armed.set(true)
            val old = async(CoroutineName("admitted-playback"), start = CoroutineStart.UNDISPATCHED) {
                f.runtime.play(recording, f.message)
            }.also(jobs::add)
            gate.entered.await()
            launch(start = CoroutineStart.UNDISPATCHED) {
                owner.withExclusiveMaintenance { exclusiveEntered.complete(Unit); releaseExclusive.await() }
            }.also(jobs::add)
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, owner.state)
            // Exact method used by rememberDesktopVoicePlacements, with no outer data lease.
            val late = async(start = CoroutineStart.UNDISPATCHED) { f.repo.placementsForMessage(next) }.also(jobs::add)
            assertFalse(late.isCompleted)
            gate.release.complete(Unit)
            val playback = withTimeout(5000) { assertNotNull(old.await()) }
            withTimeout(5000) { exclusiveEntered.await() }
            assertFalse(late.isCompleted)
            assertContentEquals(audioBytes, Files.readAllBytes(Path.of(recording.audioPath)))
            releaseExclusive.complete(Unit)
            assertTrue(withTimeout(5000) { late.await() }.isEmpty())
            f.runtime.stopForMaintenance(); withTimeout(5000) { playback.join(); jobs.joinAll() }
            val anchor = f.repo.snapshotAnchorsForMessages(setOf(next.id)).single()
            assertEquals(next.id, anchor.messageId)
            assertEquals(MessageAlternativeVersionPolicy.activeVersionId(next), anchor.messageVersionId)
            assertEquals(next, f.chats.getMessage(next.id, f.session.id))
            assertEquals(recording, f.repo.get(recording.id))
            assertContentEquals(audioBytes, Files.readAllBytes(Path.of(recording.audioPath)))
            assertEquals(requests, f.server.requestCount)
        } finally {
            withContext(NonCancellable) {
                gate.release.complete(Unit); releaseExclusive.complete(Unit)
                jobs.forEach { it.cancelAndJoin() }
                f.close(); owner.closeAndDrain()
                check(f.root.fileName.toString().startsWith("s5-chat-"))
                check(f.root.toFile().deleteRecursively())
            }
        }
    }
}
