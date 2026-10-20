package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import com.example.chatbar.data.local.entity.*
import java.nio.file.*
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopFullBackupServiceTest {
    private class Fixture : AutoCloseable {
        val base = Files.createTempDirectory("whole-app-service-")
        val root = Files.createDirectory(base.resolve("root"))
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
        val gate = DesktopDataOperationCoordinator()
        val focus = DesktopAudioFocus()
        val events = mutableListOf<String>()
        var drain: suspend () -> Unit = {}
        var pause: suspend () -> Unit = {}
        val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {
            events += "drain"; drain()
        }, beforeSnapshot = { events += "flush" }, pauseScheduler = { events += "pause"; pause() },
            resumeScheduler = { events += "resume" })
        val settings = root.resolve("entities/app_settings.json")
        suspend fun seed(): DesktopFullBackupArchive.Prepared {
            Files.createDirectories(settings.parent)
            Files.writeString(settings, backupJson.encodeToString(AppSettings.serializer(), AppSettings(themeMode = ThemeMode.DARK)))
            val path = base.resolve("saved.cbbackup")
            val password = "test-password".toCharArray()
            service.export(path, password)
            assertTrue(password.all { it == '\u0000' })
            val readPassword = "test-password".toCharArray()
            val prepared = service.inspect(path, readPassword)
            assertTrue(readPassword.all { it == '\u0000' })
            Files.writeString(settings, backupJson.encodeToString(AppSettings.serializer(), AppSettings(themeMode = ThemeMode.LIGHT)))
            events.clear()
            return prepared
        }
        fun theme() = backupJson.decodeFromString(AppSettings.serializer(), Files.readString(settings)).themeMode
        override fun close() {
            runBlocking { focus.closeAndDrain(); gate.closeAndDrain() }
            owner.close(); base.toFile().deleteRecursively()
        }
    }

    @Test fun `real gate seals only after schedule and startup installs before any repository opens`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            Files.writeString(f.root.resolve("desktop-settings.json"), "local settings sentinel")
            f.service.scheduleRestore(prepared)
            assertEquals(listOf("flush", "pause", "drain", "resume"), f.events)
            assertEquals(ThemeMode.LIGHT, f.theme())
            assertTrue(f.service.restartRequired)
            assertFailsWith<DesktopDataOperationRejectedException> { f.gate.withNormalOperation { error("late writer admitted") } }
            f.gate.closeAndDrain(); f.owner.close()
            var business = false
            DesktopStartupGate(select = { DesktopStartupRootSelection.Selected(f.resolved) }, presenter = { error("unexpected refusal") }).run {
                assertTrue(DesktopDataRootOwnership.isHeld(f.root))
                assertEquals(ThemeMode.DARK, f.theme())
                assertEquals("local settings sentinel", Files.readString(f.root.resolve("desktop-settings.json")))
                assertFalse(DesktopFullBackupCommitJournal(f.root).hasPending())
                business = true
            }
            assertTrue(business); assertFalse(DesktopDataRootOwnership.isHeld(f.root))
            assertTrue(Files.exists(prepared.source))
        }
    }

    @Test fun `unconfirmed audio close refuses scheduling and preserves current data for retry`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            f.drain = { error("native close unconfirmed") }
            assertFailsWith<DesktopAudioDrainException> { f.service.scheduleRestore(prepared) }
            assertEquals(ThemeMode.LIGHT, f.theme())
            assertFalse(DesktopFullBackupCommitJournal(f.root).hasPending())
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
            assertEquals(listOf("flush", "pause", "drain", "resume"), f.events)
            f.drain = {}; f.service.scheduleRestore(prepared)
            assertTrue(f.service.restartRequired)
        }
    }

    @Test fun `cancellation during drain completes close and resumes scheduler without scheduling`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.drain = { entered.complete(Unit); release.await() }
            val job = launch { f.service.scheduleRestore(prepared) }
            entered.await(); job.cancel(); release.complete(Unit); job.join()
            assertEquals(ThemeMode.LIGHT, f.theme())
            assertFalse(DesktopFullBackupCommitJournal(f.root).hasPending())
            assertEquals("resume", f.events.last())
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
            f.drain = {}; f.service.scheduleRestore(prepared)
            assertTrue(f.service.restartRequired)
        }
    }

    @Test fun `scheduler can finish an audio reservation before restore reserves audio`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            val schedulerAudio = DesktopAudioMaintenance({ f.focus }) {}
            f.pause = {
                schedulerAudio.withMaintenance(false, { f.gate.isRestartRequired }) {
                    f.gate.withExclusiveMaintenance { f.events += "scheduled-backup-finished" }
                }
            }
            f.service.scheduleRestore(prepared)
            assertEquals(listOf("flush", "pause", "scheduled-backup-finished", "drain", "resume"), f.events)
            assertTrue(f.service.restartRequired)
        }
    }

    @Test fun `changed inspected bytes cannot acquire audio or schedule restore`() = runBlocking {
        Fixture().use { f ->
            val prepared = f.seed()
            Files.writeString(prepared.files.values.first(), "changed")
            assertFails { f.service.scheduleRestore(prepared) }
            assertTrue(f.events.isEmpty()); assertEquals(ThemeMode.LIGHT, f.theme())
            assertFalse(DesktopFullBackupCommitJournal(f.root).hasPending())
        }
    }

    @Test fun `cancelled request waiting for operation mutex erases its password`() = runBlocking {
        Fixture().use { f ->
            f.seed()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.drain = { entered.complete(Unit); release.await() }
            val first = async { f.service.export(f.base.resolve("second.cbbackup"), null) }
            entered.await()
            val secret = "synthetic-secret".toCharArray()
            val queued = launch(start = CoroutineStart.UNDISPATCHED) { f.service.inspect(f.base.resolve("never-read"), secret) }
            queued.cancelAndJoin()
            assertTrue(secret.all { it == '\u0000' })
            release.complete(Unit); first.await()
            assertFalse(f.service.restartRequired)
        }
    }
}
