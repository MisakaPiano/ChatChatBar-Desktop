package com.example.chatbar.domain.memory

import java.io.Closeable

/** Runtime-specific connection notifications and long-running task protection. */
interface MemoryMaintenancePlatform {
    fun onNetworkRestored(action: () -> Unit): Closeable = Closeable {}
    suspend fun <T> run(sessionId: String, requireValidatedInternet: Boolean, block: suspend () -> T): T
}

class MemoryBackgroundProtectionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
