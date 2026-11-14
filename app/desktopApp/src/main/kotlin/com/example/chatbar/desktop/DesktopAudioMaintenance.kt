package com.example.chatbar.desktop

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A retryable scheduler failure, never a successful/not-due backup result. */
internal class DesktopAudioMaintenanceBusyException : IllegalStateException(
    "音频正在使用或维护中；本次维护已延期，可稍后重试",
)

internal class DesktopAudioDrainException(cause: Throwable) : IllegalStateException(
    "音频排水未确认；未开始数据维护，原数据已保留，请重试排水或安全退出", cause,
)

/** One app-owned reservation over the existing formal/preview native output.
 * Reserve -> drain -> scheduler stop/join -> data exclusive; never hold the focus mutex while
 * joining scheduler/player. Automatic attempts fail immediately if the reservation/audio is busy.
 */
internal class DesktopAudioMaintenance(
    private val focus: () -> DesktopAudioFocus,
    private val stopPlayback: suspend () -> Unit,
) {
    fun isIdle(): Boolean = focus().isIdleForMaintenance()

    suspend fun <T> withMaintenance(
        interruptPlayback: Boolean,
        restartRequired: () -> Boolean,
        operation: suspend () -> T,
    ): T = reserved(interruptPlayback, restartRequired, false, operation)

    /** Source-only repair withdraws its target's queue through the voice owner. Other queued
     * sources keep their native generation token; actual start still waits on repair admission. */
    suspend fun <T> withIdleSourceMutation(restartRequired: () -> Boolean, operation: suspend () -> T): T =
        reserved(false, restartRequired, true, operation)

    private suspend fun <T> reserved(
        interruptPlayback: Boolean,
        restartRequired: () -> Boolean,
        preserveQueuedPlayback: Boolean,
        operation: suspend () -> T,
    ): T {
        val audio = focus()
        val token = audio.reserveMaintenance(interruptPlayback, preserveQueuedPlayback)
        var drained = !interruptPlayback // Idle reservation proves no owner and no uncertain release.
        try {
            if (interruptPlayback) {
                // Complete the real close handshake even if the initiating UI is cancelled.
                withContext(NonCancellable) {
                    try {
                        stopPlayback()
                        audio.drainMaintenance(token)
                        drained = true
                    } catch (failure: Throwable) { throw DesktopAudioDrainException(failure) }
                }
            }
            currentCoroutineContext().ensureActive()
            return operation()
        } finally {
            withContext(NonCancellable) {
                audio.releaseMaintenance(token, drained, restartRequired())
            }
        }
    }
}
