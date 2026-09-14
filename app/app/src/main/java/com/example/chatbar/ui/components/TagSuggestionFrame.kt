package com.example.chatbar.ui.components

import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Publish at the next drawable frame, not a debounce timer or a result-count threshold. */
internal suspend fun awaitTagSuggestionFrame() = withContext(Dispatchers.Main.immediate) {
    suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback {
            if (continuation.isActive) continuation.resume(Unit)
        }
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
        choreographer.postFrameCallback(callback)
    }
}
