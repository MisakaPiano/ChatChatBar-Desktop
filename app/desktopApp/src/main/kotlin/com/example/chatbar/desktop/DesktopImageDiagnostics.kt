package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Original generation-call metadata only: no prompt, headers, images or encoded guidance. */
data class DesktopImageRequestEvidence(val attempt: Int, val method: String, val requestedImages: Long?,
    val httpStatus: Int? = null, val outputImages: Long? = null, val outputImageBytes: Long? = null,
    val operation: String = "NovelAI 生图",
    val requestedEncodings: Long? = null, val outputEncodingBytes: Long? = null)

internal suspend fun beginDesktopImageDiagnostics(owner: DesktopTransportDiagnosticsOwner?, secrets: DesktopSecretStore,
    token: String, model: String, count: Int,
    kind: DesktopUsageMediaKind = DesktopUsageMediaKind.NAI_IMAGE_GENERATION,
    diagnosticGroup: String? = null): DesktopImageDiagnosticRecorder? {
    if (owner == null) return null
    val coroutine = currentCoroutineContext()
    val context = coroutine[DesktopUsageParent]?.value ?: DesktopUsageContext(DesktopUsageSource.OTHER)
    val credential = coroutine[DesktopCredentialCall]?.takeIf {
        it.owner === secrets && it.credentialKey == DesktopCredentialKey.NovelAiToken
    }
    val group = diagnosticGroup ?: context.sessionId ?: context.editorSessionId ?: context.taskId ?: "image-generation:${UUID.randomUUID()}"
    return owner.beginNovelAiImages(group, model, token, credential?.accountId, context, count.toLong(), kind)
}

/** Observes each original newCall once; shared 429 retry and frame decoding remain the authority. */
internal class DesktopImageDiagnosticRecorder(
    private val owner: DesktopTransportDiagnosticsOwner,
    private val sessionId: String, private val model: String,
    private val scrubber: DesktopDiagnosticScrubber, private val clock: () -> Long,
    private val usage: DesktopUsageAttemptRecorder?, private val requestedImages: Long,
    private val kind: DesktopUsageMediaKind = DesktopUsageMediaKind.NAI_IMAGE_GENERATION,
) {
    private val lock = Any()
    private val id = UUID.randomUUID().toString()
    private var attempt = 0
    private var closed = false
    private var usageFailed = false
    private var images = 0L
    private var bytes = 0L
    private fun record(action: (DesktopUsageAttemptRecorder) -> Unit) {
        if (usageFailed || usage == null) return
        try { action(usage) } catch (_: Exception) {
            usageFailed = true
            owner.update(id) { it.copy(error = "图片用量未完成记录；原请求继续，未因此自动重放。") }
        }
    }
    fun client(original: OkHttpClient): OkHttpClient = original.newBuilder().addInterceptor { chain ->
        start(chain.request())
        chain.proceed(chain.request()).also { response(it.code) }
    }.build()

    private fun start(request: Request) = synchronized(lock) {
        if (closed) return@synchronized
        attempt++; images = 0; bytes = 0
        val evidence = DesktopImageRequestEvidence(attempt, request.method,
            requestedImages.takeUnless { kind == DesktopUsageMediaKind.NAI_VIBE_ENCODING }, operation = kind.label,
            requestedEncodings = requestedImages.takeIf { kind == DesktopUsageMediaKind.NAI_VIBE_ENCODING })
        val url = scrubber.url(request.url.toString())
        if (attempt == 1) owner.addMedia(DesktopTransportDiagnosticEntry(id, sessionId, "NovelAI · $model", model,
            clock(), requestAt = clock(), requestUrl = url, image = evidence))
        else owner.update(id) { it.copy(requestAt = clock(), requestUrl = url, image = evidence,
            retryEvents = (it.retryEvents + "网络尝试 $attempt").takeLast(DesktopTransportDiagnosticsOwner.MAX_RETRIES)) }
        record { it.attempt(attempt) }
    }
    private fun response(code: Int) = synchronized(lock) {
        if (closed) return@synchronized
        record { it.mediaResponse(code) }
        owner.update(id) { it.copy(image = it.image?.copy(httpStatus = code)) }
    }
    fun validatedImage(byteLength: Int) = synchronized(lock) {
        if (closed || attempt == 0) return@synchronized
        images = Math.addExact(images, 1L); bytes = Math.addExact(bytes, byteLength.toLong())
        record { it.localImages(images, bytes) }
        owner.update(id) { it.copy(image = it.image?.copy(outputImages = images, outputImageBytes = bytes)) }
    }
    fun finish(outcome: DesktopUsageOutcome) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        record { it.finish(outcome) }
        if (attempt > 0) owner.update(id) { it.copy(completedAt = clock(),
            status = when (outcome) {
                DesktopUsageOutcome.COMPLETED -> DesktopTaskStatus.COMPLETED
                DesktopUsageOutcome.CANCELLED -> DesktopTaskStatus.CANCELLED
                else -> DesktopTaskStatus.FAILED
            }, cancellationObserved = outcome == DesktopUsageOutcome.CANCELLED) }
    }
    fun validatedEncoding(byteLength: Int) = synchronized(lock) {
        if (closed || attempt == 0) return@synchronized
        record { it.localEncoding(byteLength.toLong()) }
        owner.update(id) { it.copy(image = it.image?.copy(outputEncodingBytes = byteLength.toLong())) }
    }
}
