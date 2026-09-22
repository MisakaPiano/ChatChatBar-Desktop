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

class DesktopApplicationLifecycleTest {
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
                )
            }
        }

        assertSame(runtimeFailure, thrown)
        assertEquals(listOf("runtime", "coordinator"), calls)
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
                )
            }
        }

        assertSame(runtimeFailure, thrown)
        assertEquals(listOf(coordinatorFailure), thrown.suppressed.toList())
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
        assertEquals(listOf("initialize"), calls)
    }

    @Test
    fun `missing settings lifecycle stays disabled and performs no writes`() {
        withTemporaryAppDataRoot { appDataRoot ->
            val container = DesktopAppContainer(appDataRoot)

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

            assertFalse(container.automaticBackupScheduler.isRunning)
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
            val container = DesktopAppContainer(appDataRoot)
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
            val container = DesktopAppContainer(appDataRoot)

            runDesktopApplicationLifecycle(
                initialize = { container.automaticBackupRuntime.initialize() },
                applicationBody = {
                    val state = container.automaticBackupRuntime.state.value
                    assertEquals(settings, state.effectiveSettings)
                    assertTrue(state.schedulerRunning)
                    assertTrue(container.automaticBackupScheduler.isRunning)
                },
                close = { container.close() },
            )

            assertFalse(container.automaticBackupRuntime.state.value.schedulerRunning)
            assertFalse(container.automaticBackupScheduler.isRunning)
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
}
