package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopSessionCommitJournalTest {
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
}
