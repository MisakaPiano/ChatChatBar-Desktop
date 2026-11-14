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
            val wake = synchronized(lock) { if (closed) false else { started += activity; true } }
            if (wake) foreground()
        }
        override fun onActivityPaused(activity: Activity) { }
        override fun onActivityStopped(activity: Activity) {
            val left = synchronized(lock) { started -= activity; !closed && started.isEmpty() }
            if (left) background()
        }
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) { }
        override fun onActivityDestroyed(activity: Activity) { synchronized(lock) { started -= activity } }
    }
    init {
        app.registerActivityLifecycleCallbacks(activities)
        try {
            if (connectivity == null) Log.w("CCBGroupSync", "SYNC_GROUP_NETWORK_OBSERVER_UNAVAILABLE")
            else { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered = true }
        } catch (_: Exception) { Log.w("CCBGroupSync", "SYNC_GROUP_NETWORK_OBSERVER_UNAVAILABLE") }
    }
    override fun close() {
        val unregister = synchronized(lock) {
            if (closed) return
            closed = true; started.clear(); networkRegistered.also { networkRegistered = false }
        }
        app.unregisterActivityLifecycleCallbacks(activities)
        if (unregister) try { connectivity?.unregisterNetworkCallback(networkCallback) }
        catch (_: Exception) { Log.w("CCBGroupSync", "SYNC_GROUP_NETWORK_OBSERVER_CLOSE_FAILED") }
    }
}
