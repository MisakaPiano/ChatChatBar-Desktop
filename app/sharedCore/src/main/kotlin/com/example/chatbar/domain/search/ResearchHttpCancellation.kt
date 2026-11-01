package com.example.chatbar.domain.search

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Response

/** Bind through headers and body processing, including cancellation before synchronous execute registers. */
@OptIn(InternalCoroutinesApi::class)
internal fun <T> Call.withResearchOwner(owner: Job?, consume: (Response) -> T): T {
    owner?.ensureActive()
    val handle = owner?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) cancel()
    }
    try {
        return execute().use(consume)
    } catch (error: Throwable) {
        owner?.ensureActive()
        throw error
    } finally { handle?.dispose() }
}
