package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopSessionCommitJournalTest {
    private fun evidence(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            root.relativize(it).toString() to Files.readAllBytes(it).toList()
        }
    }
    private suspend fun fixture(test: suspend (Path, Path, DesktopDataOperationCoordinator) -> Unit) {
        val base = Files.createTempDirectory("session-journal-")
        val root = Files.createDirectory(base.resolve("root"))
        val staged = Files.createDirectory(base.resolve("staged"))
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
        val gate = DesktopDataOperationCoordinator()
        try { test(root, staged, gate) } finally { gate.closeAndDrain(); owner.close(); base.toFile().deleteRecursively() }
    }
    private fun put(root: Path, relative: String, text: String): Path = root.resolve(relative).also {
        Files.createDirectories(it.parent); Files.writeString(it, text)
    }

    @Test fun `session graph and new media commit together without deleting older media`(): Unit = runBlocking { fixture { root, staged, gate ->
        val session = "entities/chat_sessions/session.json"
        val old = put(root, session, "old session")
        val oldMedia = put(root, "audio/generated/session/old.mp3", "retained recording")
        val changes = linkedMapOf<String, Path?>(session to put(staged, "session", "new session"),
            "entities/chat_messages/session_message.json" to put(staged, "message", "new message"),
            "images/save_slots/slot/image.png" to put(staged, "image", "synthetic image bytes"))
        val expected = changes.mapValues { (path, _) -> if (path == session) DesktopArchiveFiles.hash(old, 1024) else null }
        gate.withExclusiveMaintenance { DesktopSessionCommitJournal(root).commit(changes, expected) }
        assertEquals("new session", Files.readString(old)); assertEquals("retained recording", Files.readString(oldMedia))
        assertEquals("new message", Files.readString(root.resolve("entities/chat_messages/session_message.json")))
        DesktopSessionCommitJournal(root).recover()
        assertFalse(DesktopSessionCommitJournal(root).hasPending())
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, gate.state)
    } }

    @Test fun `completed transaction retains recovery evidence without replaying it over later live data`(): Unit = runBlocking { fixture { root, staged, gate ->
        val relative = "entities/chat_sessions/session.json"
        val live = put(root, relative, "old synthetic session")
        val expected = mapOf(relative to DesktopArchiveFiles.hash(live, 1024))
        gate.withExclusiveMaintenance {
            DesktopSessionCommitJournal(root).commit(mapOf(relative to put(staged, "next", "new synthetic session")), expected)
        }
        val home = root.resolve("backups/.restore-session.tmp")
        val completed = Files.newDirectoryStream(home, "done-*").use { it.toList().single() }
        assertEquals("old synthetic session", Files.readString(completed.resolve("0.old")))
        assertEquals("new synthetic session", Files.readString(completed.resolve("0.next")))
        assertTrue(Files.exists(completed.resolve("intent.json")))
        assertTrue(Files.exists(completed.resolve("committed")))
        val retained = evidence(home)
        // Completed evidence is not an instruction to undo legitimate work done after publication.
        Files.writeString(live, "later synthetic user edit")
        DesktopSessionCommitJournal(root).recover()
        assertEquals("later synthetic user edit", Files.readString(live))
        assertFalse(DesktopSessionCommitJournal(root).hasPending())
        assertEquals(retained, evidence(home))

        val incomplete = Files.createDirectory(home.resolve("active-synthetic-preparation"))
        Files.writeString(incomplete.resolve("0.old"), "uncommitted preparation evidence")
        val withPreparation = evidence(home)
        DesktopSessionCommitJournal(root).recover()
        assertFalse(DesktopSessionCommitJournal(root).hasPending())
        assertEquals(withPreparation, evidence(home), "No intent means no permission to publish or sweep the preparation")
        assertEquals("later synthetic user edit", Files.readString(live))
    } }

    @Test fun `failure restores all old files and removes only newly published files`(): Unit = runBlocking { fixture { root, staged, gate ->
        val session = "entities/chat_sessions/session.json"
        val old = put(root, session, "old session")
        val changes = linkedMapOf<String, Path?>(session to put(staged, "session", "new session"),
            "entities/chat_messages/session_message.json" to put(staged, "message", "new message"))
        val expected = mapOf(session to DesktopArchiveFiles.hash(old, 1024), "entities/chat_messages/session_message.json" to null)
        val journal = DesktopSessionCommitJournal(root) { index -> if (index == 1) error("synthetic commit failure") }
        assertFailsWith<IllegalStateException> { gate.withExclusiveMaintenance { journal.commit(changes, expected) } }
        assertEquals("old session", Files.readString(old))
        assertFalse(Files.exists(root.resolve("entities/chat_messages/session_message.json")))
        assertFalse(journal.hasPending())
        assertTrue(Files.walk(root.resolve("backups")).use { files -> files.anyMatch { it.fileName.toString() == "0.old" } })
    } }

    @Test fun `unknown live bytes block rollback and startup keeps evidence`(): Unit = runBlocking { fixture { root, staged, gate ->
        val session = "entities/chat_sessions/session.json"
        val old = put(root, session, "old session")
        val expected = mapOf(session to DesktopArchiveFiles.hash(old, 1024))
        val journal = DesktopSessionCommitJournal(root) { Files.writeString(old, "foreign bytes"); error("synthetic interruption") }
        val error = assertFailsWith<IllegalStateException> { gate.withExclusiveMaintenance {
            try { journal.commit(mapOf(session to put(staged, "session", "new session")), expected) }
            catch (failure: Throwable) { if (journal.hasPending()) requireRestart(); throw failure }
        } }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.suppressed.isNotEmpty() })
        assertEquals(DesktopDataOperationCoordinatorState.RESTART_REQUIRED, gate.state)
        assertFailsWith<IllegalStateException> { DesktopSessionCommitJournal(root).recover() }
        assertEquals("foreign bytes", Files.readString(old)); assertTrue(journal.hasPending())
    } }

    @Test fun `publication rejects stale expected bytes and out of scope stores before live changes`(): Unit = runBlocking { fixture { root, staged, gate ->
        val session = "entities/chat_sessions/session.json"
        val old = put(root, session, "original")
        val next = put(staged, "next", "replacement")
        val journal = DesktopSessionCommitJournal(root)
        assertFailsWith<IllegalStateException> { gate.withExclusiveMaintenance { journal.commit(mapOf(session to next), mapOf(session to null)) } }
        assertEquals("original", Files.readString(old))
        for (path in listOf("entities/app_settings.json", "secrets/credential", "images/save_slots/slot/..", "../outside")) {
            assertFailsWith<IllegalStateException> { gate.withExclusiveMaintenance { journal.commit(mapOf(path to next), mapOf(path to null)) } }
        }
        assertFalse(journal.hasPending())
    } }

    @Test fun `a reply journal rolls back source and derived rows after an abrupt process exit`() = runBlocking {
        for (cut in listOf(0, 4)) {
            val base = Files.createTempDirectory("reply-journal-crash-")
            val root = Files.createDirectory(base.resolve("root"))
            val staged = Files.createDirectory(base.resolve("staged"))
            val paths = ReplyJournalCrashChild.paths
            val originals = paths.dropLast(1).associateWith { "synthetic old $it" }
            try {
                originals.forEach { (relative, value) -> put(root, relative, value) }
                for (index in 2..5) put(staged, "$index.txt", "synthetic next $index")
                val java = Path.of(System.getProperty("java.home"), "bin",
                    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
                val classpath = requireNotNull(System.getProperty("ccb.readonly.classpath"))
                val log = base.resolve("child.log")
                val child = ProcessBuilder(java.toString(), "-Xmx96m", "-XX:+UseSerialGC", "-cp", classpath,
                    ReplyJournalCrashChild::class.java.name, root.toString(), staged.toString(), cut.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start()
                try {
                    assertTrue(child.waitFor(30, TimeUnit.SECONDS))
                    assertEquals(73, child.exitValue(), Files.readString(log))
                } finally { if (child.isAlive) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
                val journal = DesktopSessionCommitJournal(root)
                assertTrue(journal.hasPending())
                assertFalse(Files.exists(root.resolve(paths.first())))
                assertEquals(if (cut == 4) "synthetic next 4" else originals[paths[4]], Files.readString(root.resolve(paths[4])))
                val resolution = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))
                val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
                try { journal.recover() } finally { owner.close() }
                originals.forEach { (relative, value) -> assertEquals(value, Files.readString(root.resolve(relative))) }
                assertFalse(Files.exists(root.resolve(paths.last())))
                assertFalse(journal.hasPending())
                Files.newDirectoryStream(root.resolve("backups/.restore-session.tmp"), "*-reply-*").use {
                    assertFalse(it.iterator().hasNext())
                }
            } finally { base.toFile().deleteRecursively() }
        }
    }
}

internal object ReplyJournalCrashChild {
    val paths = listOf("entities/generated_voice_messages/voice.json", "entities/voice_anchor_states/anchor.json",
        "entities/chat_message_indexes/s.json", "entities/chat_sessions/s.json", "entities/chat_messages/s_m.json",
        "backups/.session-deletion-cleanup/voice-test.json")

    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val root = Path.of(args[0])
        val staged = Path.of(args[1])
        val cut = args[2].toInt()
        val resolution = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolveSibling("bootstrap.json"))
        val owner = (DesktopDataRootOwnership.acquire(resolution) as DesktopDataRootOwnershipResult.Acquired).ownership
        val changes = paths.mapIndexed { index, relative -> relative to if (index >= 2) staged.resolve("$index.txt") else null }.toMap()
        val expected = paths.associateWith { relative ->
            val file = root.resolve(relative)
            if (Files.exists(file)) DesktopArchiveFiles.hash(file, 1024) else null
        }
        try {
            DesktopDataOperationCoordinator().withExclusiveMaintenance {
                DesktopSessionCommitJournal(root) { index -> if (index == cut) Runtime.getRuntime().halt(73) }
                    .commit(changes, expected, discardCompleted = true)
            }
        } finally { owner.close() }
    }
}
