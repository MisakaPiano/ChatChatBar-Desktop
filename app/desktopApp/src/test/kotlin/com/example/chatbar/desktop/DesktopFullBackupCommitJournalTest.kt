package com.example.chatbar.desktop

import java.nio.file.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopFullBackupCommitJournalTest {
    private suspend fun fixture(test: suspend (Path, Path, DesktopDataOperationCoordinator) -> Unit) {
        val base = Files.createTempDirectory("app-restore-journal-")
        val root = Files.createDirectory(base.resolve("root"))
        val stage = Files.createDirectory(base.resolve("stage"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json")))).ownership
        val gate = DesktopDataOperationCoordinator()
        try { test(root, stage, gate) } finally { gate.closeAndDrain(); owner.close(); base.toFile().deleteRecursively() }
    }
    private fun put(root: Path, name: String, value: String): Path = root.resolve(name).also {
        Files.createDirectories(it.parent); Files.writeString(it, value)
    }
    @Test fun `scheduled replacement does not touch live data until owned startup recovery`() = runBlocking { fixture { root, stage, gate ->
        val old = put(root, "entities/app_settings.json", "old")
        val next = put(stage, "settings", "new")
        val oldHash = DesktopArchiveFiles.hash(old, 1024)
        val journal = DesktopFullBackupCommitJournal(root)
        gate.withExclusiveMaintenance {
            journal.schedule(mapOf("entities/app_settings.json" to next), mapOf("entities/app_settings.json" to oldHash))
            requireRestart()
        }
        assertEquals("old", Files.readString(old)); assertTrue(journal.hasPending())
        assertEquals(DesktopDataOperationCoordinatorState.RESTART_REQUIRED, gate.state)
        DesktopFullBackupCommitJournal(root).recover()
        assertEquals("new", Files.readString(old)); assertFalse(journal.hasPending())
        assertTrue(Files.walk(root.resolve("backups")).use { stream -> stream.anyMatch { it.fileName.toString() == "0.old" && Files.readString(it) == "old" } })
        DesktopFullBackupCommitJournal(root).recover()
        assertEquals("new", Files.readString(old))
    } }
    @Test fun `crash after a partial installation rolls back before repositories can open`() = runBlocking { fixture { root, stage, gate ->
        val one = put(root, "entities/app_settings.json", "old settings")
        val two = put(root, "images/old.png", "old image")
        val changes = linkedMapOf<String, Path?>("entities/app_settings.json" to put(stage, "settings", "new settings"), "images/old.png" to null)
        val expected = mapOf("entities/app_settings.json" to DesktopArchiveFiles.hash(one, 1024), "images/old.png" to DesktopArchiveFiles.hash(two, 1024))
        val journal = DesktopFullBackupCommitJournal(root)
        gate.withExclusiveMaintenance { journal.schedule(changes, expected) }
        val active = Files.newDirectoryStream(root.resolve("backups/.restore-app.tmp"), "active-*").use { it.single() }
        Files.write(active.resolve("started"), byteArrayOf(1), StandardOpenOption.CREATE_NEW)
        Files.copy(active.resolve("0.next"), one, StandardCopyOption.REPLACE_EXISTING)
        DesktopFullBackupCommitJournal(root).recover()
        assertEquals("old settings", Files.readString(one)); assertEquals("old image", Files.readString(two))
        assertFalse(journal.hasPending())
    } }
    @Test fun `failure in apply restores old data and retains source and evidence`() = runBlocking { fixture { root, stage, gate ->
        val old = put(root, "entities/app_settings.json", "old")
        val source = put(stage, "settings", "new")
        gate.withExclusiveMaintenance { DesktopFullBackupCommitJournal(root).schedule(mapOf("entities/app_settings.json" to source),
            mapOf("entities/app_settings.json" to DesktopArchiveFiles.hash(old, 1024))) }
        assertFails { DesktopFullBackupCommitJournal(root) { error("synthetic disk failure") }.recover() }
        assertEquals("old", Files.readString(old)); assertEquals("new", Files.readString(source))
        assertFalse(DesktopFullBackupCommitJournal(root).hasPending())
    } }
    @Test fun `foreign bytes and changed staged input block recovery without deleting evidence`() = runBlocking { fixture { root, stage, gate ->
        val old = put(root, "entities/app_settings.json", "old")
        gate.withExclusiveMaintenance { DesktopFullBackupCommitJournal(root).schedule(mapOf("entities/app_settings.json" to put(stage, "settings", "new")),
            mapOf("entities/app_settings.json" to DesktopArchiveFiles.hash(old, 1024))) }
        Files.writeString(old, "foreign")
        assertFails { DesktopFullBackupCommitJournal(root).recover() }
        assertEquals("foreign", Files.readString(old)); assertTrue(DesktopFullBackupCommitJournal(root).hasPending())
        val active = Files.newDirectoryStream(root.resolve("backups/.restore-app.tmp"), "active-*").use { it.single() }
        Files.writeString(active.resolve("0.next"), "changed")
        assertFails { DesktopFullBackupCommitJournal(root).recover() }
        assertEquals("foreign", Files.readString(old)); assertTrue(Files.exists(active.resolve("0.old")))
    } }
}
