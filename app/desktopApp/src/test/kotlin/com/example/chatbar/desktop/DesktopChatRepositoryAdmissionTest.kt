package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatScrollPosition
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.SettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopChatRepositoryAdmissionTest {
    @Test fun `admitted settings refresh drains before a later settings reader during maintenance`(): Unit = runBlocking {
        fixture { f ->
            val settings = SettingsRepository(f.storage)
            settings.initialize()
            val releaseOld = CompletableDeferred<Unit>()
            val refreshed = CompletableDeferred<Unit>()
            val exclusiveEntered = CompletableDeferred<Unit>()
            val releaseExclusive = CompletableDeferred<Unit>()
            f.keep(launch(start = CoroutineStart.UNDISPATCHED) {
                f.gate.withNormalOperation {
                    releaseOld.await()
                    // The same admitted refresh used after an API-account mutation.
                    settings.initialize(forceReload = true)
                    refreshed.complete(Unit)
                }
            })
            f.keep(launch(start = CoroutineStart.UNDISPATCHED) {
                f.gate.withExclusiveMaintenance { exclusiveEntered.complete(Unit); releaseExclusive.await() }
            })
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, f.gate.state)
            val reader = f.keep(async(start = CoroutineStart.UNDISPATCHED) { settings.getAppSettings() })
            assertFalse(reader.isCompleted)
            releaseOld.complete(Unit)
            withTimeout(5000) { refreshed.await(); exclusiveEntered.await() }
            assertFalse(reader.isCompleted)
            releaseExclusive.complete(Unit)
            assertEquals(settings.currentAppSettings, withTimeout(5000) { reader.await() })
            withTimeout(5000) { f.jobs.joinAll() }
        }
    }

    private class Fixture(val root: Path, val gate: DesktopDataOperationCoordinator,
        val storage: JsonFileStorage, val repository: ChatRepository) {
        val jobs = mutableListOf<Job>()
        fun <T : Job> keep(job: T): T = job.also(jobs::add)
    }

    private suspend fun fixture(block: suspend CoroutineScope.(Fixture) -> Unit) = coroutineScope {
        val root = Files.createTempDirectory("chat-repository-admission-")
        val gate = DesktopDataOperationCoordinator()
        val storage = JsonFileStorage(root, gate)
        val f = Fixture(root, gate, storage, ChatRepository(storage))
        try {
            f.repository.createSession(ChatSession("s", "card", "Synthetic session", createdAt = 1, updatedAt = 1))
            f.repository.addMessage(ChatMessage("seed", "s", MessageRole.USER, "Synthetic input", createdAt = 1, updatedAt = 1))
            block(f)
        } finally {
            withContext(NonCancellable) {
                f.jobs.forEach { it.cancelAndJoin() }
                gate.closeAndDrain()
                check(root.fileName.toString().startsWith("chat-repository-admission-"))
                check(root.toFile().deleteRecursively())
            }
        }
    }

    @Test fun `queued append cannot hold the message mutex while admitted writer drains for maintenance`(): Unit = runBlocking {
        fixture { f ->
            val releaseOld = CompletableDeferred<Unit>()
            val oldDone = CompletableDeferred<Unit>()
            val exclusiveEntered = CompletableDeferred<Unit>()
            val releaseExclusive = CompletableDeferred<Unit>()
            f.keep(launch(start = CoroutineStart.UNDISPATCHED) {
                f.gate.withNormalOperation {
                    releaseOld.await()
                    f.repository.addMessage(ChatMessage("old", "s", MessageRole.ASSISTANT, "Admitted reply", createdAt = 2, updatedAt = 2))
                    oldDone.complete(Unit)
                }
            })
            f.keep(launch(start = CoroutineStart.UNDISPATCHED) {
                f.gate.withExclusiveMaintenance { exclusiveEntered.complete(Unit); releaseExclusive.await() }
            })
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, f.gate.state)
            val late = f.keep(launch(start = CoroutineStart.UNDISPATCHED) {
                f.repository.addMessage(ChatMessage("late", "s", MessageRole.USER, "Later input", createdAt = 3, updatedAt = 3))
            })
            releaseOld.complete(Unit)
            withTimeout(5000) { oldDone.await(); exclusiveEntered.await() }
            assertFalse(late.isCompleted)
            // Read only the known synthetic file; an ordinary repository read here must wait.
            assertEquals("Admitted reply", Json.decodeFromString(ChatMessage.serializer(),
                Files.readString(f.root.resolve("entities/chat_messages/s_old.json"))).content)
            assertFalse(Files.exists(f.root.resolve("entities/chat_messages/s_late.json")))
            releaseExclusive.complete(Unit)
            withTimeout(5000) { f.jobs.joinAll() }
            assertEquals(listOf("seed", "old", "late"), f.repository.getMessageIds("s"))
        }
    }

    @Test fun `exclusive session publication reload can finish ahead of a queued cold index reader`(): Unit = runBlocking {
        fixture { f ->
            val repository = ChatRepository(f.storage) // Its index cache has not been populated.
            val exclusiveEntered = CompletableDeferred<Unit>()
            val reload = CompletableDeferred<Unit>()
            val reloaded = CompletableDeferred<Unit>()
            val releaseExclusive = CompletableDeferred<Unit>()
            f.keep(launch(start = CoroutineStart.UNDISPATCHED) {
                f.gate.withExclusiveMaintenance {
                    exclusiveEntered.complete(Unit); reload.await()
                    repository.reloadAfterSessionCommit("s")
                    reloaded.complete(Unit); releaseExclusive.await()
                }
            })
            exclusiveEntered.await()
            val reader = f.keep(async(start = CoroutineStart.UNDISPATCHED) { repository.getMessageIds("s") })
            assertFalse(reader.isCompleted)
            reload.complete(Unit)
            withTimeout(5000) { reloaded.await() }
            assertFalse(reader.isCompleted)
            releaseExclusive.complete(Unit)
            assertEquals(listOf("seed"), withTimeout(5000) { reader.await() })
            withTimeout(5000) { f.jobs.joinAll() }
        }
    }

    @Test fun `queued viewport save cannot block admitted save or overwrite its newer capture`(): Unit = runBlocking {
        fixture { f ->
            val releaseOld = CompletableDeferred<Unit>()
            val oldDone = CompletableDeferred<Unit>()
            val exclusiveEntered = CompletableDeferred<Unit>()
            val releaseExclusive = CompletableDeferred<Unit>()
            val newer = ChatScrollPosition("s", "seed", scrollOffset = 90, capturedAt = 200)
            f.keep(launch(start = CoroutineStart.UNDISPATCHED) {
                f.gate.withNormalOperation {
                    releaseOld.await(); f.repository.updateScrollPosition(newer); oldDone.complete(Unit)
                }
            })
            f.keep(launch(start = CoroutineStart.UNDISPATCHED) {
                f.gate.withExclusiveMaintenance { exclusiveEntered.complete(Unit); releaseExclusive.await() }
            })
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, f.gate.state)
            val late = f.keep(launch(start = CoroutineStart.UNDISPATCHED) {
                f.repository.updateScrollPosition(newer.copy(scrollOffset = 10, capturedAt = 100))
            })
            releaseOld.complete(Unit)
            withTimeout(5000) { oldDone.await(); exclusiveEntered.await() }
            assertFalse(late.isCompleted)
            assertEquals(newer, Json.decodeFromString(ChatScrollPosition.serializer(),
                Files.readString(f.root.resolve("entities/chat_scroll_positions/s.json"))))
            releaseExclusive.complete(Unit)
            withTimeout(5000) { f.jobs.joinAll() }
            assertEquals(newer, f.repository.getScrollPosition("s"))
        }
    }
}
