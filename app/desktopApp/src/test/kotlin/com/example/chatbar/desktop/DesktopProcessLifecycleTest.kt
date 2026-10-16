package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore

class DesktopProcessLifecycleTest {
    private val retainedSyntheticRoots = ConcurrentHashMap.newKeySet<Path>()
    @Test fun `initialization failure keeps ownership through shutdown retry then reports original failure`() {
        withRoot { root ->
            val calls = mutableListOf<String>()
            val original = IllegalStateException("synthetic init")
            var attempts = 0
            runDesktopApplicationWithDataRootOwnership(root) {
                assertSame(original, assertFailsWith<IllegalStateException> {
                    runDesktopApplicationLifecycle({ calls += "initialize"; throw original },
                        { error("no UI") }, {
                            closeDesktopWithRecovery(close = {
                                calls += "close"
                                if (attempts++ == 0) throw DesktopTaskDrainTimeoutException("synthetic pending")
                            }, awaitRetry = {
                                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(root))
                                calls += "retry"
                            }, observeWait = { AutoCloseable {} })
                        })
                })
            }
            assertEquals(listOf("initialize", "close", "retry", "close"), calls)
            assertEquals(2, attempts)
            assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(root)).ownership.close()
        }
    }

    @Test fun `initialization failure waits for real task cleanup before releasing root and never exits successfully`() {
        withRoot { root -> runBlocking {
            val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val containerRef = AtomicReference<DesktopAppContainer?>()
            val started = CompletableDeferred<Unit>()
            val draining = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val initFailure = IllegalStateException("synthetic initialization after writer startup")
            val observed = mutableListOf<String>()
            // A separately owned fixture scope prevents runBlocking's implicit child join from
            // defeating bounded teardown if the synchronous lifecycle itself gets stuck.
            val lifecycle = fixtureScope.async {
                runDesktopProcessLifecycle(application = {
                    runDesktopApplicationWithDataRootOwnership(root) {
                        val container = DesktopAppContainer(root, secretStoreFactory = { InMemoryDesktopSecretStore() })
                        containerRef.set(container) // Production invariant: ownership before container.
                        runDesktopApplicationLifecycle(initialize = {
                            container.taskRuntime.launchNovelAi("synthetic startup", "session", "message", true) {
                                started.complete(Unit)
                                try { awaitCancellation() } finally { withContext(NonCancellable) {
                                    draining.complete(Unit)
                                    release.await()
                                    Files.writeString(root.appDataRoot.resolve("joined-write.txt"), "synthetic completed write")
                                } }
                            }
                            started.await()
                            throw initFailure
                        }, applicationBody = { error("must not start UI") }, close = {
                            closeDesktopWithRecovery({ container.close() }, { error("unexpected drain timeout") },
                                observeWait = { AutoCloseable {} })
                        })
                    }
                }, terminate = { observed += "exit" }, reportFailure = { observed += "failure" })
            }
            var primaryFailure: Throwable? = null
            try {
                withTimeout(10_000) { draining.await() }
                val container = checkNotNull(containerRef.get())
                assertEquals(false, lifecycle.isCompleted)
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(root))
                assertEquals(DesktopDataOperationCoordinatorState.OPEN, container.dataOperationCoordinator.state)
                release.complete(Unit)
                withTimeout(10_000) { lifecycle.await() }
                assertEquals(listOf("failure"), observed)
                assertEquals("synthetic completed write", Files.readString(root.appDataRoot.resolve("joined-write.txt")))
                assertEquals(DesktopDataOperationCoordinatorState.CLOSED, container.dataOperationCoordinator.state)
                assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(root)).ownership.close()
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                finishSyntheticLifecycle(release, lifecycle, fixtureScope, containerRef, primaryFailure, root = root.appDataRoot)
            }
        } }
    }

    @Test fun `image task cancellation finishes owned writes before coordinator closes`() {
        withRoot { root -> runBlocking {
            val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val containerRef = AtomicReference<DesktopAppContainer?>()
            val imageId = CompletableDeferred<String>()
            val started = CompletableDeferred<Unit>()
            val closeRequested = CompletableDeferred<Unit>()
            val draining = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lifecycle = fixtureScope.async {
                runDesktopApplicationWithDataRootOwnership(root) {
                    val container = DesktopAppContainer(root, secretStoreFactory = { InMemoryDesktopSecretStore() })
                    containerRef.set(container)
                    runDesktopApplicationLifecycle(initialize = {
                        imageId.complete(container.taskRuntime.launchNovelAi("shutdown fixture", "session", "message", true) {
                            started.complete(Unit)
                            try { awaitCancellation() } finally { withContext(NonCancellable) {
                                draining.complete(Unit)
                                release.await()
                                Files.writeString(root.appDataRoot.resolve("cancelled-write.txt"), "synthetic retained result")
                            } }
                        })
                    }, applicationBody = { runBlocking { closeRequested.await() } }, close = {
                        closeDesktopWithRecovery({ container.close() }, { error("unexpected timeout") },
                            observeWait = { AutoCloseable {} })
                    })
                }
            }
            var primaryFailure: Throwable? = null
            try {
                withTimeout(5000) { started.await() }
                val container = checkNotNull(containerRef.get())
                val image = withTimeout(5000) { imageId.await() }
                closeRequested.complete(Unit)
                withTimeout(5000) { draining.await() }
                assertEquals(false, lifecycle.isCompleted)
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(root))
                assertEquals(DesktopDataOperationCoordinatorState.OPEN, container.dataOperationCoordinator.state)
                release.complete(Unit)
                withTimeout(5000) { lifecycle.await() }
                assertEquals("synthetic retained result", Files.readString(root.appDataRoot.resolve("cancelled-write.txt")))
                assertEquals(DesktopTaskStatus.CANCELLED, container.taskRuntime.tasks.value.single { it.taskId == image }.status)
                assertEquals(DesktopDataOperationCoordinatorState.CLOSED, container.dataOperationCoordinator.state)
                assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(root)).ownership.close()
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                closeRequested.complete(Unit)
                finishSyntheticLifecycle(release, lifecycle, fixtureScope, containerRef, primaryFailure, root = root.appDataRoot)
            }
        } }
    }

    @Test fun `synthetic teardown timeout reports incomplete lifecycle without claiming drain or cancelling writer`() = runBlocking {
        val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CompletableDeferred<Unit>()
        val actualRelease = CompletableDeferred<Unit>()
        val lifecycle = fixtureScope.async { started.complete(Unit); actualRelease.await(); Unit }
        val original = IllegalStateException("synthetic assertion failure")
        try {
            withTimeout(1000) { started.await() }
            val failure = assertFailsWith<IllegalStateException> {
                finishSyntheticLifecycle(CompletableDeferred(), lifecycle, fixtureScope,
                    AtomicReference<DesktopAppContainer?>(), original, timeoutMillis = 50)
            }
            assertSame(original, failure.cause)
            assertTrue(failure.message.orEmpty().contains("drain NOT CONFIRMED"))
            assertFalse(lifecycle.isCompleted)
            assertFalse(lifecycle.isCancelled)
        } finally {
            // Only the synthetic fixture can signal its actual writer completion, never a timeout.
            actualRelease.complete(Unit)
            withContext(NonCancellable) { withTimeout(1000) { lifecycle.join() } }
            fixtureScope.cancel()
        }
    }

    private suspend fun finishSyntheticLifecycle(
        release: CompletableDeferred<Unit>,
        lifecycle: Deferred<Unit>,
        fixtureScope: CoroutineScope,
        containerRef: AtomicReference<DesktopAppContainer?>,
        primaryFailure: Throwable?,
        timeoutMillis: Long = 15_000,
        root: Path? = null,
    ) {
        release.complete(Unit)
        // The timeout is INSIDE NonCancellable, so even a cancelled test has a bounded join.
        val joined = withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMillis) { lifecycle.join(); true }
        } == true
        val container = containerRef.get()
        val coordinator = container?.dataOperationCoordinator?.state
        if (!joined || (container != null && coordinator != DesktopDataOperationCoordinatorState.CLOSED)) {
            // Even a lifecycle still queued before acquire must not start against a deleted root.
            if (root != null) retainedSyntheticRoots.add(root)
            // Do not call container.close again, cancel an in-flight write, release ownership, or
            // delete the root. The fixture still owns its scope; withRoot retains its locked files.
            throw IllegalStateException("Synthetic teardown drain NOT CONFIRMED: " +
                "budgetMs=$timeoutMillis, lifecycleCompleted=${lifecycle.isCompleted}, " +
                "lifecycleCancelled=${lifecycle.isCancelled}, coordinator=$coordinator; " +
                "fixture scope and any held ownership retained", primaryFailure)
        }
        fixtureScope.cancel() // Lifecycle has joined and the real coordinator is CLOSED (or never constructed).
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

    @Test fun `root fixture rejects unexpected ownership retention after successful body`() {
        var root: DesktopDataRootResolution.Resolved? = null
        var held: DesktopDataRootOwnership? = null
        try {
            val failure = assertFailsWith<IllegalStateException> {
                withRoot {
                    root = it
                    held = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(it)).ownership
                }
            }
            assertTrue(failure.message.orEmpty().contains("ownership release not confirmed"))
            assertTrue(Files.exists(checkNotNull(root).appDataRoot))
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(checkNotNull(root)))
        } finally {
            disposeRetentionRegressionRoot(root, held)
        }
    }

    @Test fun `root fixture preserves body failure identity and ordered cleanup diagnostic`() {
        for (declaredIncomplete in listOf(false, true)) {
            var root: DesktopDataRootResolution.Resolved? = null
            var held: DesktopDataRootOwnership? = null
            val earlier = IllegalArgumentException("synthetic earlier diagnostic")
            val primary = IllegalStateException("synthetic body assertion").apply { addSuppressed(earlier) }
            try {
                val failure = assertFailsWith<IllegalStateException> {
                    withRoot {
                        root = it
                        if (declaredIncomplete) retainedSyntheticRoots.add(it.appDataRoot)
                        else held = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(it)).ownership
                        throw primary
                    }
                }
                assertSame(primary, failure)
                assertSame(earlier, failure.suppressed.first())
                assertEquals(2, failure.suppressed.size)
                val cleanup = assertIs<IllegalStateException>(failure.suppressed.last())
                assertTrue(cleanup.message.orEmpty().contains(
                    if (declaredIncomplete) "drain NOT CONFIRMED" else "ownership release not confirmed"))
                assertTrue(Files.exists(checkNotNull(root).appDataRoot))
            } finally {
                disposeRetentionRegressionRoot(root, held)
            }
        }
    }

    @Test fun `root fixture preserves suppression disabled body failure and cleanup evidence`() {
        var root: DesktopDataRootResolution.Resolved? = null
        var held: DesktopDataRootOwnership? = null
        val primary = SyntheticSuppressionDisabledFailure()
        try {
            val failure = assertFailsWith<SyntheticRootCleanupUnconfirmed> {
                withRoot {
                    root = it
                    held = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(it)).ownership
                    throw primary
                }
            }
            assertSame(primary, failure.primaryFailure)
            assertSame(primary, failure.cause)
            assertTrue(primary.suppressed.isEmpty())
            assertTrue(failure.cleanupFailure.message.orEmpty().contains("ownership release not confirmed"))
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(checkNotNull(root)))
            assertTrue(Files.exists(checkNotNull(root).appDataRoot))
        } finally {
            disposeRetentionRegressionRoot(root, held)
        }
    }

    @Test fun `declared incomplete root cannot become green when body swallows teardown failure`() {
        var root: DesktopDataRootResolution.Resolved? = null
        try {
            val failure = assertFailsWith<IllegalStateException> {
                withRoot {
                    root = it
                    // Simulate finishSyntheticLifecycle's retained-root declaration after a
                    // caller caught its failure. This is NOT permission to call teardown safe.
                    retainedSyntheticRoots.add(it.appDataRoot)
                }
            }
            assertTrue(failure.message.orEmpty().contains("drain NOT CONFIRMED"))
            assertTrue(Files.exists(checkNotNull(root).appDataRoot))
        } finally {
            disposeRetentionRegressionRoot(root)
        }
    }

    @Test fun `root fixture removes synthetic directory after confirmed ordinary completion`() {
        var root: DesktopDataRootResolution.Resolved? = null
        withRoot { root = it }
        assertFalse(Files.exists(checkNotNull(root).appDataRoot))
    }

    /** These regression roots have no tasks or writers; dispose only their explicitly held test lock. */
    private fun disposeRetentionRegressionRoot(
        root: DesktopDataRootResolution.Resolved?,
        held: DesktopDataRootOwnership? = null,
    ) {
        held?.close()
        if (root == null) return
        // Never delete a root that remains owned by anything other than this synthetic fixture.
        val probe = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(root))
        probe.ownership.close()
        retainedSyntheticRoots.remove(root.appDataRoot)
        check(root.appDataRoot.toFile().deleteRecursively()) { "Synthetic regression root cleanup failed" }
    }

    private class SyntheticSuppressionDisabledFailure :
        RuntimeException("synthetic suppression disabled body failure", null, false, true)

    /** Only used when the original test Throwable refuses to retain the cleanup diagnostic. */
    private class SyntheticRootCleanupUnconfirmed(
        val primaryFailure: Throwable,
        val cleanupFailure: Throwable,
    ) : IllegalStateException("Synthetic root cleanup unconfirmed; original test failure preserved as cause", primaryFailure)

    private fun withRoot(block: (DesktopDataRootResolution.Resolved) -> Unit) {
        val path = Files.createTempDirectory("ccb-shutdown-policy-")
        val root = DesktopDataRootResolution.Resolved(path, DesktopDataRootProvenance.CLI_OVERRIDE, path.resolveSibling("unused-bootstrap.json"))
        var primaryFailure: Throwable? = null
        try {
            block(root)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailure = try {
                check(path !in retainedSyntheticRoots) {
                    "Synthetic shutdown root retained: bounded teardown drain NOT CONFIRMED"
                }
                when (val result = DesktopDataRootOwnership.acquire(root)) {
                    is DesktopDataRootOwnershipResult.Acquired -> {
                        result.ownership.close()
                        check(path.toFile().deleteRecursively()) { "Synthetic shutdown root cleanup failed" }
                    }
                    else -> error("Synthetic shutdown root retained: ownership release not confirmed (${result.javaClass.simpleName})")
                }
                null
            } catch (failure: Throwable) {
                failure // Keep files and any held ownership; a warning alone would be false-green.
            }
            if (cleanupFailure != null) {
                val primary = primaryFailure
                if (primary == null) throw cleanupFailure
                if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
                if (primary.suppressed.none { it === cleanupFailure }) {
                    throw SyntheticRootCleanupUnconfirmed(primary, cleanupFailure)
                }
                // The original throw remains in flight, with its exact identity and ordered diagnostic.
            }
        }
    }
}
