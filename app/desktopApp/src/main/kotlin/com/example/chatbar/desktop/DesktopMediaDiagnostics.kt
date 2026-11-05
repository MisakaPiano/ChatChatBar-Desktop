package com.example.chatbar.desktop

import com.example.chatbar.domain.voice.FishAudioStoredAudio
import java.util.UUID
import okhttp3.Request

/** Actual transport metadata only. No headers, synthesis text, request body or media bytes. */
data class DesktopMediaRequestEvidence(val attempt: Int, val sentTextCodePoints: Long,
    val method: String, val httpStatus: Int? = null,
    val audioDurationMs: Long? = null, val audioBytes: Long? = null)

/** One logical TTS request; attempts are opened only at the original transport dispatch. */
internal class DesktopMediaDiagnosticRecorder(
    private val owner: DesktopTransportDiagnosticsOwner,
    private val sessionId: String,
    private val modelId: String,
    private val scrubber: DesktopDiagnosticScrubber,
    private val clock: () -> Long,
    private val usage: DesktopUsageAttemptRecorder?,
    private val sentCodePoints: Long,
) {
    private val lock = Any()
    private val id = UUID.randomUUID().toString()
    private var attempt = 0
    private var closed = false
    private var usageFailed = false
    private fun recordUsage(action: (DesktopUsageAttemptRecorder) -> Unit) {
        if (usageFailed || usage == null) return
        try { action(usage) }
        catch (_: Exception) {
            usageFailed = true
            owner.update(id) { it.copy(error = "媒体用量未完成记录；原请求继续，未因此自动重放。") }
        }
    }

    fun attemptStarted(request: Request) = synchronized(lock) {
        check(!closed)
        val number = ++attempt
        val evidence = DesktopMediaRequestEvidence(number, sentCodePoints, request.method)
        val url = scrubber.url(request.url.toString())
        if (number == 1) owner.addMedia(DesktopTransportDiagnosticEntry(id, sessionId, "Fish Audio · $modelId", modelId,
            clock(), requestAt = clock(), requestUrl = url, media = evidence))
        else owner.update(id) { it.copy(requestAt = clock(), requestUrl = url, media = evidence,
            retryEvents = (it.retryEvents + "网络尝试 $number").takeLast(DesktopTransportDiagnosticsOwner.MAX_RETRIES)) }
        recordUsage { it.attempt(number) }
    }

    fun response(code: Int) = synchronized(lock) {
        if (!closed) {
            recordUsage { it.mediaResponse(code) }
            owner.update(id) { entry -> entry.copy(media = entry.media?.copy(httpStatus = code),
                retryEvents = if (code >= 400) (entry.retryEvents + "尝试 $attempt · HTTP $code")
                    .takeLast(DesktopTransportDiagnosticsOwner.MAX_RETRIES) else entry.retryEvents) }
        }
    }

    fun audio(value: FishAudioStoredAudio) = audio(value.durationMs, value.byteLength)

    fun audio(durationMs: Long, byteLength: Long) = synchronized(lock) {
        if (closed) return@synchronized
        recordUsage { it.localAudio(durationMs, byteLength) }
        owner.update(id) { entry -> entry.copy(media = entry.media?.copy(audioDurationMs = durationMs, audioBytes = byteLength)) }
    }

    fun finish(status: DesktopTaskStatus) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        recordUsage { it.finish(when (status) {
            DesktopTaskStatus.COMPLETED -> DesktopUsageOutcome.COMPLETED
            DesktopTaskStatus.CANCELLED, DesktopTaskStatus.USER_STOPPED -> DesktopUsageOutcome.CANCELLED
            DesktopTaskStatus.FAILED -> DesktopUsageOutcome.FAILED
            DesktopTaskStatus.RUNNING -> DesktopUsageOutcome.RUNNING
        }) }
        if (attempt > 0) owner.update(id) { it.copy(status = status, completedAt = clock(),
            cancellationObserved = status == DesktopTaskStatus.CANCELLED || status == DesktopTaskStatus.USER_STOPPED) }
    }
}
