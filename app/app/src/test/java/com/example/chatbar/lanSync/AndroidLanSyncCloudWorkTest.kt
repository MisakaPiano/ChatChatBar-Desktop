package com.example.chatbar.lanSync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidLanSyncCloudWorkTest {
    @Test
    fun latestNotificationCanStopDuringReleaseAndAfterDestructionUntilANewerStartIsIssued() = runTest {
        val starts = mutableListOf<Long>()
        val stopRequests = mutableListOf<Long>()
        val workers = mutableListOf<Deferred<Boolean>>()
        val gate = AndroidLanSyncCloudWork(applicationScope = backgroundScope,
            canStart = { true }, canContinue = { true }, cancelCloud = {},
            startService = { starts += it })
        val firstResult = CompletableDeferred<Boolean>()
        val secondResult = CompletableDeferred<Boolean>()
        try {
            assertFalse(gate.acceptsStop(1))
            val first = async { gate.run { firstResult.await() } }.also { workers += it }
            runCurrent()
            val firstGeneration = starts.single()
            assertFalse(gate.owns(firstGeneration))
            gate.promoted(firstGeneration) { stopRequests += firstGeneration }
            runCurrent()
            assertTrue(gate.allowed)
            assertTrue(gate.owns(firstGeneration))

            firstResult.complete(true)
            runCurrent()
            // The real worker completed and requested service stop; its onDestroy is still held.
            assertEquals(listOf(firstGeneration), stopRequests)
            assertFalse(first.isCompleted)
            assertFalse(gate.allowed)
            assertFalse(gate.owns(firstGeneration))
            assertTrue(gate.acceptsStop(firstGeneration))

            gate.destroyed(firstGeneration, expected = true)
            runCurrent()
            assertTrue(first.await())
            // A pending notification click can arrive even after active has been cleared.
            assertFalse(gate.owns(firstGeneration))
            assertTrue(gate.acceptsStop(firstGeneration))

            val second = async { gate.run { secondResult.await() } }.also { workers += it }
            runCurrent()
            assertEquals(2, starts.size)
            val secondGeneration = starts.last()
            assertNotEquals(firstGeneration, secondGeneration)
            // Issuing a newer start retires old notification intent before the new promotion.
            assertFalse(gate.acceptsStop(firstGeneration))
            assertTrue(gate.acceptsStop(secondGeneration))
            assertFalse(gate.owns(secondGeneration))
            gate.promoted(secondGeneration) { stopRequests += secondGeneration }
            runCurrent()
            assertTrue(gate.owns(secondGeneration))
            secondResult.complete(true)
            runCurrent()
            assertEquals(listOf(firstGeneration, secondGeneration), stopRequests)
            assertTrue(gate.acceptsStop(secondGeneration))
            gate.destroyed(secondGeneration, expected = true)
            runCurrent()
            assertTrue(second.await())

            gate.closeAndDrain()
            assertFalse(gate.acceptsStop(firstGeneration))
            assertFalse(gate.acceptsStop(secondGeneration))
        } finally {
            workers.forEach { it.cancel() }
            starts.forEach { gate.destroyed(it, expected = true) }
            gate.closeAndDrain()
        }
    }
}
