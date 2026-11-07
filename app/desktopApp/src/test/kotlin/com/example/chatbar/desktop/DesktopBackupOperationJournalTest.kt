package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.DesktopFullBackupMapping
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.io.File
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopBackupOperationJournalTest {
    private class Fixture : AutoCloseable {
        val base = Files.createTempDirectory("backup-status-synthetic-")
        val root = Files.createDirectory(base.resolve("root"))
        val gate = DesktopDataOperationCoordinator()
        val focus = DesktopAudioFocus()
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
        val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {})
        val journal = DesktopBackupOperationJournal(root, DesktopBackupOperationJournal.Lane.BACKUP)
        override fun close() {
            runBlocking { focus.closeAndDrain(); gate.closeAndDrain() }; owner.close()
            check(base.toFile().deleteRecursively())
        }
    }

    @Test fun `hard exit leaves only fixed status and fresh process reads uncertainty without executing recovery`() {
        val base = Files.createTempDirectory("backup-status-process-")
        try {
            val source = Files.writeString(base.resolve("original"), "SYNTHETIC_PRIVATE_NEVER_LOG")
            child(base, "halt", 73)
            val marker = base.resolve("backups/backup-operation-backup.json")
            val original = Files.readAllBytes(marker)
            assertFalse(original.toString(Charsets.UTF_8).contains("SYNTHETIC_PRIVATE_NEVER_LOG"))
            child(base, "read", 0)
            assertContentEquals(original, Files.readAllBytes(marker))
            assertEquals("SYNTHETIC_PRIVATE_NEVER_LOG", Files.readString(source))
            assertFalse(Files.exists(base.resolve("backups/.restore-app.tmp")))
            assertFalse(Files.exists(base.resolve("entities")))
        } finally { check(base.toFile().deleteRecursively()) }
    }

    @Test fun `late completion cannot overwrite a newer operation and preview close does not relabel successful export`() {
        Fixture().use { f ->
            val first = f.journal.begin(DesktopBackupOperationJournal.Kind.CHECK)
            val second = f.journal.begin(DesktopBackupOperationJournal.Kind.EXPORT)
            f.journal.finish(first, DesktopBackupOperationJournal.Phase.COMPLETE)
            assertNotNull(DesktopBackupOperationJournal(f.root, DesktopBackupOperationJournal.Lane.BACKUP).previousNotice())
            f.journal.finish(second, DesktopBackupOperationJournal.Phase.COMPLETE)
            f.journal.abandonPreview(second)
            assertNull(DesktopBackupOperationJournal(f.root, DesktopBackupOperationJournal.Lane.BACKUP).previousNotice())
            assertNotNull(DesktopBackupOperationJournal(f.root, DesktopBackupOperationJournal.Lane.BACKUP).previousResult())
            val pending = f.journal.begin(DesktopBackupOperationJournal.Kind.CHECK)
            f.journal.finish(pending, DesktopBackupOperationJournal.Phase.PREVIEW)
            assertNotNull(f.journal.previousNotice())
            f.journal.abandonPreview(pending); assertNull(f.journal.previousNotice()); assertNotNull(f.journal.previousResult())
            val failed = f.journal.begin(DesktopBackupOperationJournal.Kind.EXPORT)
            f.journal.finish(failed, DesktopBackupOperationJournal.Phase.FAILED)
            assertNotNull(DesktopBackupOperationJournal(f.root, DesktopBackupOperationJournal.Lane.BACKUP).previousNotice())
        }
    }

    @Test fun `actual controller reopens status without picker work and status failure after publication preserves export success`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.journal.begin(DesktopBackupOperationJournal.Kind.EXPORT)
            val output = f.base.resolve("export.cbbackup")
            var picked = 0
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType): Path? { picked++; return null }
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path {
                    picked++
                    // Make only the auxiliary completion write fail, after RUNNING was recorded.
                    val marker = f.root.resolve("backups/backup-operation-backup.json")
                    Files.move(marker, f.base.resolve("retained-status.json"))
                    Files.createDirectory(marker)
                    return output
                }
            }
            val controller = DesktopFullBackupController(f.service, picker, operationJournal = f.journal)
            try {
                assertNotNull(controller.state.value.operationNotice); assertEquals(0, picked)
                val before = DesktopFullBackupMapping(f.root).inventory()
                controller.export(null)
                val result = withTimeout(20_000) { controller.state.first { !it.busy && it.exportResult != null } }
                assertTrue(result.exportResult!!.success); assertTrue(Files.isRegularFile(output))
                assertNotNull(result.operationNotice); assertNull(result.error)
                f.service.verifyFile(output, null)
                assertEquals(before, DesktopFullBackupMapping(f.root).inventory())
                assertEquals(1, picked)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `merge controller reads its separate lane and cancelled picker completes without replay`() = runBlocking<Unit> {
        Fixture().use { f ->
            val journal = DesktopBackupOperationJournal(f.root, DesktopBackupOperationJournal.Lane.MERGE)
            journal.begin(DesktopBackupOperationJournal.Kind.MERGE_CHECK)
            var picked = 0
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType): Path? { picked++; return null }
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("No export requested")
            }
            val service = DesktopMergeImportService(f.resolved, f.gate, InMemoryDesktopSecretStore(), {}, { it() })
            val controller = DesktopMergeImportController(service, picker, DesktopDirectoryPicker { error("No destination requested") }, operationJournal = journal)
            try {
                assertNotNull(controller.state.value.operationNotice); assertEquals(0, picked)
                assertNull(f.journal.previousNotice())
                controller.selectFile()
                withTimeout(20_000) { controller.state.first { !it.busy && picked == 1 } }
                assertNull(controller.state.value.operationNotice)
                assertNull(DesktopBackupOperationJournal(f.root, DesktopBackupOperationJournal.Lane.MERGE).previousNotice())
                assertFalse(service.restartRequired)
            } finally { controller.closeAndDrain(); service.close() }
        }
    }

    @Test fun `status records remain outside the real snapshot payload and later updates do not invalidate it`() = runBlocking<Unit> {
        Fixture().use { f ->
            val settings = f.root.resolve("entities/app_settings.json")
            Files.createDirectories(settings.parent); Files.writeString(settings, "{}")
            val ticket = f.journal.begin(DesktopBackupOperationJournal.Kind.EXPORT)
            val snapshots = com.example.chatbar.data.snapshot.AppDataSnapshotService(f.root)
            val snapshot = f.gate.withExclusiveMaintenance { snapshots.createSnapshot() }
            assertTrue(snapshot.validation.valid)
            val payload = snapshot.directory.resolve(com.example.chatbar.data.snapshot.AppDataSnapshotService.PAYLOAD_DIRECTORY_NAME)
            val files = Files.walk(payload).use { paths -> paths.filter { Files.isRegularFile(it) }
                .map { payload.relativize(it).joinToString("/") }.toList().toSet() }
            assertEquals(setOf("entities/app_settings.json"), files)
            f.journal.finish(ticket, DesktopBackupOperationJournal.Phase.COMPLETE)
            assertTrue(snapshots.validateSnapshot(snapshot.directory).valid)
            assertEquals(listOf(snapshot.name), snapshots.listSnapshots().map { it.name })
            assertEquals("{}", Files.readString(settings))
            assertTrue(Files.isRegularFile(f.root.resolve("backups/backup-operation-backup.json")))
        }
    }

    private fun child(root: Path, mode: String, expected: Int) {
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val cp = listOf(DesktopBackupStatusSyntheticChild::class.java, DesktopBackupOperationJournal::class.java,
            Unit::class.java, Json::class.java, kotlinx.serialization.KSerializer::class.java,
            com.sun.jna.Native::class.java, com.sun.jna.platform.win32.Kernel32::class.java).map {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }.distinct().joinToString(File.pathSeparator)
        val log = root.resolve("child-$mode.log")
        val process = ProcessBuilder(java.toString(), "-Xmx96m", "-XX:+UseSerialGC", "-cp", cp,
            DesktopBackupStatusSyntheticChild::class.java.name, root.toString(), mode)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Synthetic status child did not exit")
        assertEquals(expected, process.exitValue(), Files.readString(log))
    }
}

object DesktopBackupStatusSyntheticChild {
    @JvmStatic fun main(args: Array<String>) {
        val journal = DesktopBackupOperationJournal(Path.of(args[0]), DesktopBackupOperationJournal.Lane.BACKUP)
        if (args[1] == "halt") {
            journal.begin(DesktopBackupOperationJournal.Kind.EXPORT)
            Runtime.getRuntime().halt(73)
        }
        check(journal.previousNotice() != null)
    }
}
