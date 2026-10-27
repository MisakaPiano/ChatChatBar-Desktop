package com.example.chatbar.desktop

import java.io.File
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopFullBackupProcessTest {
    @Test fun `separate JVM schedules then fresh JVM restores with old snapshot retained`() {
        val root = Files.createTempDirectory("ccb-backup-process-")
        child(root, "schedule", 0)
        assertEquals("old synthetic", Files.readString(root.resolve("entities/app_settings.json")))
        child(root, "recover", 0)
        assertEquals("new synthetic", Files.readString(root.resolve("entities/app_settings.json")))
        child(root, "recover", 0)
        assertTrue(Files.walk(root.resolve("backups")).use { paths -> paths.anyMatch {
            it.fileName.toString() == "0.old" && Files.readString(it) == "old synthetic"
        } })
        assertEquals("new synthetic", Files.readString(root.resolve("source")))
    }

    @Test fun `hard process exit during apply is rolled back by independent JVM`() {
        val root = Files.createTempDirectory("ccb-backup-crash-")
        child(root, "schedule", 0)
        child(root, "crash-during-apply", 71)
        assertEquals("new synthetic", Files.readString(root.resolve("entities/app_settings.json")))
        child(root, "recover", 0)
        assertEquals("old synthetic", Files.readString(root.resolve("entities/app_settings.json")))
        assertEquals("new synthetic", Files.readString(root.resolve("source")))
    }

    @Test fun `fresh process rejects changed journal evidence retaining live and source bytes`() {
        val root = Files.createTempDirectory("ccb-backup-corrupt-")
        child(root, "schedule", 0)
        val active = Files.newDirectoryStream(root.resolve("backups/.restore-app.tmp"), "active-*").use { it.single() }
        Files.writeString(active.resolve("0.next"), "corrupt synthetic")
        child(root, "reject", 0)
        assertEquals("old synthetic", Files.readString(root.resolve("entities/app_settings.json")))
        assertTrue(Files.exists(active.resolve("0.old")))
        assertEquals("new synthetic", Files.readString(root.resolve("source")))
    }

    private fun child(root: Path, mode: String, expected: Int) {
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val cp = listOf(DesktopFullBackupSyntheticChild::class.java, DesktopDataRootOwnership::class.java,
            com.example.chatbar.data.operation.AppDataOperationGate::class.java,
            Unit::class.java, kotlinx.coroutines.Job::class.java, Json::class.java,
            kotlinx.serialization.KSerializer::class.java, com.sun.jna.Native::class.java,
            com.sun.jna.platform.win32.Kernel32::class.java).map {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }.distinct().joinToString(File.pathSeparator)
        val log = root.resolve("child-$mode-${System.nanoTime()}.log")
        val process = ProcessBuilder(java.toString(), "-Xmx96m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "-cp", cp, DesktopFullBackupSyntheticChild::class.java.name, root.toString(), mode)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Owned synthetic child timed out; retained for diagnosis")
        assertEquals(expected, process.exitValue(), Files.readString(log))
        println("synthetic-child mode=$mode pid=${process.pid()} exit=${process.exitValue()} alive=${process.isAlive}")
        // Retain process logs and original/new journal bytes for failure analysis.
    }
}

object DesktopFullBackupSyntheticChild {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = Path.of(args[0])
        val owner = (DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired).ownership
        try {
            val journal = DesktopFullBackupCommitJournal(root) {
                if (args[1] == "crash-during-apply") Runtime.getRuntime().halt(71)
            }
            when (args[1]) {
                "schedule" -> {
                    val live = root.resolve("entities/app_settings.json")
                    Files.createDirectories(live.parent)
                    Files.writeString(live, "old synthetic", StandardOpenOption.CREATE_NEW)
                    val source = Files.writeString(root.resolve("source"), "new synthetic", StandardOpenOption.CREATE_NEW)
                    val gate = DesktopDataOperationCoordinator()
                    try { gate.withExclusiveMaintenance {
                        journal.schedule(mapOf("entities/app_settings.json" to source),
                            mapOf("entities/app_settings.json" to DesktopArchiveFiles.hash(live, 1024)))
                        requireRestart()
                    } } finally { gate.closeAndDrain() }
                }
                "reject" -> {
                    var rejected = false
                    try { journal.recover() } catch (_: IllegalStateException) { rejected = true }
                    check(rejected && journal.hasPending())
                }
                else -> journal.recover()
            }
            println("RESULT ${args[1]}")
        } finally { owner.close() }
    }
}
