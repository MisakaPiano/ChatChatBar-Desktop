package com.example.chatbar.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopSynchronousEditorAdmissionTest {
    @Test fun `short synchronous file work delays exclusive entry and later callbacks refuse pending and closed states`() = runBlocking<Unit> {
        val gate = DesktopDataOperationCoordinator()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val exclusiveEntered = CompletableDeferred<Unit>(); val exclusiveRelease = CompletableDeferred<Unit>()
        val work = async(Dispatchers.Default) { gate.withSynchronousNormalOperation { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        var maintenance: Job? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            maintenance = launch { gate.withExclusiveMaintenance { exclusiveEntered.complete(Unit); exclusiveRelease.await() } }
            withTimeout(5000) { while (gate.state != DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING) yield() }
            assertFalse(exclusiveEntered.isCompleted)
            assertFailsWith<DesktopDataOperationRejectedException> { gate.withSynchronousNormalOperation { fail("must not enter") } }
            release.countDown(); work.await(); exclusiveEntered.await()
            assertFailsWith<DesktopDataOperationRejectedException> { gate.withSynchronousNormalOperation { fail("must not enter") } }
            exclusiveRelease.complete(Unit); maintenance.join()
            assertEquals(42, gate.withSynchronousNormalOperation { 42 })
            gate.closeAndDrain()
            assertFailsWith<DesktopDataOperationRejectedException> { gate.withSynchronousNormalOperation { fail("must not enter") } }
        } finally { release.countDown(); exclusiveRelease.complete(Unit); maintenance?.join(); work.join() }
    }
}
