package com.example.chatbar.domain.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TagIndexPreparationTest {
    @Test fun cancellingAQueryDoesNotWaitForOrRestartTheSharedBuild() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val coordinator = TagIndexPreparation<String>()
            val finish = CompletableDeferred<Unit>()
            var builds = 0
            fun build() = appScope.async(start = CoroutineStart.LAZY) {
                builds++
                finish.await()
                "ready"
            }
            val oldQuery = async { coordinator.await("version", ::build) }
            runCurrent()
            oldQuery.cancelAndJoin()
            assertFalse(finish.isCompleted)
            val newQuery = async { coordinator.await("version", ::build) }
            runCurrent()
            assertEquals(1, builds)
            finish.complete(Unit)
            assertEquals("ready", newQuery.await())
        } finally { appScope.cancel() }
    }

    @Test fun anotherSourceVersionDoesNotWaitBehindTheOldBuild() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val coordinator = TagIndexPreparation<String>()
            val finishOld = CompletableDeferred<Unit>()
            val oldQuery = async {
                coordinator.await("old") { appScope.async(start = CoroutineStart.LAZY) { finishOld.await(); "old" } }
            }
            runCurrent()
            val next = coordinator.await("new") { appScope.async(start = CoroutineStart.LAZY) { "new" } }
            assertEquals("new", next)
            assertFalse(oldQuery.isCompleted)
            oldQuery.cancelAndJoin()
        } finally { appScope.cancel() }
    }
}
