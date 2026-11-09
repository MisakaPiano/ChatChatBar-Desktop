package com.example.chatbar.desktop

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopUpdateWorkOwnershipTest {
    @Test fun `original updater job cancellation drains before terminal and stale stop cannot cancel the next maintenance`() = runBlocking {
        val root = Files.createTempDirectory("update-job-owner-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstEntered = CompletableDeferred<Unit>(); val nextEntered = CompletableDeferred<Unit>()
        val firstRelease = CountDownLatch(1); val nextRelease = CountDownLatch(1); val calls = AtomicInteger()
        try {
            val home = Files.createDirectories(root.resolve("program/versions/synthetic"))
            Files.writeString(home.parent.parent.resolve("install-owner.txt"), "ccb-desktop-nsis-v1")
            Files.writeString(home.resolve("install-manifest.json"), "{}")
            Files.createDirectories(home.resolve("app/desktop-updater")) // No executable: no process may run in this fixture.
            val updater = DesktopUpdateRuntime(scope, home, root.resolve("local"), proxySettings = {
                if (calls.incrementAndGet() == 1) { firstEntered.complete(Unit); check(firstRelease.await(10, TimeUnit.SECONDS)) }
                else { nextEntered.complete(Unit); check(nextRelease.await(10, TimeUnit.SECONDS)) }
                com.example.chatbar.domain.ApplicationProxySettings()
            })
            assertTrue(updater.supportsInstall)
            updater.cleanCache(null)
            withTimeout(5000) { firstEntered.await() }
            val original = updater.workRows.value.single()
            assertTrue(original.active); assertEquals(DesktopWorkKind.UPDATE_CACHE, original.kind)
            assertTrue(updater.stopTask(original.id))
            assertTrue(updater.workRows.value.single().stopRequested)
            firstRelease.countDown()
            withTimeout(5000) { updater.workRows.first { it.single().phase == DesktopWorkPhase.CANCELLED } }
            assertFalse(updater.state.value.busy)
            updater.cleanCache(null)
            withTimeout(5000) { nextEntered.await() }
            assertFalse(updater.stopTask(original.id)); assertTrue(updater.state.value.busy)
            nextRelease.countDown()
            val rows = withTimeout(5000) { updater.workRows.first { it.size == 2 && it.none { row -> row.active } } }
            assertEquals(DesktopWorkPhase.FAILED, rows.first().phase) // Exact missing helper refusal, not successful cleanup.
            assertEquals(DesktopWorkPhase.CANCELLED, rows.last().phase)
            assertFalse(Files.exists(root.resolve("local")))
        } finally {
            firstRelease.countDown(); nextRelease.countDown(); scope.coroutineContext[Job]!!.cancelAndJoin()
            root.toFile().deleteRecursively()
        }
    }
}
