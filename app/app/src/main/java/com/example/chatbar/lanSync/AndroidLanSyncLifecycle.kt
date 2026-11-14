package com.example.chatbar.lanSync

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.util.Log
import java.util.Collections
import java.util.IdentityHashMap

/** Process-local wake hints only. The sync owner rechecks configuration, trust and actual routes. */
internal class AndroidLanSyncLifecycle(private val app: Application, private val foreground: () -> Unit,
    private val background: () -> Unit, private val networkAvailable: () -> Unit) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val started = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val resumed = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var networkRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (synchronized(lock) { !closed }) networkAvailable()
        }
    }
    private val activities = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) { }
        override fun onActivityStarted(activity: Activity) { synchronized(lock) { if (!closed) started += activity } }
        override fun onActivityResumed(activity: Activity) {
            val wake = synchronized(lock) { if (closed) false else { started += activity; resumed += activity; true } }
            if (wake) foreground()
        }
        override fun onActivityPaused(activity: Activity) { synchronized(lock) { resumed -= activity } }
        override fun onActivityStopped(activity: Activity) {
            val left = synchronized(lock) { resumed -= activity; started -= activity; !closed && started.isEmpty() }
            if (left) background()
        }
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) { }
        override fun onActivityDestroyed(activity: Activity) { synchronized(lock) { started -= activity; resumed -= activity } }
    }
    init { app.registerActivityLifecycleCallbacks(activities) }
    val isForeground: Boolean get() = synchronized(lock) { !closed && resumed.isNotEmpty() }
    /** Register network hints only after group evidence exists. Empty startup tracks visibility only. */
    fun observeNetwork() {
        val register = synchronized(lock) {
            if (closed || networkRegistered) false else { networkRegistered = true; true }
        }
        if (!register) return
        try {
            if (connectivity == null) error("SYNC_GROUP_NETWORK_OBSERVER_UNAVAILABLE")
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
            synchronized(lock) { networkRegistered = false }
            Log.w("CCBGroupSync", "SYNC_GROUP_NETWORK_OBSERVER_UNAVAILABLE")
            return
        }
        // Close can race registration; the registering thread must undo a late successful register.
        if (synchronized(lock) { closed }) try { connectivity?.unregisterNetworkCallback(networkCallback) }
        catch (_: Exception) { Log.w("CCBGroupSync", "SYNC_GROUP_NETWORK_OBSERVER_CLOSE_FAILED") }
    }
    override fun close() {
        val unregister = synchronized(lock) {
            if (closed) return
            closed = true; started.clear(); resumed.clear(); networkRegistered.also { networkRegistered = false }
        }
        app.unregisterActivityLifecycleCallbacks(activities)
        if (unregister) try { connectivity?.unregisterNetworkCallback(networkCallback) }
        catch (_: Exception) { Log.w("CCBGroupSync", "SYNC_GROUP_NETWORK_OBSERVER_CLOSE_FAILED") }
    }
}
