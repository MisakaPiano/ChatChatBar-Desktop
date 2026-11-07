package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopVoiceMutationAdmissionTest {
    /** Pauses one real runtime entry after normal admission, before its own mutation mutex. */
    private class PausedGate(val owner: DesktopDataOperationCoordinator) : AppDataOperationGate {
        val armed = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun <T> withNormalOperation(operation: suspend () -> T): T = owner.withNormalOperation {
            if (armed.compareAndSet(true, false)) { entered.complete(Unit); release.await() }
            operation()
        }
    }

    @Test fun `actual residency completion cannot hold voice mutation ahead of admitted generation during maintenance`(): Unit = runBlocking {
        cycle(deleteRecording = false)
    }

    @Test fun `actual recording deletion waits outside voice mutation while admitted generation drains`(): Unit = runBlocking {
        cycle(deleteRecording = true)
    }

    private suspend fun cycle(deleteRecording: Boolean) = coroutineScope {
        val owner = DesktopDataOperationCoordinator()
        val gate = PausedGate(owner)
        val f = DesktopChatVoiceRuntimeTest.Fixture(gate)
        val jobs = mutableListOf<Job>()
        val exclusiveEntered = CompletableDeferred<Unit>()
        val releaseExclusive = CompletableDeferred<Unit>()
        try {
            f.seed()
            val card = assertNotNull(f.characters.getById("card"))
            val disabled = SessionCharacterParticipationPolicy.change(f.session, card, "alice", false)
            val recording = if (deleteRecording) {
                f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
                f.repo.listForSession(f.session.id).single()
            } else null
            if (!deleteRecording) f.chats.saveSessionSettingsDraft(f.session, disabled)
            val beforeSession = Files.readAllBytes(f.root.resolve("entities/chat_sessions/${f.session.id}.json"))
            val requests = f.server.requestCount
            val residency = DesktopCharacterResidency(f.chats, f.characters, gate) { _, change ->
                f.runtime.withAutomaticParticipationUpdate(change)
            }
            gate.armed.set(true)
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                // It must still traverse the actual runtime gate/mutex, then safely reject stale UI.
                f.runtime.generate(f.message.copy(content = "stale expected content"), "alice")
            }.also(jobs::add)
            gate.entered.await()
            launch(start = CoroutineStart.UNDISPATCHED) {
                owner.withExclusiveMaintenance { exclusiveEntered.complete(Unit); releaseExclusive.await() }
            }.also(jobs::add)
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, owner.state)
            val late = launch(start = CoroutineStart.UNDISPATCHED) {
                if (recording != null) f.runtime.deleteVoice(recording)
                else residency.completed(DesktopRealChatResult(
                    f.message.copy(id = "synthetic-user", role = MessageRole.USER), false, f.message, null)) { false }
            }.also(jobs::add)
            assertFalse(late.isCompleted)
            gate.release.complete(Unit)
            withTimeout(5000) { assertNull(old.await()); exclusiveEntered.await() }
            assertFalse(late.isCompleted)
            assertContentEquals(beforeSession, Files.readAllBytes(f.root.resolve("entities/chat_sessions/${f.session.id}.json")))
            if (recording != null) assertTrue(Files.exists(java.nio.file.Path.of(recording.audioPath)))
            releaseExclusive.complete(Unit)
            withTimeout(5000) { jobs.joinAll() }
            assertEquals(requests, f.server.requestCount)
            if (recording != null) {
                assertNull(f.repo.get(recording.id))
                assertFalse(Files.exists(java.nio.file.Path.of(recording.audioPath)))
            } else {
                assertTrue(SessionCharacterParticipationPolicy.isEnabled(assertNotNull(f.chats.getSession(f.session.id)), card, "alice"))
                assertTrue(residency.notices.value[f.session.id]?.text?.contains("Alice") == true)
            }
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
