package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.repository.MemoryRepository
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopMemorySafetyTest {
    @Test fun `strict memory reads valid local paths beyond legacy Win32 length without changing source bytes`() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val base = Files.createTempDirectory("memory-long-native-").toAbsolutePath().normalize()
        try {
            var root = base
            var level = 0
            while (root.toString().length < 230) root = Files.createDirectory(root.resolve("level-${level++}-" + "x".repeat(24)))
            val storage = JsonFileStorage(root)
            val session = ChatSession("s", "c", "Synthetic long-path session", createdAt = 1, updatedAt = 1)
            val message = ChatMessage("message-" + "x".repeat(60), "s", MessageRole.USER, "Synthetic source retained", createdAt = 1, updatedAt = 1)
            storage.saveEntity("chat_sessions", session.id, session, ChatSession.serializer())
            storage.saveEntity("chat_messages", "s_${message.id}", message, ChatMessage.serializer())
            val file = root.resolve("entities/chat_messages/s_${message.id}.json")
            assertTrue(file.toString().length > 260)
            assertTrue(Files.isRegularFile(file))
            val before = Files.readAllBytes(file)
            val raw = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(file.toString())
            val rawError = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetLastError()
            val extended = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(DesktopArchiveFiles.nativePath(file))
            println("SYNTHETIC_LONG_PATH_METADATA rawRejected=${raw == -1};rawError=$rawError;extendedSafe=${extended != -1 && extended and 0x400 == 0};length=${file.toString().length}")
            assertTrue(extended != -1 && extended and 0x400 == 0)
            val messages = DesktopMemorySources(root, storage, com.example.chatbar.data.repository.ChatRepository(storage)).read("s")
            assertEquals(listOf(message), messages)
            assertContentEquals(before, Files.readAllBytes(file))
        } finally {
            check(base.parent == java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize() &&
                base.fileName.toString().startsWith("memory-long-native-"))
            Files.walk(base).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                check(it.toAbsolutePath().normalize().startsWith(base) && !Files.isSymbolicLink(it))
                Files.delete(it)
            } }
        }
    }

    @Test fun `strict memory never interprets corrupt state as new empty memory`() = runBlocking {
        val root = Files.createTempDirectory("memory-corrupt-")
        try {
            val storage = JsonFileStorage(root)
            val repo = MemoryRepository(storage, strictReads = true)
            val path = root.resolve("entities/memory_states/session.json")
            Files.createDirectories(path.parent); Files.writeString(path, "broken original")
            assertFails { repo.getState("session") }
            assertEquals("broken original", Files.readString(path))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `strict recovery rejects foreign session nodes and preserves journal`() = runBlocking {
        val root = Files.createTempDirectory("memory-foreign-")
        try {
            val storage = JsonFileStorage(root)
            val old = MemorySessionState(sessionId = "session", revision = 1)
            storage.saveEntity("memory_states", "session", old, MemorySessionState.serializer())
            val journal = MemoryCommitJournal(id = "journal", sessionId = "session", expectedStateRevision = 1,
                nextState = old.copy(revision = 2), nodes = listOf(MemoryNode("node", "other-session", MemoryTier.EPISODE)))
            storage.saveEntity("memory_commit_journals", "journal", journal, MemoryCommitJournal.serializer())
            assertFails { MemoryRepository(storage, strictReads = true).getState("session") }
            assertNotNull(storage.loadEntity("memory_commit_journals", "journal", MemoryCommitJournal.serializer()))
            assertEquals(old, storage.loadEntity("memory_states", "session", MemorySessionState.serializer()))
            assertNull(storage.loadEntity("memory_nodes", "node", MemoryNode.serializer()))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `memory publication holds real admission until entire journal transaction is finished`() = runBlocking {
        val root = Files.createTempDirectory("memory-gate-")
        val coordinator = DesktopDataOperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exclusiveEntered = CompletableDeferred<Unit>()
        val gate = object : AppDataOperationGate {
            override suspend fun <T> withNormalOperation(operation: suspend () -> T): T = coordinator.withNormalOperation {
                entered.complete(Unit); release.await(); operation()
            }
        }
        try {
            val storage = JsonFileStorage(root, coordinator)
            val repo = MemoryRepository(storage, strictReads = true, dataGate = gate)
            val next = MemorySessionState(sessionId = "session", revision = 1)
            val commit = async { repo.commitStateLast(0, next) }
            entered.await()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withExclusiveMaintenance {
                    exclusiveEntered.complete(Unit)
                    assertEquals(next, storage.readEntityStrict("memory_states", "session", MemorySessionState.serializer()).let {
                        assertIs<JsonFileStorage.EntityReadResult.Valid<MemorySessionState>>(it).value })
                    assertTrue(Files.list(root.resolve("entities/memory_commit_journals")).use { it.findAny().isEmpty })
                }
            }
            assertFalse(exclusiveEntered.isCompleted)
            release.complete(Unit); commit.await(); exclusive.join()
            assertTrue(exclusiveEntered.isCompleted)
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
        } finally { coordinator.closeAndDrain(); root.toFile().deleteRecursively() }
    }
}
