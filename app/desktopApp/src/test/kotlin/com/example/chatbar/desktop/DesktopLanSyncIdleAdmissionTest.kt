package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopLanSyncIdleAdmissionTest {
    @Test fun syncRefusesActiveWritersWithoutCancellingAndReservesNewAdmissionAtomically() = runBlocking {
        withTimeout(10000) {
            val parent = Files.createTempDirectory("lan-idle-admission-")
            val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
                DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
                secretStoreFactory = { InMemoryDesktopSecretStore() })
            val runtime = container.taskRuntime
            try {
                for (messageMutation in listOf(false, true)) {
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val finished = CompletableDeferred<Unit>()
                    val worker = async {
                        val work: suspend () -> Unit = { entered.complete(Unit); release.await(); finished.complete(Unit) }
                        if (messageMutation) runtime.withMessageMutation("synthetic-session", work)
                        else runtime.withCardDesign("synthetic-draft", work = work)
                    }
                    entered.await()
                    assertEquals("SYNC_BACKGROUND_WORK_BUSY", assertFailsWith<DesktopTaskAdmissionException> {
                        runtime.withIdleMaintenance { error("busy work must remain admitted") }
                    }.message)
                    assertTrue(worker.isActive)
                    assertFalse(finished.isCompleted)
                    release.complete(Unit); worker.await()
                    assertTrue(finished.isCompleted)
                }
                runtime.withIdleMaintenance {
                    assertFailsWith<DesktopTaskAdmissionException> { runtime.withMessageMutation("new") {} }
                    assertFailsWith<DesktopTaskAdmissionException> { runtime.withCardDesign("new") {} }
                    assertFailsWith<DesktopTaskAdmissionException> { runtime.withIdleMaintenance {} }
                }
                runtime.withCardDesign("after-sync") {}
                assertFailsWith<IllegalArgumentException> { runtime.withIdleMaintenance { throw IllegalArgumentException("synthetic") } }
                runtime.withMessageMutation("after-refusal") {}
            } finally { container.close(); parent.toFile().deleteRecursively() }
        }
    }
}
