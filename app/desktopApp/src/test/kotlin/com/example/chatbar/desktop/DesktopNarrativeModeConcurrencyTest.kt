package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.repository.ChatRepository
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopNarrativeModeConcurrencyTest {
    @Test fun `new setter waits outside settings mutex while admitted old writer drains before maintenance`() = runBlocking {
        val root = Files.createTempDirectory("narrative-gate-")
        val coordinator = DesktopDataOperationCoordinator()
        val releaseOld = CompletableDeferred<Unit>()
        val exclusiveEntered = CompletableDeferred<Unit>()
        val releaseExclusive = CompletableDeferred<Unit>()
        val oldDone = CompletableDeferred<Unit>()
        val jobs = mutableListOf<Job>()
        try {
            val repository = ChatRepository(JsonFileStorage(root, coordinator))
            val baseline = repository.createSession(ChatSession("s", "card", "original", createdAt = 1, updatedAt = 1))
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withNormalOperation {
                    releaseOld.await()
                    repository.updateSession(baseline.copy(title = "old writer completed"))
                    oldDone.complete(Unit)
                }
            }
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withExclusiveMaintenance {
                    exclusiveEntered.complete(Unit)
                    releaseExclusive.await()
                }
            }
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, coordinator.state)
            val setter = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.setNarrativeMode("s", ChatNarrativeMode.ADVANCE)
            }.also { jobs += it }
            releaseOld.complete(Unit)
            withTimeout(5000) { oldDone.await(); exclusiveEntered.await() }
            assertFalse(setter.isCompleted)
            // Only inspect this known synthetic file; a normal repository read here would
            // correctly wait behind exclusive maintenance and manufacture a test deadlock.
            val whileExclusive = Json.decodeFromString(ChatSession.serializer(),
                Files.readString(root.resolve("entities/chat_sessions/s.json")))
            assertEquals("old writer completed", whileExclusive.title)
            assertEquals(ChatNarrativeMode.FOCUS, whileExclusive.narrativeMode)
            releaseExclusive.complete(Unit)
            withTimeout(5000) { jobs.joinAll() }
            val saved = repository.getSession("s")!!
            assertEquals("old writer completed", saved.title)
            assertEquals(ChatNarrativeMode.ADVANCE, saved.narrativeMode)
        } finally {
            releaseOld.complete(Unit); releaseExclusive.complete(Unit)
            jobs.forEach { it.cancelAndJoin() }
            coordinator.closeAndDrain()
            check(root.fileName.toString().startsWith("narrative-gate-"))
            check(root.toFile().deleteRecursively())
        }
    }
}
