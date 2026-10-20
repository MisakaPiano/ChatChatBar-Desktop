package com.example.chatbar.domain.memory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionScopedJobRegistryTest {
    @Test
    fun `restore reservation remains sealed during cancellable work then reopens`() = runTest {
        val registry = SessionScopedJobRegistry(this)
        val drained = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        registry.launch("s") { try { awaitCancellation() } finally { drained.complete(Unit) } }
        runCurrent()
        val restore = async {
            registry.whileStopped("s", { assertTrue(drained.isCompleted) }) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        runCurrent(); entered.await()
        assertNull(registry.launch("s") { error("late writer") })
        restore.cancel(); restore.join()
        assertTrue(registry.launch("s") {} != null)
        runCurrent()
    }

    @Test
    fun `reservation failure reopens but recovery failure remains sealed`() = runTest {
        val registry = SessionScopedJobRegistry(this)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            registry.whileStopped("s", {}) { throw IllegalArgumentException("precommit") }
        }
        assertTrue(registry.launch("s") {} != null)
        runCurrent()
        kotlin.test.assertFailsWith<IllegalStateException> {
            registry.whileStopped("s", { error("uncertain recovery") }) { error("must not run") }
        }
        assertNull(registry.launch("s") {})
    }
    @Test
    fun `transient stop blocks admission through recovery and permits retry only after success`() = runTest {
        val registry = SessionScopedJobRegistry(this)
        val recovering = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopping = async {
            registry.cancelAndJoin("session", permanent = false) {
                recovering.complete(Unit)
                release.await()
            }
        }
        runCurrent(); recovering.await()
        assertNull(registry.launch("session") { error("late work") })
        release.complete(Unit); stopping.await()
        assertTrue(registry.launch("session") {} != null)
        runCurrent()
        kotlin.test.assertFailsWith<IllegalStateException> {
            registry.cancelAndJoin("session", permanent = false) { error("recovery uncertain") }
        }
        assertNull(registry.launch("session") { error("unsafe retry") })
        registry.cancelAndJoin("session", permanent = false)
        assertTrue(registry.launch("session") {} != null)
        runCurrent()
    }
    @Test
    fun `session deletion cancels and joins active work then rejects new work`() = runTest {
        val registry = SessionScopedJobRegistry(this)
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        val job = registry.launch("deleted-session") {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                finished.complete(Unit)
            }
        }
        runCurrent()
        started.await()

        registry.cancelAndJoin("deleted-session")

        assertTrue(job?.isCancelled == true)
        assertTrue(finished.isCompleted)
        assertNull(registry.launch("deleted-session") {})
    }

    @Test
    fun `session deletion leaves other session work running`() = runTest {
        val registry = SessionScopedJobRegistry(this)
        val otherStarted = CompletableDeferred<Unit>()
        val otherJob = registry.launch("other-session") {
            otherStarted.complete(Unit)
            awaitCancellation()
        }
        runCurrent()
        otherStarted.await()

        registry.cancelAndJoin("deleted-session")

        assertTrue(otherJob?.isActive == true)
        otherJob?.cancel()
        otherJob?.join()
    }
}
