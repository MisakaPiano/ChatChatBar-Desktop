package com.example.chatbar.desktop

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopUpdateNoticesTest {
    private val installed = "0.1.0-preview.100"
    private val next = "0.1.0-preview.101"
    private fun result(version: String = next, newer: Boolean = true) = DesktopUpdateResult(
        DesktopRelease(version, null, "合成更新说明", "https://github.com/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/desktop-v$version", true), newer)

    @Test fun `startup checks once dismiss retains hint offline restart retains badge until current is confirmed`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val directory = Files.createTempDirectory("update-notice-")
        val hints = DesktopUpdateNoticeHints(directory)
        val calls = mutableListOf<Boolean>()
        var reply = result()
        val first = DesktopUpdateNotices(scope, installed, hints, true) { calls += it; reply }
        try {
            first.checkOnStartup()!!.join()
            assertEquals(listOf(true), calls)
            assertEquals(next, first.state.value.pendingVersion)
            assertTrue(first.state.value.showStartupNotice)
            assertTrue(desktopShowStartupUpdateNotice(first.state.value, blocked = false))
            assertFalse(desktopShowStartupUpdateNotice(first.state.value, blocked = true))
            first.dismissStartupNotice()
            assertFalse(first.state.value.showStartupNotice)
            assertEquals(next, hints.read())
            first.checkOnStartup()
            assertEquals(1, calls.size)
            first.closeAndDrain()

            val requested = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var offline = true
            val restarted = DesktopUpdateNotices(scope, installed, hints, true) {
                requested.complete(Unit); finish.await()
                if (offline) error("synthetic offline") else reply
            }
            restarted.checkOnStartup()
            requested.await()
            assertEquals(next, restarted.state.value.pendingVersion)
            assertNull(restarted.state.value.result, "A cached hint cannot become an installable release")
            assertFalse(restarted.state.value.showStartupNotice)
            finish.complete(Unit)
            restarted.check()!!.join()
            assertNotNull(restarted.state.value.error)
            assertEquals(next, restarted.state.value.pendingVersion)
            assertEquals(next, hints.read())
            offline = false; reply = result(installed, newer = false)
            restarted.check()!!.join()
            assertNull(restarted.state.value.pendingVersion)
            assertNull(hints.read())
            assertNull(restarted.state.value.error)
            restarted.closeAndDrain()
        } finally { first.closeAndDrain(); scope.cancel() }
    }

    @Test fun `development hosts do not check implicitly and manual checks do not create startup dialogs`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<Boolean>()
        val controller = DesktopUpdateNotices(scope, installed, null, false) { calls += it; result() }
        try {
            assertNull(controller.checkOnStartup()); assertTrue(calls.isEmpty())
            controller.setIncludePreview(false)
            controller.check()!!.join()
            assertEquals(listOf(false), calls)
            assertEquals(next, controller.state.value.pendingVersion)
            assertFalse(controller.state.value.showStartupNotice)
        } finally { controller.closeAndDrain(); scope.cancel() }
    }

    @Test fun `startup and manual overlap share one check and closing cancels its child before rejecting more work`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val controller = DesktopUpdateNotices(scope, installed, null, true) {
            calls++; entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        try {
            val manual = controller.check()!!
            entered.await()
            assertSame(manual, controller.checkOnStartup())
            assertSame(manual, controller.check())
            controller.setIncludePreview(false)
            assertTrue(controller.state.value.includePreview)
            controller.closeAndDrain()
            assertTrue(cancelled.isCompleted)
            assertFalse(controller.state.value.busy)
            controller.check(); controller.checkOnStartup()
            assertEquals(1, calls)
        } finally { controller.closeAndDrain(); scope.cancel() }
    }

    @Test fun `malformed hints are not a release and cache failures remain visible beside successful network checks`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("update-notice-invalid-")
        val file = directory.resolve("pending-notice.txt")
        val hints = DesktopUpdateNoticeHints(directory)
        Files.writeString(file, "x".repeat(161))
        assertFails { hints.read() }
        Files.writeString(file, "https://untrusted.invalid/installer.exe")
        assertFails { hints.read() }
        Files.delete(file); Files.createDirectory(file)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = DesktopUpdateNotices(scope, installed, hints, true) { result() }
        try {
            controller.checkOnStartup()!!.join()
            assertEquals(next, controller.state.value.pendingVersion)
            assertTrue(controller.state.value.showStartupNotice)
            assertNotNull(controller.state.value.result)
            assertNotNull(controller.state.value.cacheNotice)
            assertTrue(Files.isDirectory(file))
        } finally { controller.closeAndDrain(); scope.cancel() }
    }
}
