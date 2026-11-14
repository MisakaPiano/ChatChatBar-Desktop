package com.example.chatbar.desktop

import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopLanSyncNetworkMonitorTest {
    /** Real Windows registration/initial callback/cancellation only; no interface or route is modified. */
    @Test fun windowsInitialNotificationAndCancellationUseTheActualIpHelper() = runBlocking {
        assumeTrue("Native IP interface notification requires Windows; NOT_TESTED elsewhere", Platform.isWindows())
        val wake = CompletableDeferred<Unit>()
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val monitor = DesktopLanSyncNetworkMonitor(onNetworkAvailable = { wake.complete(Unit) }, report = { errors += it })
        try {
            val observed = withTimeout(10_000) {
                monitor.state.first { it.nativeRegistered && it.initialNotificationReceived || it.error != null }
            }
            assertNull(observed.error)
            assertTrue(observed.nativeRegistered)
            assertTrue(observed.initialNotificationReceived, "InitialNotification confirms registration, not a real NIC change")
            withTimeout(10_000) { wake.await() }
        } finally { assertTrue(monitor.closeAndDrain()) }
        assertTrue(monitor.state.value.closed)
        assertFalse(monitor.state.value.nativeRegistered)
        assertTrue(errors.isEmpty())
    }

    @Test fun registrationErrorsAreVisibleWithoutInventingASuccessfulSubscription() = runBlocking {
        for (throwOnRegister in listOf(false, true)) {
            val api = FakeApi(registerResult = 5, throwOnRegister = throwOnRegister)
            val errors = Collections.synchronizedList(mutableListOf<String>())
            val wakes = AtomicInteger()
            val monitor = DesktopLanSyncNetworkMonitor({ wakes.incrementAndGet() }, { api }, { errors += it })
            try {
                val failed = withTimeout(5_000) { monitor.state.first { it.error != null } }
                assertEquals(DesktopLanSyncNetworkMonitor.REGISTER_FAILED, failed.error)
                assertFalse(failed.nativeRegistered)
                assertEquals(0, wakes.get())
            } finally {
                if (throwOnRegister) {
                    // Unknown completion conservatively retains the callback; this seam supplies no handle.
                    assertFalse(monitor.closeAndDrain())
                } else assertTrue(monitor.closeAndDrain())
            }
            assertEquals(1, api.registrations.get())
            assertEquals(0, api.cancellations.get())
            assertTrue(DesktopLanSyncNetworkMonitor.REGISTER_FAILED in errors)
        }
    }

    @Test fun conflatedHintsDrainBeforeCloseAndLateCallbacksCannotReachAnOwnerAfterFailedCancellation() = runBlocking {
        val api = FakeApi(cancelResult = 5)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deliveries = AtomicInteger()
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val monitor = DesktopLanSyncNetworkMonitor(onNetworkAvailable = {
            deliveries.incrementAndGet()
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
        }, apiFactory = { api }, report = { errors += it })
        try {
            withTimeout(5_000) { monitor.state.first { it.nativeRegistered } }
            val nativeThread = Thread { api.fire(3) }.apply { start(); join() }
            withTimeout(5_000) { entered.await() }
            repeat(100) { api.fire(0) }
            val close = async { monitor.closeAndDrain() }
            withTimeout(5_000) { api.cancelEntered.await() }
            assertFalse(close.isCompleted, "A live delivery must join before the app can close its root")
            release.complete(Unit)
            assertFalse(withTimeout(5_000) { close.await() })
            assertEquals(1, deliveries.get(), "Queued conflated hints are discarded during close")
            assertNotEquals(nativeThread.id, api.cancellationThread)
            assertTrue(monitor.state.value.closed)
            assertTrue(monitor.state.value.nativeRegistered, "Failed cancellation retains the same native subscription")
            assertEquals(DesktopLanSyncNetworkMonitor.CANCEL_FAILED, monitor.state.value.error)
            repeat(10) { api.fire(0) }
            assertEquals(1, deliveries.get())
            api.cancelResult = 0
            assertTrue(monitor.closeAndDrain())
            assertFalse(monitor.state.value.nativeRegistered)
            assertNull(monitor.state.value.error)
            assertEquals(1, api.registrations.get())
            assertEquals(2, api.cancellations.get())
            assertTrue(DesktopLanSyncNetworkMonitor.CANCEL_FAILED in errors)
        } finally {
            release.complete(Unit)
            api.cancelResult = 0
            monitor.closeAndDrain()
        }
    }

    @Test fun notificationBeforeRegistrationReturnsAndDeliveryFailureDoNotEscapeTheCallback() = runBlocking {
        val api = FakeApi(notifyDuringRegister = true)
        val monitor = DesktopLanSyncNetworkMonitor({ error("synthetic failed wake") }, { api }, {})
        try {
            val failed = withTimeout(5_000) { monitor.state.first { it.nativeRegistered && it.initialNotificationReceived && it.error != null } }
            assertTrue(failed.initialNotificationReceived)
            assertEquals(DesktopLanSyncNetworkMonitor.WAKE_FAILED, failed.error)
            assertEquals(1, api.registrations.get())
        } finally { assertTrue(monitor.closeAndDrain()) }
    }

    private class FakeApi(private val registerResult: Int = 0, private val throwOnRegister: Boolean = false,
        @Volatile var cancelResult: Int = 0, private val notifyDuringRegister: Boolean = false) : DesktopLanSyncIpHelperApi {
        val registrations = AtomicInteger()
        val cancellations = AtomicInteger()
        val cancelEntered = CompletableDeferred<Unit>()
        @Volatile var cancellationThread = -1L
        private lateinit var callback: DesktopLanSyncIpInterfaceCallback
        override fun NotifyIpInterfaceChange(family: Short, callback: DesktopLanSyncIpInterfaceCallback,
            context: Pointer?, initialNotification: Byte, handle: PointerByReference): Int {
            registrations.incrementAndGet()
            assertEquals(0.toShort(), family)
            assertEquals(1.toByte(), initialNotification)
            assertNull(context)
            this.callback = callback
            if (throwOnRegister) error("synthetic registration failed")
            if (registerResult == 0) handle.value = Pointer.createConstant(1234)
            if (notifyDuringRegister) {
                callback.invoke(null, null, 3)
                repeat(100) { callback.invoke(null, Pointer.createConstant(1), 0) }
            }
            return registerResult
        }
        override fun CancelMibChangeNotify2(handle: Pointer): Int {
            assertEquals(1234L, Pointer.nativeValue(handle))
            cancellationThread = Thread.currentThread().id
            cancellations.incrementAndGet()
            callback.invoke(null, Pointer.createConstant(1), 0) // The monitor must never dereference this row.
            cancelEntered.complete(Unit)
            return cancelResult
        }
        fun fire(kind: Int) { callback.invoke(null, if (kind == 3) null else Pointer.createConstant(1), kind) }
    }
}
