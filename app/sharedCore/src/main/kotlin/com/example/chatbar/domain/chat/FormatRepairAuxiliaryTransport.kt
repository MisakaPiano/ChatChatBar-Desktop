package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.addModelApiAuthorization
import com.example.chatbar.domain.prompt.*
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.BufferedSink

/** Internal short-clock seam; production uses 600 seconds of silence, never a total deadline. */
internal data class AuxiliaryStreamTiming(
    val readTimeoutMillis: Long = 600_000,
    val inactivityMillis: Long = 600_000,
    val finishGraceMillis: Long = 250,
    val pollMillis: Long = 200,
    val nanoTime: () -> Long = System::nanoTime,
) {
    init { require(readTimeoutMillis > 0 && inactivityMillis > 0 && finishGraceMillis >= 0 && pollMillis > 0) }
}

enum class AuxiliaryProtocolFailure { MALFORMED_SSE, EARLY_EOF, UNCONFIRMED_FINISH }
class AuxiliaryProtocolException(val failure: AuxiliaryProtocolFailure, cause: Throwable? = null) :
    RuntimeException("辅助文本流协议错误: $failure", cause)

internal fun OkHttpClient.forIsolatedRepair(timing: AuxiliaryStreamTiming): OkHttpClient = newBuilder()
    .readTimeout(timing.readTimeoutMillis, TimeUnit.MILLISECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .build()

/** FORMAT_REPAIR/REPAIR. This boundary alone owns assembly; callers cannot supply chat history. */
internal fun isolatedFormatRepairStream(
    client: OkHttpClient,
    allowCleartextHttp: Boolean,
    formatCard: String?,
    segmentedBubbleFormat: String?,
    message: String,
    model: ModelConfig,
    diagnostics: ProviderTransportDiagnostics,
    timing: AuxiliaryStreamTiming = AuxiliaryStreamTiming(),
 ): Flow<StreamEvent> = isolatedAuxiliaryTextStream(client, allowCleartextHttp, listOf(
    ChatApiMessage.text("system", FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim()),
    ChatApiMessage.text("user", FormatRepairPromptAuthority.messageFormatRepairUserPrompt(formatCard, segmentedBubbleFormat, message)),
), model, diagnostics, timing)

/** Shared isolated auxiliary transport. Callers own the task's frozen prompt assembly. */
internal fun isolatedAuxiliaryTextStream(
    client: OkHttpClient, allowCleartextHttp: Boolean, inputMessages: List<ChatApiMessage>, model: ModelConfig,
    diagnostics: ProviderTransportDiagnostics = ProviderTransportDiagnostics.NONE,
    timing: AuxiliaryStreamTiming = AuxiliaryStreamTiming(),
): Flow<StreamEvent> = callbackFlow {
    val messages = AuxiliaryMessageAssembler.assemble(inputMessages, preservesInputRoles = false)
    // Envelope fields belong to this task, never to custom model parameters. Keep the saved model untouched.
    val requestModel = model.copy(customParams = model.customParams.filterKeys {
        it !in setOf("messages", "model", "stream", "stream_options", "prompt_cache_key")
    })
    val body = OpenAiChatRequestSerializer.serialize(messages, requestModel, stream = true,
        allowCleartextHttp = allowCleartextHttp, disableThinking = true, isolatedTaskParameters = true,
        includeStreamUsage = model.supportsOpenAiPromptCacheInstrumentation())
    val url = "${model.baseUrl.trimEnd('/')}/chat/completions"
    diagnostics.onRequest(ProviderRequestEvidence(url, body))
    val jsonBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
    // Also prevents OkHttp's HTTP 503/Retry-After: 0 follow-up, independent of connection retries.
    val oneShotBody = object : RequestBody() {
        override fun contentType() = jsonBody.contentType()
        override fun contentLength() = jsonBody.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = jsonBody.writeTo(sink)
    }
    val request = Request.Builder().url(url).addModelApiAuthorization(model.apiKey)
        .header("Accept", "text/event-stream")
        .post(oneShotBody).build()
    val lock = Any()
    var terminal = false
    var finishReason: String? = null
    var refused = false
    var receivedReasoning = false
    val text = StringBuilder()
    var lastMeaningful = timing.nanoTime()
    var grace: Job? = null
    var watchdog: Job? = null

    // Every terminal path and callback is serialized. Cancellation is never a successful terminal event.
    fun terminate(source: EventSource, error: Throwable? = null) {
        if (terminal || !isActive) return
        terminal = true
        grace?.cancel()
        watchdog?.cancel()
        if (error == null) trySend(StreamEvent.Done)
        else trySend(StreamEvent.Error(error.message ?: "辅助请求失败", error.aiTaskFailureKind(), error))
        close()
        source.cancel()
    }
    fun complete(source: EventSource) {
        val failure = AiTaskRefusalPolicy.failure(text.toString(), finishReason, refused)
            ?: if (finishReason == "length") ModelResponseTruncatedException()
            else if (finishReason != null && finishReason != "stop") AuxiliaryProtocolException(AuxiliaryProtocolFailure.UNCONFIRMED_FINISH)
            else if (text.isBlank()) AiTaskEmptyResponseException(receivedReasoning)
            else null
        terminate(source, failure)
    }
    val listener = object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) = synchronized(lock) {
            if (terminal || !isActive) return@synchronized
            if (data.trim() == "[DONE]") {
                diagnostics.onResponseChunk(data)
                complete(eventSource)
                return@synchronized
            }
            val (chunk, usage) = try {
                OpenAiSseParser.parse(data) to OpenAiSseParser.parseUsage(data)
            } catch (error: Exception) {
                terminate(eventSource, AuxiliaryProtocolException(AuxiliaryProtocolFailure.MALFORMED_SSE, error))
                return@synchronized
            }
            diagnostics.onResponseChunk(data, chunk)
            finishReason = chunk.finishReason ?: finishReason
            refused = refused || chunk.refused
            if (!chunk.content.isNullOrBlank() || !chunk.reasoningContent.isNullOrBlank() || chunk.finishReason != null) {
                lastMeaningful = timing.nanoTime()
            }
            if (!chunk.reasoningContent.isNullOrBlank()) receivedReasoning = true
            // Reasoning is observed for timeout/empty classification, never emitted as repair body.
            chunk.content?.takeIf(String::isNotEmpty)?.let { text.append(it); trySend(StreamEvent.Delta(it)) }
            usage?.let { trySend(StreamEvent.Usage(it)) }
            if (refused || finishReason == "length") {
                complete(eventSource)
            } else if (chunk.finishReason != null && grace == null) {
                grace = launch {
                    delay(timing.finishGraceMillis)
                    synchronized(lock) { complete(eventSource) }
                }
            }
        }
        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) = synchronized(lock) {
            if (terminal || !isActive) return@synchronized
            // Even after finish_reason, an observed network failure cannot become Done.
            val error = AiTaskRefusalPolicy.failure(text.toString(), finishReason, refused)
                ?: ModelRequestException("辅助文本请求失败${response?.code?.let { " (HTTP $it)" }.orEmpty()}",
                    httpStatus = response?.code,
                    traceId = response?.header("x-request-id") ?: response?.header("x-trace-id"),
                    retryAfterMillis = response?.header("Retry-After")?.toLongOrNull()?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000),
                    cause = t)
            terminate(eventSource, error)
        }
        override fun onClosed(eventSource: EventSource) = synchronized(lock) {
            if (finishReason != null) complete(eventSource)
            else terminate(eventSource, AuxiliaryProtocolException(AuxiliaryProtocolFailure.EARLY_EOF))
        }
    }
    val source = EventSources.createFactory(client.forIsolatedRepair(timing)).newEventSource(request, listener)
    watchdog = launch {
        while (isActive) {
            delay(timing.pollMillis)
            synchronized(lock) {
                if (!terminal && timing.nanoTime() - lastMeaningful >= TimeUnit.MILLISECONDS.toNanos(timing.inactivityMillis)) {
                    terminate(source, SocketTimeoutException("辅助文本有效输出等待超时"))
                }
            }
        }
    }
    awaitClose {
        synchronized(lock) {
            if (!terminal) { terminal = true; diagnostics.onCancelled() }
            grace?.cancel()
            watchdog?.cancel()
            source.cancel()
        }
    }
}.buffer(Channel.UNLIMITED)
