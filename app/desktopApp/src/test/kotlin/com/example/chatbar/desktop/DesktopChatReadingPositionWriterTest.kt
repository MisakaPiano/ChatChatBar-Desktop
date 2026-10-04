package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatScrollPosition
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatReadingPositionWriterTest {
    private fun position(id: String = "session", anchor: String = "a", timestamp: Long = 0) =
        ChatScrollPosition(id, anchor, 0, 12, timestamp)

    @Test fun `same millisecond snapshots have strictly increasing capturedAt`() = runTest {
        val saved = mutableListOf<ChatScrollPosition>()
        val writer = DesktopChatReadingPositionWriter({ null }, { saved += it }, { 100 }, StandardTestDispatcher(testScheduler))
        repeat(3) { writer.save(position(anchor = "$it")) }
        assertEquals(listOf(100L, 101L, 102L), saved.map { it.capturedAt })
        writer.close()
    }
    @Test fun `loading durable timestamp raises sequence above clock rollback`() = runTest {
        var saved: ChatScrollPosition? = null
        val writer = DesktopChatReadingPositionWriter({ position(timestamp = 900) }, { saved = it }, { 100 }, StandardTestDispatcher(testScheduler))
        writer.load("session")
        writer.save(position())
        assertEquals(901, saved?.capturedAt)
        writer.close()
    }
    @Test fun `writes serialize and drain waits for last accepted snapshot`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<ChatScrollPosition>()
        val writer = DesktopChatReadingPositionWriter({ null }, {
            if (it.anchorMessageId == "first") { entered.complete(Unit); release.await() }
            saved += it
        }, { 100 }, StandardTestDispatcher(testScheduler))
        val first = launch { writer.save(position(anchor = "first")) }
        entered.await()
        val second = launch { writer.save(position("other", "second")) }
        runCurrent()
        val drain = launch { writer.drain() }
        runCurrent()
        assertFalse(drain.isCompleted)
        assertTrue(saved.isEmpty())
        release.complete(Unit)
        joinAll(first, second, drain)
        assertEquals(listOf("first", "second"), saved.map { it.anchorMessageId })
        assertEquals(listOf("session", "other"), saved.map { it.sessionId })
        assertTrue(saved[1].capturedAt > saved[0].capturedAt)
        writer.close()
    }
    @Test fun `UI cancellation completes accepted write and close drains tracked worker`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved: ChatScrollPosition? = null
        val writer = DesktopChatReadingPositionWriter({ null }, { entered.complete(Unit); release.await(); saved = it },
            dispatcher = StandardTestDispatcher(testScheduler))
        val save = launch { writer.save(position()) }
        entered.await()
        save.cancel()
        val close = launch { writer.close() }
        runCurrent()
        assertFalse(close.isCompleted)
        release.complete(Unit)
        joinAll(save, close)
        assertNotNull(saved)
        assertTrue(save.isCancelled)
        assertNull(writer.save(position(anchor = "late")))
    }
    @Test fun `ordinary persistence failure is explicit and releases serialization lock`() = runTest {
        val failure = IllegalStateException("inline fixture")
        val writer = DesktopChatReadingPositionWriter({ null }, { throw failure }, dispatcher = StandardTestDispatcher(testScheduler))
        assertFailsWith<DesktopReadingPositionPersistenceException> { writer.save(position()) }
        assertFailsWith<DesktopReadingPositionPersistenceException> { writer.drain() }
        assertFailsWith<DesktopReadingPositionPersistenceException> { writer.close() }
    }

    @Test fun `synchronous submit coalesces newest pending snapshot without UI jobs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<ChatScrollPosition>()
        val writer = DesktopChatReadingPositionWriter({ null }, { if (it.anchorMessageId == "first") gate.await(); saved += it },
            dispatcher = StandardTestDispatcher(testScheduler))
        writer.submit(position(anchor = "first"))
        runCurrent()
        repeat(100) { writer.submit(position(anchor = "pending-$it")) }
        writer.submit(position("B", "other-session"))
        gate.complete(Unit)
        writer.closeAndDrain()
        assertEquals(listOf("first", "pending-99", "other-session"), saved.map { it.anchorMessageId })
        assertTrue(saved.zipWithNext().all { (a, b) -> a.capturedAt < b.capturedAt })
    }
}
