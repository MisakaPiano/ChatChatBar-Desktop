package com.example.chatbar.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal interface DesktopLanSyncIpInterfaceCallback : StdCallLibrary.StdCallCallback {
    fun invoke(context: Pointer?, row: Pointer?, notificationType: Int)
}

internal interface DesktopLanSyncIpHelperApi : StdCallLibrary {
    fun NotifyIpInterfaceChange(family: Short, callback: DesktopLanSyncIpInterfaceCallback,
        context: Pointer?, initialNotification: Byte, handle: PointerByReference): Int
    fun CancelMibChangeNotify2(handle: Pointer): Int
}

internal data class DesktopLanSyncNetworkMonitorState(
    val nativeRegistered: Boolean = false,
    val initialNotificationReceived: Boolean = false,
    val closed: Boolean = false,
    val error: String? = null,
)

/** Native callbacks post hints only. All application work and cancellation happen off the callback. */
internal class DesktopLanSyncNetworkMonitor(
    private val onNetworkAvailable: suspend () -> Unit,
    private val apiFactory: () -> DesktopLanSyncIpHelperApi = ::loadDesktopLanSyncIpHelper,
    private val report: (String) -> Unit = { System.err.println(it) },
) {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val hints = Channel<Unit>(Channel.CONFLATED)
    private val initialHints = Channel<Unit>(Channel.CONFLATED)
    private val closeAdmission = Mutex()
    private val mutableState = MutableStateFlow(DesktopLanSyncNetworkMonitorState())
    val state = mutableState.asStateFlow()
    @Volatile private var accepting = true
    private var registration: NativeRegistration? = null

    private val worker = scope.launch {
        while (isActive) {
            val initial = select<Boolean?> {
                initialHints.onReceiveCatching { if (it.isClosed) null else true }
                hints.onReceiveCatching { if (it.isClosed) null else false }
            } ?: break
            if (!accepting) continue
            if (initial)
                mutableState.update { it.copy(initialNotificationReceived = true) }
            if (accepting) try { onNetworkAvailable() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure(WAKE_FAILED) }
        }
    }
    private val registrationJob = scope.launch {
        if (!accepting) return@launch
        try {
            val api = apiFactory()
            val handle = PointerByReference()
            val native = NativeRegistration(api, callbackFor(initialHints, hints), handle)
            registration = native
            retainedRegistrations.add(native)
            val result = api.NotifyIpInterfaceChange(0.toShort(), native.callback, null, 1.toByte(), handle)
            if (result == 0 && handle.value != null) mutableState.update { it.copy(nativeRegistered = true) }
            else {
                if (handle.value == null) { retainedRegistrations.remove(native); registration = null }
                failure(REGISTER_FAILED)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure(REGISTER_FAILED) }
        catch (_: LinkageError) { failure(REGISTER_FAILED) }
    }

    private fun failure(code: String) {
        mutableState.update { it.copy(error = code) }
        runCatching { report(code) }
    }

    /** A failed passive unsubscribe retains only API/callback/mailbox, never application owners. */
    suspend fun closeAndDrain(): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        accepting = false
        closeAdmission.withLock {
            registrationJob.join()
            val native = registration
            val cancelled = if (native == null) true else {
                val handle = native.handle.value
                val result = try { if (handle == null) -1 else native.api.CancelMibChangeNotify2(handle) }
                catch (_: Exception) { -1 }
                catch (_: LinkageError) { -1 }
                if (result == 0) {
                    retainedRegistrations.remove(native)
                    registration = null
                    true
                } else { failure(CANCEL_FAILED); false }
            }
            hints.close()
            initialHints.close()
            worker.cancelAndJoin()
            owner.cancelAndJoin()
            mutableState.update { it.copy(nativeRegistered = if (cancelled) false else it.nativeRegistered, closed = true,
                error = if (cancelled && it.error == CANCEL_FAILED) null else it.error) }
            cancelled
        }
    }

    companion object {
        internal const val REGISTER_FAILED = "CCB_LAN_SYNC_NETWORK_MONITOR_REGISTER_FAILED"
        internal const val CANCEL_FAILED = "CCB_LAN_SYNC_NETWORK_MONITOR_CANCEL_FAILED"
        internal const val WAKE_FAILED = "CCB_LAN_SYNC_NETWORK_MONITOR_WAKE_FAILED"
        private const val MIB_INITIAL_NOTIFICATION = 3
        private class NativeRegistration(val api: DesktopLanSyncIpHelperApi,
            val callback: DesktopLanSyncIpInterfaceCallback, val handle: PointerByReference)
        // A native callback may outlive a failed close. This anchor captures no monitor/delivery closure.
        private val retainedRegistrations = ConcurrentHashMap.newKeySet<NativeRegistration>()
        private fun callbackFor(initial: Channel<Unit>, changes: Channel<Unit>) = object : DesktopLanSyncIpInterfaceCallback {
            override fun invoke(context: Pointer?, row: Pointer?, notificationType: Int) {
                if (notificationType == MIB_INITIAL_NOTIFICATION) initial.trySend(Unit) else changes.trySend(Unit)
            }
        }
    }
}

internal fun loadDesktopLanSyncIpHelper(): DesktopLanSyncIpHelperApi {
    check(Platform.isWindows()) { "CCB_LAN_SYNC_NETWORK_MONITOR_WINDOWS_REQUIRED" }
    return Native.load("iphlpapi", DesktopLanSyncIpHelperApi::class.java)
}
