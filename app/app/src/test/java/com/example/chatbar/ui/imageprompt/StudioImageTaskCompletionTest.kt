package com.example.chatbar.ui.imageprompt

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioImageTaskCompletionTest {
    @Test
    fun `cancelled generation publishes terminal state after IO cleanup`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                finishStudioImageFailure(cleanup = { events += "cleaned" }) { error ->
                    assertNull(error)
                    events += "cancelled"
                }
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf("cleaned", "cancelled"), events)
    }

    @Test
    fun `cleanup failure cannot strand cancelled generation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val failure = IOException("disk failure")
        var published = false
        var reported: Throwable? = null
        val job = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                finishStudioImageFailure(cleanup = { throw failure }) { error ->
                    reported = error
                    published = true
                }
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(published)
        // Coroutine stack-trace recovery may copy exceptions across dispatcher boundaries.
        assertEquals(failure.javaClass, reported?.javaClass)
        assertEquals(failure.message, reported?.message)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `ordinary failure also publishes after cleanup`() = runBlocking {
        var cleaned = false
        var published = false
        finishStudioImageFailure(cleanup = { cleaned = true }) { error ->
            assertNull(error)
            assertTrue(cleaned)
            published = true
        }
        assertTrue(published)
    }
}
