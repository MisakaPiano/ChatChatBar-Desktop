package com.example.chatbar.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopChatDraftPersistenceTest {
    @Test
    fun `queued edits conflate by session and a late signal cannot write old text`() = runTest {
        val writes = mutableListOf<Pair<String, String>>()
        val writer = DesktopChatDraftPersistence({ id, text -> writes += id to text }, StandardTestDispatcher(testScheduler))
        writer.submit("A", "a")
        writer.submit("A", "ab")
        val latest = writer.submit("A", "abc")
        writer.submit("B", "independent")
        assertTrue(writes.isEmpty())
        runCurrent()
        writer.await(latest)
        assertEquals(listOf("A" to "abc", "B" to "independent"), writes)
        writer.closeAndDrain()
    }

    @Test
    fun `admitted clear supersedes pending text and follows an already in-flight write`() = runTest {
        val writes = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val writer = DesktopChatDraftPersistence({ _, text ->
            if (text == "in-flight") release.await()
            writes += text
        }, StandardTestDispatcher(testScheduler))
        writer.submit("A", "in-flight")
        runCurrent()
        val queued = writer.submit("A", "sent text")
        val clear = writer.clearIfCurrent("A", queued)
        assertTrue(writes.isEmpty())
        release.complete(Unit)
        writer.await(clear)
        writer.closeAndDrain()
        assertEquals(listOf("in-flight", ""), writes)
    }

    @Test
    fun `send clear does not discard a newer edit`() = runTest {
        val writes = mutableListOf<String>()
        val writer = DesktopChatDraftPersistence({ _, text -> writes += text }, StandardTestDispatcher(testScheduler))
        val sent = writer.submit("A", "sent")
        writer.submit("A", "next message")
        assertNull(writer.clearIfCurrent("A", sent))
        writer.closeAndDrain()
        assertEquals(listOf("next message"), writes)
    }

    @Test
    fun `write failure is sanitized observable and joined before storage closes`() = runTest {
        val events = mutableListOf<String>()
        val writer = DesktopChatDraftPersistence(
            write = { _, _ -> events += "write"; error("private text and fake credential") },
            dispatcher = StandardTestDispatcher(testScheduler),
            onResult = { _, successful -> assertFalse(successful); events += "failure" },
        )
        val revision = writer.submit("A", "private text")
        val failure = assertFailsWith<DesktopDraftPersistenceException> { writer.await(revision) }
        assertFalse(failure.toString().contains("private"))
        assertNull(failure.cause)
        assertFailsWith<DesktopDraftPersistenceException> {
            closeDesktopDataRuntimes(
                taskRuntimeClose = { events += "tasks" },
                draftRuntimeClose = { writer.closeAndDrain() },
                runtimeClose = { events += "backup" },
                coordinatorClose = { events += "storage" },
                migrationServiceClose = { events += "migration" },
            )
        }
        runCurrent()
        assertEquals(listOf("write", "failure", "tasks", "backup", "storage", "migration"), events)
        assertFailsWith<IllegalStateException> { writer.submit("A", "too late") }
    }

    @Test
    fun `draft timeout leaves storage open and a retry joins the original writer`() = runTest {
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val writer = DesktopChatDraftPersistence({ _, _ ->
            release.await()
            events += "written"
        }, StandardTestDispatcher(testScheduler))
        writer.submit("A", "last edit")
        assertFailsWith<DesktopDraftDrainTimeoutException> {
            closeDesktopDataRuntimes(
                taskRuntimeClose = { events += "tasks" },
                draftRuntimeClose = { writer.closeAndDrain(timeoutMillis = 50) },
                runtimeClose = { events += "backup" },
                coordinatorClose = { events += "storage" },
                migrationServiceClose = { events += "migration" },
            )
        }
        assertEquals(listOf("tasks"), events)
        release.complete(Unit)
        closeDesktopDataRuntimes(
            draftRuntimeClose = { writer.closeAndDrain() },
            runtimeClose = { events += "backup" },
            coordinatorClose = { events += "storage" },
            migrationServiceClose = { events += "migration" },
        )
        assertEquals(listOf("tasks", "written", "backup", "storage", "migration"), events)
    }

    @Test
    fun `task timeout still stops shutdown before draft and data closes`() = runTest {
        val events = mutableListOf<String>()
        assertFailsWith<DesktopTaskDrainTimeoutException> {
            closeDesktopDataRuntimes(
                taskRuntimeClose = { events += "tasks"; throw DesktopTaskDrainTimeoutException("pending") },
                draftRuntimeClose = { events += "drafts" },
                runtimeClose = { events += "backup" },
                coordinatorClose = { events += "storage" },
                migrationServiceClose = { events += "migration" },
            )
        }
        assertEquals(listOf("tasks"), events)
    }
}
