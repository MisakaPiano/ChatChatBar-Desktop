package com.example.chatbar.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore

class DesktopProcessLifecycleTest {
    @Test fun `image task cancellation finishes owned writes before coordinator closes`() {
        withRoot { root -> runBlocking {
            val container = DesktopAppContainer(root, secretStoreFactory = { InMemoryDesktopSecretStore() })
            val started = CompletableDeferred<Unit>()
            val draining = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val image = container.taskRuntime.launchNovelAi("shutdown fixture", "session", "message", true) {
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) {
                    draining.complete(Unit)
                    release.await()
                    Files.writeString(root.appDataRoot.resolve("cancelled-write.txt"), "synthetic retained result")
                } }
            }
            try {
                withTimeout(5000) { started.await() }
                val close = async { closeDesktopWithRecovery({ container.close() }, { error("unexpected timeout") }) }
                withTimeout(5000) { draining.await() }
                assertEquals(false, close.isCompleted)
                assertEquals(DesktopDataOperationCoordinatorState.OPEN, container.dataOperationCoordinator.state)
                release.complete(Unit)
                withTimeout(5000) { close.await() }
                assertEquals("synthetic retained result", Files.readString(root.appDataRoot.resolve("cancelled-write.txt")))
                assertEquals(DesktopTaskStatus.CANCELLED, container.taskRuntime.tasks.value.single { it.taskId == image }.status)
                assertEquals(DesktopDataOperationCoordinatorState.CLOSED, container.dataOperationCoordinator.state)
            } finally { release.complete(Unit); container.close() }
        } }
    }

    @Test fun `termination follows container picker and root release exactly once`() {
        val calls = mutableListOf<String>()
        withRoot { root ->
            runDesktopProcessLifecycle(
                application = {
                    runDesktopApplicationWithDataRootOwnership(root,
                        closeOwnership = { calls += "ownership"; it.close() }) {
                        AutoCloseable { calls += "pickers" }.use {
                            runDesktopApplicationLifecycle({}, { calls += "compose" }, { calls += "container" })
                        }
                    }
                },
                terminate = { calls += "exit:$it" },
                reportFailure = { error("unexpected failure") },
            )
            assertEquals(listOf("compose", "container", "pickers", "ownership", "exit:0"), calls)
            assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(root)).ownership.close()
        }
    }

    @Test fun `application failure presents recovery and never calls successful terminator`() {
        val calls = mutableListOf<String>()
        runDesktopProcessLifecycle(
            application = { throw IllegalStateException("private content must not reach presenter") },
            terminate = { calls += "exit" }, reportFailure = { calls += "recovery" },
        )
        assertEquals(listOf("recovery"), calls)
    }

    @Test fun `terminator is outside failure handler and invoked once`() {
        var calls = 0
        val failure = IllegalStateException("terminator fixture")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            runDesktopProcessLifecycle({}, terminate = { calls++; throw failure }, reportFailure = { error("not application failure") })
        })
        assertEquals(1, calls)
    }

    @Test fun `drain timeouts retain ownership during explicit recovery and retry before disposal`() {
        listOf(DesktopTaskDrainTimeoutException("fixture"), DesktopDraftDrainTimeoutException(),
            DesktopReadingPositionDrainTimeoutException()).forEach { timeout ->
            withRoot { root ->
                val calls = mutableListOf<String>()
                var attempts = 0
                runDesktopProcessLifecycle(application = {
                    runDesktopApplicationWithDataRootOwnership(root) {
                        AutoCloseable { calls += "pickers" }.use {
                            runBlocking {
                                closeDesktopWithRecovery(close = {
                                    calls += "drain"
                                    if (attempts++ == 0) throw timeout
                                }, awaitRetry = {
                                    assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(root))
                                    calls += "visible-retry"
                                })
                            }
                        }
                    }
                }, terminate = { calls += "exit" }, reportFailure = { error("unexpected") })
                assertEquals(listOf("drain", "visible-retry", "drain", "pickers", "exit"), calls)
            }
        }
    }

    @Test fun `non timeout close failure holds lock without retry or successful exit`() {
        withRoot { root ->
            lateinit var ownership: DesktopDataRootOwnership
            val calls = mutableListOf<String>()
            try {
                runDesktopProcessLifecycle(application = {
                    runDesktopApplicationWithDataRootOwnership(root, acquireOwnership = {
                        DesktopDataRootOwnership.acquire(it).also { result -> ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(result).ownership }
                    }) {
                        runBlocking { closeDesktopWithRecovery(close = { calls += "close"; error("failed transaction") }, awaitRetry = { error("must not retry") }) }
                    }
                }, terminate = { calls += "exit" }, reportFailure = {
                    assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(root))
                    calls += "visible-error"
                })
                assertEquals(listOf("close", "visible-error"), calls)
            } finally { ownership.close() }
        }
    }

    @Test fun `recovery presentation failure retains unsafe cause`() = runBlocking {
        val timeout = DesktopDraftDrainTimeoutException()
        val presentationFailure = IllegalStateException("dialog unavailable")
        val thrown = assertFailsWith<DesktopUnsafeShutdownException> {
            closeDesktopWithRecovery(close = { throw timeout }, awaitRetry = { throw presentationFailure })
        }
        assertSame(timeout, thrown.cause)
        assertEquals(listOf(presentationFailure), timeout.suppressed.toList())
    }

    private fun withRoot(block: (DesktopDataRootResolution.Resolved) -> Unit) {
        val path = Files.createTempDirectory("ccb-shutdown-policy-")
        try { block(DesktopDataRootResolution.Resolved(path, DesktopDataRootProvenance.CLI_OVERRIDE, path.resolveSibling("unused-bootstrap.json"))) }
        finally { path.toFile().deleteRecursively() }
    }
}
