package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatScrollPosition
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** No worker/scope: callers await one bounded write. Switching/closing drains that same lock. */
internal class DesktopChatReadingPositionWriter(
    private val read: suspend (String) -> ChatScrollPosition?,
    private val write: suspend (ChatScrollPosition) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val sequence = AtomicLong()
    private var closed = false

    suspend fun load(sessionId: String): ChatScrollPosition? = lock.withLock {
        read(sessionId)?.also { position -> sequence.updateAndGet { maxOf(it, position.capturedAt) } }
    }

    suspend fun save(snapshot: ChatScrollPosition): ChatScrollPosition? {
        val position = snapshot.copy(capturedAt = sequence.updateAndGet { maxOf(it + 1, clock()) })
        // Only an accepted stable snapshot's single storage write is protected from UI disposal.
        return withContext(NonCancellable) {
            lock.withLock {
                if (closed) return@withLock null
                write(position)
                position
            }
        }
    }

    suspend fun drain() = lock.withLock { }
    suspend fun close() = lock.withLock { closed = true }
}
