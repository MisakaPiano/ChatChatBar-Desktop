package com.example.chatbar.domain.voice

import com.example.chatbar.domain.chat.ModelRequestException
import com.example.chatbar.domain.chat.ModelResponseTruncatedException
import com.example.chatbar.domain.prompt.AiTaskEmptyResponseException
import com.example.chatbar.domain.prompt.rethrowIfAiTaskTerminalFailure
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** Remove only an unambiguous complete Markdown envelope; never salvage partial JSON. */
internal object VoiceJsonEnvelope {
    fun unwrap(raw: String): String {
        val text = raw.trim().removePrefix("\uFEFF").trim()
        val fence = when {
            text.startsWith("```") -> "```"
            text.startsWith("~~~") -> "~~~"
            else -> return text
        }
        if (!text.endsWith(fence) || text.length < fence.length * 2) return text
        var body = text.substring(fence.length, text.length - fence.length).trim()
        if (body.startsWith("json", ignoreCase = true)) body = body.substring(4).trimStart()
        // Strict decoding still rejects multiple objects, trailing prose and incomplete payloads.
        return body.takeIf { it.startsWith('{') && it.endsWith('}') } ?: text
    }
}

internal object VoiceRetryPolicy {
    fun isRejectedTtsRetryable(status: Int?): Boolean =
        status == 408 || status == 425 || status == 429 || (status != null && status in 500..599)

    fun delayMillis(failedAttempt: Int, retryAfterMillis: Long?): Long =
        maxOf(1_000L shl (failedAttempt - 1).coerceIn(0, 5), retryAfterMillis ?: 0L) +
            Random.nextLong(0L, 501L)

    fun retryAfterMillis(header: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val value = header?.trim() ?: return null
        value.toLongOrNull()?.let { seconds ->
            return seconds.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
        }
        return runCatching {
            val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("GMT")
            (requireNotNull(parser.parse(value)).time - nowMillis).coerceAtLeast(0L)
        }.getOrNull()
    }
}

internal class VoiceOutputException(message: String) : IllegalStateException(message)

/** Feature-owned retry budget, independent of request transport and JSON validation. */
internal suspend fun <T> retryVoiceAuxiliary(
    onRetry: (Exception, Long) -> Unit,
    request: suspend () -> T
): T {
    var transportFailures = 0
    var outputFailures = 0
    while (true) {
        try {
            return request()
        } catch (error: Exception) {
            error.rethrowIfAiTaskTerminalFailure()
            val waitMillis = when (error) {
                is ModelRequestException -> {
                    transportFailures++
                    if (!error.isRetryable || transportFailures >= 3) throw error
                    VoiceRetryPolicy.delayMillis(transportFailures, error.retryAfterMillis)
                }
                is VoiceOutputException, is ModelResponseTruncatedException, is AiTaskEmptyResponseException -> {
                    outputFailures++
                    if (outputFailures >= 2) throw error
                    1_000L
                }
                else -> throw error
            }
            onRetry(error, waitMillis)
            delay(waitMillis)
        }
    }
}

internal suspend fun <T> retryRejectedVoiceTts(
    onRetry: (FishAudioApiException, Int, Long) -> Unit,
    request: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        attempt++
        try {
            return request()
        } catch (error: FishAudioApiException) {
            error.rethrowIfAiTaskTerminalFailure()
            if (attempt >= 3 || !VoiceRetryPolicy.isRejectedTtsRetryable(error.statusCode)) throw error
            val waitMillis = VoiceRetryPolicy.delayMillis(attempt, error.retryAfterMillis)
            onRetry(error, attempt, waitMillis)
            delay(waitMillis)
        }
    }
}
