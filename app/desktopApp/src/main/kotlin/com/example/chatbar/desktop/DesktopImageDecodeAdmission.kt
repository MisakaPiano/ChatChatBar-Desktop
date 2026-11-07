package com.example.chatbar.desktop

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore

/** One gallery owns this budget. Full previews and other image owners remain independent. */
internal class DesktopImageDecodeAdmission(parallelism: Int = 2) {
    private val permits = Semaphore(parallelism)

    suspend fun <T> load(work: suspend Lease.() -> T): T {
        permits.acquire()
        val lease = Lease(permits)
        return try {
            currentCoroutineContext().ensureActive()
            lease.work()
        } finally { lease.release() }
    }

    internal class Lease(private val permits: Semaphore) {
        private var held = true

        /** Animation keeps its original timing without occupying a decode slot while sleeping. */
        suspend fun awaitFrame(durationMillis: Long) {
            check(held)
            release()
            delay(durationMillis)
            permits.acquire()
            held = true
            currentCoroutineContext().ensureActive()
        }

        internal fun release() {
            if (held) {
                held = false
                permits.release()
            }
        }
    }
}
