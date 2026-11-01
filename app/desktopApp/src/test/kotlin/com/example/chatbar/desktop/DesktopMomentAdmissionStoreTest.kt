package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class DesktopMomentAdmissionStoreTest {
    @Test fun `normal reopen retains local grant while copied root and changed task cannot inherit approval`() = runBlocking {
        val parent = Files.createTempDirectory("moment-admission-")
        try {
            val root = Files.createDirectory(parent.resolve("data")); val custody = parent.resolve("local-only")
            val store = DesktopMomentAdmissionStore(root, custody)
            assertFalse(store.load().active); assertFalse(Files.exists(custody))
            val task = MomentTask.create("card", "session", 1).copy(id = "合成_任务")
            val grant = DesktopMomentAdmission(true, mapOf(task.id to store.taskHash(task)))
            store.save(grant)
            Files.writeString(root.resolve("ordinary-data"), "unrelated new file")
            val reopened = DesktopMomentAdmissionStore(root, custody)
            assertEquals(grant, reopened.load())
            assertNotEquals(grant.tasks[task.id], reopened.taskHash(task.copy(status = MomentTaskStatus.RUNNING)))
            assertNull(grant.tasks[MomentTask.create("card", "session", 1).id])
            val other = Files.createDirectory(parent.resolve("copied-data"))
            assertFailsWith<IllegalStateException> { DesktopMomentAdmissionStore(other, custody).load() }
            assertFalse(Files.exists(root.resolve("moments-admission.json")))
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test fun `actual same-root full restore invalidates external grant before installing imported tasks`() = runBlocking {
        val parent = Files.createTempDirectory("moment-restore-admission-")
        val root = Files.createDirectory(parent.resolve("data"))
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
        val ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
        try {
            val local = DesktopMomentAdmissionStore(root, parent.resolve("local-only"))
            val imported = MomentTask.create("card", "session", 1)
            local.save(DesktopMomentAdmission(true, mapOf(imported.id to local.taskHash(imported))))
            val source = parent.resolve("imported.json")
            Files.writeString(source, kotlinx.serialization.json.Json.encodeToString(MomentTask.serializer(), imported))
            val relative = "entities/moment_tasks/${imported.id}.json"
            val journal = DesktopFullBackupCommitJournal(root)
            journal.schedule(mapOf(relative to source), mapOf(relative to null))
            assertTrue(local.load().active)
            journal.recover()
            assertTrue(Files.exists(root.resolve(relative)))
            assertFailsWith<IllegalStateException> { local.load() }
            // An explicitly saved new grant is usable; restore never silently rewrites the grant.
            local.save(DesktopMomentAdmission())
            assertFalse(local.load().active)
        } finally { ownership.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `bounded corrupt optional epoch is retained while a new restore epoch revokes old consent`() = runBlocking {
        val parent = Files.createTempDirectory("moment-epoch-recovery-")
        try {
            val root = Files.createDirectory(parent.resolve("data")); Files.createDirectory(root.resolve("backups"))
            val marker = root.resolve("backups/.moment-admission-epoch")
            Files.writeString(marker, "synthetic-corruption")
            DesktopMomentAdmissionStore.invalidateForRestore(root)
            assertEquals(36, Files.readString(marker).length)
            val evidence = Files.newDirectoryStream(marker.parent, ".moment-epoch-corrupt-*.evidence").use { it.toList() }
            assertEquals("synthetic-corruption", Files.readString(evidence.single()))
        } finally { parent.toFile().deleteRecursively() }
    }
}
