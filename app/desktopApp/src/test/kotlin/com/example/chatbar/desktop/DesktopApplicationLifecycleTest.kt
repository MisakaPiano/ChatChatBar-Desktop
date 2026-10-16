package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

class DesktopApplicationLifecycleTest {
    @Test
    fun `ownership wraps container runtime and application lifetime in order`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val calls = mutableListOf<String>()

            runDesktopApplicationWithDataRootOwnership(
                resolvedRoot = resolvedRoot(appDataRoot),
                acquireOwnership = { selectedRoot ->
                    calls += "ownership-acquire"
                    DesktopDataRootOwnership.acquire(selectedRoot)
                },
                closeOwnership = { ownership ->
                    calls += "ownership-close"
                    ownership.close()
                },
                applicationBody = {
                    calls += "container-construct"
                    runDesktopApplicationLifecycle(
                        initialize = { calls += "initialize" },
                        applicationBody = { calls += "application" },
                        close = { calls += "container-close" },
                    )
                },
            )

            assertEquals(
                listOf(
                    "ownership-acquire",
                    "container-construct",
                    "initialize",
                    "application",
                    "container-close",
                    "ownership-close",
                ),
                calls,
            )
            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `already-owned selected root prevents container and alternate-root startup`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val existing = assertIs<DesktopDataRootOwnershipResult.Acquired>(
                DesktopDataRootOwnership.acquire(resolvedRoot(appDataRoot)),
            ).ownership
            val alternateRoot = appDataRoot.resolveSibling("unintended-default")
            var containerConstructed = false
            try {
                val presented = mutableListOf<Path>()
                runDesktopApplicationWithDataRootOwnership(resolvedRoot(appDataRoot),
                    alreadyInUsePresenter = DesktopAlreadyInUsePresenter { presented.add(it) }) {
                    containerConstructed = true
                }

                assertEquals(listOf(appDataRoot), presented)
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(
                    DesktopDataRootOwnership.acquire(resolvedRoot(appDataRoot)))
                assertFalse(containerConstructed)
                assertFalse(Files.exists(alternateRoot))
            } finally {
                existing.close()
            }
        }
    }

    @Test
    fun `ownership activation failure prevents container startup`() {
        withTemporaryAppDataRoot { appDataRoot ->
            var containerConstructed = false

            val thrown = assertFailsWith<DesktopDataRootOwnershipException> {
                runDesktopApplicationWithDataRootOwnership(resolvedRoot(appDataRoot)) {
                    containerConstructed = true
                }
            }

            val failure = assertIs<DesktopDataRootOwnershipResult.Failure>(thrown.result)
            assertEquals(DesktopDataRootOwnershipFailureKind.ROOT_MISSING, failure.kind)
            assertFalse(containerConstructed)
            assertFalse(Files.exists(appDataRoot))
        }
    }

    @Test
    fun `normal application completion releases ownership`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)

            runDesktopApplicationWithDataRootOwnership(resolvedRoot(appDataRoot)) {}

            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `application failure releases ownership and remains primary`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val applicationFailure = IllegalStateException("application")

            val thrown = assertFailsWith<IllegalStateException> {
                runDesktopApplicationWithDataRootOwnership(resolvedRoot(appDataRoot)) {
                    throw applicationFailure
                }
            }

            assertSame(applicationFailure, thrown)
            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `container construction failure releases ownership`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val constructionFailure = IllegalArgumentException("container construction")

            val thrown = assertFailsWith<IllegalArgumentException> {
                runDesktopApplicationWithDataRootOwnership(resolvedRoot(appDataRoot)) {
                    throw constructionFailure
                }
            }

            assertSame(constructionFailure, thrown)
            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `initialization failure closes container before releasing outer ownership`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val initializationFailure = IllegalStateException("initialize")
            var closed = false

            val thrown = assertFailsWith<IllegalStateException> {
                runDesktopApplicationWithDataRootOwnership(resolvedRoot(appDataRoot)) {
                    runDesktopApplicationLifecycle(
                        initialize = { throw initializationFailure },
                        applicationBody = { error("application must not start") },
                        close = { closed = true },
                    )
                }
            }

            assertSame(initializationFailure, thrown)
            assertTrue(closed)
            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `unconfirmed container close retains ownership and original exception`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val containerCloseFailure = IllegalStateException("container close")
            lateinit var held: DesktopDataRootOwnership
            try {
                val thrown = assertFailsWith<IllegalStateException> {
                    runDesktopApplicationWithDataRootOwnership(resolvedRoot(appDataRoot), acquireOwnership = {
                        DesktopDataRootOwnership.acquire(it).also { result -> held = assertIs<DesktopDataRootOwnershipResult.Acquired>(result).ownership }
                    }) {
                        runDesktopApplicationLifecycle(
                            initialize = {},
                            applicationBody = {},
                            close = { throw containerCloseFailure },
                        )
                    }
                }

                assertSame(containerCloseFailure, thrown)
                assertTrue(thrown.suppressed.isNotEmpty())
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(resolvedRoot(appDataRoot)))
            } finally { held.close() }
            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `task drain timeout retains ownership while storage remains open`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val selectedRoot = resolvedRoot(appDataRoot)
            val drainTimeout = DesktopTaskDrainTimeoutException("still active")
            lateinit var retainedOwnership: DesktopDataRootOwnership
            var ownershipCloseCalled = false

            try {
                val thrown = assertFailsWith<DesktopTaskDrainTimeoutException> {
                    runDesktopApplicationWithDataRootOwnership(
                        resolvedRoot = selectedRoot,
                        acquireOwnership = { root ->
                            DesktopDataRootOwnership.acquire(root).also { result ->
                                retainedOwnership = assertIs<DesktopDataRootOwnershipResult.Acquired>(result).ownership
                            }
                        },
                        closeOwnership = {
                            ownershipCloseCalled = true
                            it.close()
                        },
                        applicationBody = {
                            runDesktopApplicationLifecycle(
                                initialize = {},
                                applicationBody = {},
                                close = { throw drainTimeout },
                            )
                        },
                    )
                }

                assertSame(drainTimeout, thrown)
                assertFalse(ownershipCloseCalled)
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(
                    DesktopDataRootOwnership.acquire(selectedRoot),
                )
            } finally {
                retainedOwnership.close()
            }
            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `draft drain timeout retains existing root ownership including suppressed failure`() {
        listOf(false, true).forEach { suppressed ->
            withTemporaryAppDataRoot { appDataRoot ->
                Files.createDirectories(appDataRoot)
                val selectedRoot = resolvedRoot(appDataRoot)
                val timeout = DesktopDraftDrainTimeoutException()
                val expected = if (suppressed) IllegalStateException("application").apply { addSuppressed(timeout) } else timeout
                lateinit var retained: DesktopDataRootOwnership
                var closed = false
                try {
                    val thrown = assertFailsWith<IllegalStateException> {
                        runDesktopApplicationWithDataRootOwnership(
                            resolvedRoot = selectedRoot,
                            acquireOwnership = { root ->
                                DesktopDataRootOwnership.acquire(root).also {
                                    retained = assertIs<DesktopDataRootOwnershipResult.Acquired>(it).ownership
                                }
                            },
                            closeOwnership = { closed = true; it.close() },
                            applicationBody = { throw expected },
                        )
                    }
                    assertSame(expected, thrown)
                    assertFalse(closed)
                    assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(selectedRoot))
                } finally { retained.close() }
                assertOwnershipAvailable(appDataRoot)
            }
        }
    }

    @Test
    fun `suppressed task drain timeout keeps application failure primary and ownership held`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val selectedRoot = resolvedRoot(appDataRoot)
            val applicationFailure = IllegalStateException("application")
            val drainTimeout = DesktopTaskDrainTimeoutException("still active")
            lateinit var retainedOwnership: DesktopDataRootOwnership
            var ownershipCloseCalled = false

            try {
                val thrown = assertFailsWith<IllegalStateException> {
                    runDesktopApplicationWithDataRootOwnership(
                        resolvedRoot = selectedRoot,
                        acquireOwnership = { root ->
                            DesktopDataRootOwnership.acquire(root).also { result ->
                                retainedOwnership = assertIs<DesktopDataRootOwnershipResult.Acquired>(result).ownership
                            }
                        },
                        closeOwnership = {
                            ownershipCloseCalled = true
                            it.close()
                        },
                        applicationBody = {
                            runDesktopApplicationLifecycle(
                                initialize = {},
                                applicationBody = { throw applicationFailure },
                                close = { throw drainTimeout },
                            )
                        },
                    )
                }

                assertSame(applicationFailure, thrown)
                assertEquals(listOf(drainTimeout), thrown.suppressed.toList())
                assertFalse(ownershipCloseCalled)
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(
                    DesktopDataRootOwnership.acquire(selectedRoot),
                )
            } finally {
                retainedOwnership.close()
            }
            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `ownership close failure is suppressed behind application failure`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val applicationFailure = IllegalStateException("application")
            val ownershipCloseFailure = IllegalArgumentException("ownership close")

            val thrown = assertFailsWith<IllegalStateException> {
                runDesktopApplicationWithDataRootOwnership(
                    resolvedRoot = resolvedRoot(appDataRoot),
                    closeOwnership = { ownership ->
                        ownership.close()
                        throw ownershipCloseFailure
                    },
                    applicationBody = { throw applicationFailure },
                )
            }

            assertSame(applicationFailure, thrown)
            assertEquals(listOf(ownershipCloseFailure), thrown.suppressed.toList())
            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `application and container failures preserve suppression while refusing ownership release`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val applicationFailure = IllegalStateException("application")
            val containerCloseFailure = IllegalArgumentException("container close")
            lateinit var held: DesktopDataRootOwnership
            var ownershipCloseCalled = false
            try {
                val thrown = assertFailsWith<IllegalStateException> {
                    runDesktopApplicationWithDataRootOwnership(
                        resolvedRoot = resolvedRoot(appDataRoot),
                        acquireOwnership = { root -> DesktopDataRootOwnership.acquire(root).also {
                            held = assertIs<DesktopDataRootOwnershipResult.Acquired>(it).ownership
                        } },
                        closeOwnership = { ownership ->
                            ownershipCloseCalled = true
                            ownership.close()
                        },
                        applicationBody = {
                            runDesktopApplicationLifecycle(
                                initialize = {},
                                applicationBody = { throw applicationFailure },
                                close = { throw containerCloseFailure },
                            )
                        },
                    )
                }

                assertSame(applicationFailure, thrown)
                assertEquals(
                    listOf(containerCloseFailure),
                    thrown.suppressed.toList(),
                )
                assertFalse(ownershipCloseCalled)
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(resolvedRoot(appDataRoot)))
            } finally { held.close() }
            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `ownership close failure propagates when no earlier failure exists`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val ownershipCloseFailure = IllegalStateException("ownership close")

            val thrown = assertFailsWith<IllegalStateException> {
                runDesktopApplicationWithDataRootOwnership(
                    resolvedRoot = resolvedRoot(appDataRoot),
                    closeOwnership = { ownership ->
                        ownership.close()
                        throw ownershipCloseFailure
                    },
                    applicationBody = {},
                )
            }

            assertSame(ownershipCloseFailure, thrown)
            assertOwnershipAvailable(appDataRoot)
        }
    }

    @Test
    fun `runtime closes before coordinator and coordinator is attempted after runtime failure`() {
        val calls = mutableListOf<String>()
        val runtimeFailure = IllegalStateException("runtime close")

        val thrown = assertFailsWith<IllegalStateException> {
            runBlocking {
                closeDesktopDataRuntimes(
                    runtimeClose = {
                        calls += "runtime"
                        throw runtimeFailure
                    },
                    coordinatorClose = { calls += "coordinator" },
                    migrationServiceClose = { calls += "migration" },
                )
            }
        }

        assertSame(runtimeFailure, thrown)
        assertEquals(listOf("runtime", "coordinator", "migration"), calls)
    }

    @Test
    fun `secondary coordinator close failure is suppressed`() {
        val runtimeFailure = IllegalStateException("runtime close")
        val coordinatorFailure = IllegalArgumentException("coordinator close")

        val thrown = assertFailsWith<IllegalStateException> {
            runBlocking {
                closeDesktopDataRuntimes(
                    runtimeClose = { throw runtimeFailure },
                    coordinatorClose = { throw coordinatorFailure },
                    migrationServiceClose = {},
                )
            }
        }

        assertSame(runtimeFailure, thrown)
        assertEquals(listOf(coordinatorFailure), thrown.suppressed.toList())
    }

    @Test
    fun `migration ownership closes after runtime and coordinator with ordered suppression`() {
        val calls = mutableListOf<String>()
        val runtimeFailure = IllegalStateException("runtime close")
        val coordinatorFailure = IllegalArgumentException("coordinator close")
        val migrationFailure = IllegalAccessException("migration close")

        val thrown = assertFailsWith<IllegalStateException> {
            runBlocking {
                closeDesktopDataRuntimes(
                    runtimeClose = {
                        calls += "runtime"
                        throw runtimeFailure
                    },
                    coordinatorClose = {
                        calls += "coordinator"
                        throw coordinatorFailure
                    },
                    migrationServiceClose = {
                        calls += "migration"
                        throw migrationFailure
                    },
                )
            }
        }

        assertSame(runtimeFailure, thrown)
        assertEquals(listOf(coordinatorFailure, migrationFailure), thrown.suppressed.toList())
        assertEquals(listOf("runtime", "coordinator", "migration"), calls)
    }

    @Test
    fun `normal application lifecycle initializes before body and closes after body`() {
        val calls = mutableListOf<String>()

        runDesktopApplicationLifecycle(
            initialize = { calls += "initialize" },
            applicationBody = { calls += "application" },
            close = { calls += "close" },
        )

        assertEquals(listOf("initialize", "application", "close"), calls)
    }

    @Test
    fun `root-switch exit callback returns through normal container shutdown`() {
        val source = Path.of("source").toAbsolutePath()
        val destination = Path.of("destination").toAbsolutePath()
        val controller = DesktopDataRootSwitchController(
            resolvedRoot = DesktopDataRootResolution.Resolved(
                source,
                DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                source.resolveSibling("bootstrap.json"),
            ),
            directoryPicker = DesktopDirectoryPicker { destination },
            isRestartRequired = { false },
            migrate = { throw IllegalStateException("terminal fixture") },
        )
        val calls = mutableListOf<String>()

        runDesktopApplicationLifecycle(
            initialize = { calls += "initialize" },
            applicationBody = {
                controller.chooseDestination()
                runBlocking { controller.confirmMigration() }
                assertTrue(controller.requestExit { calls += "exit-application" })
            },
            close = { calls += "container-close" },
        )

        assertEquals(listOf("initialize", "exit-application", "container-close"), calls)
    }

    @Test
    fun `application failure still closes and propagates original failure`() {
        val calls = mutableListOf<String>()
        val applicationFailure = IllegalStateException("application fixture failure")

        val thrown = assertFailsWith<IllegalStateException> {
            runDesktopApplicationLifecycle(
                initialize = { calls += "initialize" },
                applicationBody = {
                    calls += "application"
                    throw applicationFailure
                },
                close = { calls += "close" },
            )
        }

        assertSame(applicationFailure, thrown)
        assertEquals(listOf("initialize", "application", "close"), calls)
    }

    @Test
    fun `application failure remains primary when shutdown also fails`() {
        val applicationFailure = IllegalStateException("application")
        val closeFailure = IllegalArgumentException("close")

        val thrown = assertFailsWith<IllegalStateException> {
            runDesktopApplicationLifecycle(
                initialize = {},
                applicationBody = { throw applicationFailure },
                close = { throw closeFailure },
            )
        }

        assertSame(applicationFailure, thrown)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
    }

    @Test
    fun `initialization failure prevents application body from starting`() {
        val calls = mutableListOf<String>()
        val initializationFailure = IllegalStateException("initialize fixture failure")

        val thrown = assertFailsWith<IllegalStateException> {
            runDesktopApplicationLifecycle(
                initialize = {
                    calls += "initialize"
                    throw initializationFailure
                },
                applicationBody = { calls += "application" },
                close = { calls += "close" },
            )
        }

        assertSame(initializationFailure, thrown)
        assertEquals(listOf("initialize", "close"), calls)
    }

    @Test
    fun `initialization failure preserves primary and ordered shutdown secondary failures`() {
        val initialization = IllegalArgumentException("synthetic initialization")
        val backup = IllegalStateException("synthetic backup close")
        val coordinator = IllegalStateException("synthetic coordinator close")
        val migration = IllegalStateException("synthetic migration close")
        val calls = mutableListOf<String>()
        val thrown = assertFailsWith<IllegalArgumentException> {
            runDesktopApplicationLifecycle({ throw initialization }, { error("body must not run") }, {
                closeDesktopDataRuntimes(
                    taskRuntimeClose = { calls += "task" }, draftRuntimeClose = { calls += "draft" },
                    runtimeClose = { calls += "backup"; throw backup },
                    coordinatorClose = { calls += "coordinator"; throw coordinator },
                    migrationServiceClose = { calls += "migration"; throw migration })
            })
        }
        assertSame(initialization, thrown)
        assertEquals(listOf(backup), thrown.suppressed.toList())
        assertEquals(listOf(coordinator, migration), backup.suppressed.take(2))
        assertEquals(listOf("task", "draft", "backup", "coordinator", "migration"), calls)
    }

    @Test
    fun `initialization cancellation still executes suspending cleanup and remains primary`() {
        val cancellation = CancellationException("synthetic initialization cancelled")
        var closed = false
        val thrown = assertFailsWith<CancellationException> {
            runDesktopApplicationLifecycle({ throw cancellation }, { error("must not enter body") }, { yield(); closed = true })
        }
        assertSame(cancellation, thrown)
        assertTrue(closed)
        assertTrue(thrown.suppressed.isEmpty())
    }

    @Test
    fun `initialization primary with incomplete drain retains ownership without hiding cancellation`() {
        for (cancel in listOf(false, true)) withTemporaryAppDataRoot { root ->
            Files.createDirectories(root)
            val primary = if (cancel) CancellationException("synthetic cancel") else IllegalStateException("synthetic init")
            val closeFailure = IllegalStateException("synthetic incomplete close")
            lateinit var held: DesktopDataRootOwnership
            try {
                val thrown = assertFailsWith<IllegalStateException> {
                    runDesktopApplicationWithDataRootOwnership(resolvedRoot(root), acquireOwnership = {
                        DesktopDataRootOwnership.acquire(it).also { result -> held = assertIs<DesktopDataRootOwnershipResult.Acquired>(result).ownership }
                    }) { runDesktopApplicationLifecycle({ throw primary }, { error("no body") }, { throw closeFailure }) }
                }
                assertSame(primary, thrown)
                assertEquals(listOf(closeFailure), thrown.suppressed.toList())
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(resolvedRoot(root)))
            } finally { held.close() }
            assertOwnershipAvailable(root)
        }
    }

    @Test
    fun `same exception from initialization and close does not cause self suppression`() {
        val failure = IllegalStateException("synthetic shared failure")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            runDesktopApplicationLifecycle({ throw failure }, {}, { throw failure })
        })
        assertFalse(failure.suppressed.any { it === failure })
    }

    @Test
    fun `suppression disabled close retains ownership with explicit outcome preserving original exception`() {
        val closeFailure = SuppressionDisabledFailure()
        assertIncompleteCloseRetainsOwnership(null, closeFailure, expectCarrier = true)
        assertTrue(closeFailure.suppressed.isEmpty())
    }

    @Test
    fun `suppression disabled initialization primary cannot hide incomplete close`() {
        for (closeFailure in listOf(SuppressionDisabledFailure(), IllegalStateException("synthetic close"),
            DesktopTaskDrainTimeoutException("synthetic pending drain"))) {
            val primary = SuppressionDisabledFailure()
            assertIncompleteCloseRetainsOwnership(primary, closeFailure, expectCarrier = true)
            assertTrue(primary.suppressed.isEmpty())
        }
    }

    @Test
    fun `normal primary identity and existing suppression order survive incomplete close`() {
        val earlier = IllegalArgumentException("synthetic earlier failure")
        val closeEarlier = IllegalArgumentException("synthetic earlier close failure")
        val primary = IllegalStateException("synthetic initialization").apply { addSuppressed(earlier) }
        val closeFailure = IllegalStateException("synthetic close").apply { addSuppressed(closeEarlier) }
        assertIncompleteCloseRetainsOwnership(primary, closeFailure)
        assertEquals(listOf(earlier, closeFailure), primary.suppressed.toList())
        assertSame(closeEarlier, closeFailure.suppressed.first())
        assertEquals(2, closeFailure.suppressed.size) // Existing secondary, then diagnostic marker.
    }

    @Test
    fun `normal primary remains identical when suppression disabled close needs primary marker`() {
        val earlier = IllegalArgumentException("synthetic earlier failure")
        val primary = IllegalStateException("synthetic initialization").apply { addSuppressed(earlier) }
        val closeFailure = SuppressionDisabledFailure()
        assertIncompleteCloseRetainsOwnership(primary, closeFailure)
        assertEquals(listOf(earlier, closeFailure), primary.suppressed.take(2))
        assertEquals(3, primary.suppressed.size) // Append marker without reordering existing failures.
        assertTrue(closeFailure.suppressed.isEmpty())
    }

    @Test
    fun `cancellation primary and suppression disabled close retain ownership`() {
        val primary = CancellationException("synthetic initialization cancellation")
        val closeFailure = SuppressionDisabledFailure()
        assertIncompleteCloseRetainsOwnership(primary, closeFailure)
        assertSame(closeFailure, primary.suppressed.first())
        assertEquals(2, primary.suppressed.size) // Exact close failure, then fallback diagnostic marker.
    }

    @Test
    fun `close cancellation without primary retains ownership`() {
        assertIncompleteCloseRetainsOwnership(null, CancellationException("synthetic close cancellation"))
    }

    @Test
    fun `same initialization and close instance retains ownership without self suppression`() {
        for (failure in listOf(SuppressionDisabledFailure(), CancellationException("synthetic shared cancellation"),
            IllegalStateException("synthetic shared exception"))) {
            assertIncompleteCloseRetainsOwnership(failure, failure, expectCarrier = failure is SuppressionDisabledFailure)
            assertFalse(failure.suppressed.any { it === failure })
        }
    }

    @Test
    fun `confirmed suspending drain releases ownership even with suppression disabled primary`() {
        for (primary in listOf(SuppressionDisabledFailure(), CancellationException("synthetic init cancellation"))) withTemporaryAppDataRoot { root ->
            Files.createDirectories(root)
            var drained = false
            var ownershipReleased = false
            val thrown = assertFailsWith<Throwable> {
                runDesktopApplicationWithDataRootOwnership(resolvedRoot(root), closeOwnership = {
                    assertTrue(drained)
                    it.close()
                    ownershipReleased = true
                }) {
                    runDesktopApplicationLifecycle({ throw primary }, { error("no UI") }, {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { yield(); drained = true }
                    })
                }
            }
            assertSame(primary, thrown)
            assertTrue(ownershipReleased)
            assertTrue(primary.suppressed.isEmpty())
            assertOwnershipAvailable(root)
        }
    }

    @Test
    fun `unconfirmed outcome does not contaminate subsequent successful lifecycle`() {
        assertIncompleteCloseRetainsOwnership(null, SuppressionDisabledFailure(), expectCarrier = true)
        withTemporaryAppDataRoot { root ->
            Files.createDirectories(root)
            runDesktopApplicationWithDataRootOwnership(resolvedRoot(root)) {
                runDesktopApplicationLifecycle({}, {}, { yield() })
            }
            assertOwnershipAvailable(root)
        }
    }

    private class SuppressionDisabledFailure : RuntimeException("synthetic suppression disabled", null, false, true)

    private fun assertIncompleteCloseRetainsOwnership(
        primary: Throwable?,
        closeFailure: Throwable,
        expectCarrier: Boolean = false,
    ) {
        withTemporaryAppDataRoot { root ->
            Files.createDirectories(root)
            lateinit var held: DesktopDataRootOwnership
            var ownershipReleased = false
            try {
                val thrown = assertFailsWith<Throwable> {
                    runDesktopApplicationWithDataRootOwnership(resolvedRoot(root), acquireOwnership = {
                        DesktopDataRootOwnership.acquire(it).also { result ->
                            held = assertIs<DesktopDataRootOwnershipResult.Acquired>(result).ownership
                        }
                    }, closeOwnership = { ownershipReleased = true; it.close() }) {
                        runDesktopApplicationLifecycle({ if (primary != null) throw primary }, {}, {
                            // Drain may suspend before failing; suspension alone is not confirmation.
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { yield() }
                            throw closeFailure
                        })
                    }
                }
                if (expectCarrier) {
                    val outcome = assertIs<DesktopLifecycleCloseUnconfirmed>(thrown)
                    assertSame(primary ?: closeFailure, outcome.primaryFailure)
                    assertSame(primary ?: closeFailure, outcome.cause)
                    assertSame(closeFailure, outcome.closeFailure)
                } else {
                    assertSame(primary ?: closeFailure, thrown)
                }
                assertFalse(ownershipReleased)
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(resolvedRoot(root)))
            } finally {
                // This fixture has no actual writer. Only the synthetic held lock is disposed here.
                held.close()
            }
            assertOwnershipAvailable(root)
        }
    }

    @Test
    fun `real backup scheduler started before preset failure is drained by container close`() {
        withTemporaryAppDataRoot { root ->
            Files.createDirectories(root)
            val failure = IllegalStateException("synthetic preset read failure")
            lateinit var container: DesktopAppContainer
            var observedRunning = false
            runDesktopApplicationWithDataRootOwnership(resolvedRoot(root)) {
                container = DesktopAppContainer(resolvedRoot(root),
                    secretStoreFactory = { com.example.chatbar.desktop.security.InMemoryDesktopSecretStore() },
                    bundledAssets = {
                        observedRunning = container.automaticBackupRuntime.state.value.schedulerRunning
                        throw failure // Never reads bundled presets or user data.
                    })
                runBlocking {
                    val store = DesktopSettingsStore(root, container.dataOperationCoordinator)
                    val missing = assertIs<DesktopSettingsLoadResult.Missing>(store.load())
                    store.save(missing.document, DesktopSettings(automaticBackup = DesktopAutomaticBackupSettings(
                        enabled = true, minimumBackupInterval = Duration.ofHours(6), maximumSnapshotCount = 4,
                        checkInterval = Duration.ofMinutes(30))))
                }
                assertSame(failure, assertFailsWith<IllegalStateException> {
                    runDesktopApplicationLifecycle(container::initializePersistentState,
                        { error("initialization failed; no application") }, container::close)
                })
                assertTrue(observedRunning)
                assertFalse(container.automaticBackupRuntime.state.value.schedulerRunning)
                assertEquals(DesktopAutomaticBackupRuntimeMode.CLOSED, container.automaticBackupRuntime.state.value.mode)
                assertEquals(DesktopDataOperationCoordinatorState.CLOSED, container.dataOperationCoordinator.state)
            }
            assertOwnershipAvailable(root)
        }
    }

    @Test
    fun `missing settings lifecycle stays disabled and performs no writes`() {
        withTemporaryAppDataRoot { appDataRoot ->
            val container = DesktopAppContainer(resolvedRoot(appDataRoot))

            runDesktopApplicationLifecycle(
                initialize = { container.automaticBackupRuntime.initialize() },
                applicationBody = {
                    val state = container.automaticBackupRuntime.state.value
                    assertIs<DesktopSettingsLoadResult.Missing>(state.settingsLoadResult)
                    assertEquals(DesktopSettings(), state.effectiveSettings)
                    assertFalse(state.schedulerRunning)
                    assertFalse(Files.exists(appDataRoot))
                },
                close = { container.close() },
            )

            assertFalse(container.automaticBackupRuntime.state.value.schedulerRunning)
            assertFalse(Files.exists(appDataRoot))
        }
    }

    @Test
    fun `corrupt settings do not prevent application body and remain unchanged`() {
        withTemporaryAppDataRoot { appDataRoot ->
            Files.createDirectories(appDataRoot)
            val settingsPath = appDataRoot.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME)
            val originalBytes = "{ malformed".toByteArray()
            Files.write(settingsPath, originalBytes)
            val container = DesktopAppContainer(resolvedRoot(appDataRoot))
            var applicationEntered = false

            runDesktopApplicationLifecycle(
                initialize = { container.automaticBackupRuntime.initialize() },
                applicationBody = {
                    applicationEntered = true
                    val state = container.automaticBackupRuntime.state.value
                    assertIs<DesktopSettingsLoadResult.Corrupt>(state.settingsLoadResult)
                    assertFalse(state.schedulerRunning)
                },
                close = { container.close() },
            )

            assertTrue(applicationEntered)
            assertContentEquals(originalBytes, settingsPath.readBytes())
        }
    }

    @Test
    fun `persisted enabled settings start scheduler and lifecycle close stops it`() {
        withTemporaryAppDataRoot { appDataRoot ->
            val settings = DesktopSettings(
                automaticBackup = DesktopAutomaticBackupSettings(
                    enabled = true,
                    minimumBackupInterval = Duration.ofHours(6),
                    maximumSnapshotCount = 4,
                    checkInterval = Duration.ofMinutes(30),
                ),
            )
            runBlocking {
                val store = DesktopSettingsStore(appDataRoot, DesktopDataOperationCoordinator())
                val missing = assertIs<DesktopSettingsLoadResult.Missing>(store.load())
                store.save(missing.document, settings)
            }
            val container = DesktopAppContainer(resolvedRoot(appDataRoot))

            runDesktopApplicationLifecycle(
                initialize = { container.automaticBackupRuntime.initialize() },
                applicationBody = {
                    val state = container.automaticBackupRuntime.state.value
                    assertEquals(settings, state.effectiveSettings)
                    assertTrue(state.schedulerRunning)
                },
                close = { container.close() },
            )

            assertFalse(container.automaticBackupRuntime.state.value.schedulerRunning)
            assertEquals(
                DesktopAutomaticBackupRuntimeMode.CLOSED,
                container.automaticBackupRuntime.state.value.mode,
            )
        }
    }

    private fun withTemporaryAppDataRoot(block: (Path) -> Unit) {
        val parent = Files.createTempDirectory("desktop-application-lifecycle-")
        try {
            block(parent.resolve("app-data"))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun resolvedRoot(appDataRoot: Path) = DesktopDataRootResolution.Resolved(
        appDataRoot = appDataRoot,
        provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
        bootstrapPath = appDataRoot.resolveSibling("bootstrap.json"),
    )

    private fun assertOwnershipAvailable(appDataRoot: Path) {
        assertIs<DesktopDataRootOwnershipResult.Acquired>(
            DesktopDataRootOwnership.acquire(resolvedRoot(appDataRoot)),
        ).ownership.close()
    }
}
