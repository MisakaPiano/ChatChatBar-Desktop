package com.example.chatbar.domain.lanSync

import kotlinx.coroutines.*
import kotlin.test.*

class LanSyncOperationGateTest {
    @Test fun nestedAdmissionDrainsAndLockOrderViolationsFailBeforeWaiting() = runBlocking {
        withTimeout(5000) {
            val gate = LanSyncOperationGate()
            val entered = CompletableDeferred<Unit>()
            val attempt = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reader = async {
                gate.withNormalOperation {
                    gate.withNormalOperation { entered.complete(Unit) }
                    attempt.await()
                    assertEquals("SYNC_GATE_UPGRADE", assertFailsWith<IllegalArgumentException> {
                        gate.requireNoNormalAdmission()
                    }.message)
                    assertEquals("SYNC_GATE_UPGRADE", assertFailsWith<IllegalArgumentException> {
                        gate.exclusive { error("must refuse") }
                    }.message)
                    release.await()
                }
            }
            entered.await()
            val applied = CompletableDeferred<Unit>()
            val writer = async(start = CoroutineStart.UNDISPATCHED) { gate.exclusive { applied.complete(Unit) } }
            assertFalse(applied.isCompleted)
            attempt.complete(Unit)
            yield()
            assertFalse(applied.isCompleted)
            release.complete(Unit)
            reader.await(); writer.await()
            gate.requireNoNormalAdmission()
            gate.withNormalOperation { assertTrue(applied.isCompleted) }
        }
    }
}
