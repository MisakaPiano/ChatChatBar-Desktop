package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.addModelApiAuthorization
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

data class ProviderCompletionMetadata(
    val finishReason: String? = null,
    val refused: Boolean = false,
    val transportFailed: Boolean = false,
)

sealed interface ProviderStreamEvent {
    data class ContentDelta(val text: String) : ProviderStreamEvent
    data class ReasoningDelta(val text: String) : ProviderStreamEvent
    data class Usage(val usage: PromptCacheUsage) : ProviderStreamEvent
    data class Completed(val metadata: ProviderCompletionMetadata) : ProviderStreamEvent
    data class Error(
        val message: String,
        val metadata: ProviderCompletionMetadata = ProviderCompletionMetadata(),
        val cause: Throwable? = null,
    ) : ProviderStreamEvent
}

data class ProviderRequestEvidence(
    val url: String,
    val body: String,
)

interface ProviderTransportDiagnostics {
    fun onRequest(request: ProviderRequestEvidence) = Unit
    fun onResponseChunk(data: String, parsed: OpenAiSseChunk? = null) = Unit
    fun onRetry(retryNumber: Int, message: String) = Unit
    fun onCancelled() = Unit

    companion object {
        val NONE: ProviderTransportDiagnostics = object : ProviderTransportDiagnostics {}
    }
}

class OpenAiStreamingTransport(
    private val allowCleartextHttp: () -> Boolean = { false },
    client: OkHttpClient? = null,
) {
    private val client = client ?: ProxyAwareClient.modelApiBuilder(allowCleartextHttp)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun streamMainChat(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        promptCacheKey: String? = null,
        maxTokens: Int? = null,
        diagnostics: ProviderTransportDiagnostics = ProviderTransportDiagnostics.NONE,
    ): Flow<ProviderStreamEvent> = callbackFlow {
        val supportsCacheInstrumentation = modelConfig.supportsOpenAiPromptCacheInstrumentation()
        val url = "${modelConfig.baseUrl.trimEnd('/')}/chat/completions"
        val requestBody = OpenAiChatRequestSerializer.serialize(
            messages = messages,
            modelConfig = modelConfig,
            stream = true,
            allowCleartextHttp = allowCleartextHttp(),
            maxTokens = maxTokens,
            promptCacheKey = promptCacheKey.takeIf { supportsCacheInstrumentation },
            includeStreamUsage = supportsCacheInstrumentation,
        )
        diagnostics.onRequest(ProviderRequestEvidence(url, requestBody))

        val maxRetries = 2
        var retryCount = 0
        var shouldStop = false
        val terminalSent = AtomicBoolean(false)

        while (!shouldStop && retryCount <= maxRetries) {
            var retrying = false
            val request = Request.Builder()
                .url(url)
                .addModelApiAuthorization(modelConfig.apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                val terminalDelivered = AtomicBoolean(false)
                val finishReasonObserved = AtomicBoolean(false)
                val completion = AtomicReference(ProviderCompletionMetadata())
                var finishReasonCompletionJob: Job? = null

                fun resumeAttempt() {
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }

                fun deliverTerminal(eventSource: EventSource, event: ProviderStreamEvent) {
                    if (!terminalDelivered.compareAndSet(false, true)) return
                    shouldStop = true
                    terminalSent.set(true)
                    trySend(event)
                    resumeAttempt()
                    eventSource.cancel()
                }

                val listener = object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (terminalDelivered.get()) return
                        if (data.trim() == "[DONE]") {
                            finishReasonCompletionJob?.cancel()
                            diagnostics.onResponseChunk(data)
                            deliverTerminal(eventSource, ProviderStreamEvent.Completed(completion.get()))
                            return
                        }

                        val chunk = try {
                            OpenAiSseParser.parse(data)
                        } catch (error: Exception) {
                            diagnostics.onResponseChunk(data)
                            deliverTerminal(
                                eventSource,
                                ProviderStreamEvent.Error(
                                    message = "解析 SSE 数据失败: ${error.message}",
                                    metadata = completion.get(),
                                    cause = error,
                                ),
                            )
                            return
                        }
                        completion.updateAndGet { previous ->
                            previous.copy(
                                finishReason = chunk.finishReason ?: previous.finishReason,
                                refused = previous.refused || chunk.refused,
                            )
                        }
                        diagnostics.onResponseChunk(data, chunk)
                        chunk.reasoningContent?.let { trySend(ProviderStreamEvent.ReasoningDelta(it)) }
                        chunk.content?.let { trySend(ProviderStreamEvent.ContentDelta(it)) }
                        OpenAiSseParser.parseUsage(data)?.let { trySend(ProviderStreamEvent.Usage(it)) }
                        if (chunk.finishReason != null && finishReasonObserved.compareAndSet(false, true)) {
                            finishReasonCompletionJob = launch {
                                delay(FINISH_REASON_GRACE_MILLIS)
                                deliverTerminal(eventSource, ProviderStreamEvent.Completed(completion.get()))
                            }
                        }
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        if (terminalDelivered.get()) {
                            resumeAttempt()
                            return
                        }
                        if (finishReasonObserved.get()) {
                            finishReasonCompletionJob?.cancel()
                            val failedCompletion = completion.updateAndGet { it.copy(transportFailed = true) }
                            deliverTerminal(eventSource, ProviderStreamEvent.Completed(failedCompletion))
                            return
                        }
                        val body = runCatching { response?.body?.string() }.getOrNull()
                        if (retryCount < maxRetries && response?.code == 400 && body?.contains("20015") == true) {
                            retrying = true
                            retryCount++
                            diagnostics.onRetry(
                                retryCount,
                                "[RETRY #$retryCount] 服务器返回 400/20015，${retryCount}秒后重试...",
                            )
                            resumeAttempt()
                            return
                        }
                        val message = buildString {
                            append("流式请求失败")
                            response?.let {
                                append(" (${it.code})")
                                if (!body.isNullOrBlank()) append(": $body")
                            }
                            t?.let { append(" - ${it.message}") }
                            if (retryCount > 0) append(" (已重试${retryCount}次)")
                        }
                        deliverTerminal(
                            eventSource,
                            ProviderStreamEvent.Error(message, completion.get(), t),
                        )
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (retrying || terminalDelivered.get()) {
                            resumeAttempt()
                            return
                        }
                        finishReasonCompletionJob?.cancel()
                        if (finishReasonObserved.get()) {
                            deliverTerminal(eventSource, ProviderStreamEvent.Completed(completion.get()))
                        } else {
                            deliverTerminal(
                                eventSource,
                                ProviderStreamEvent.Error(
                                    message = "流式连接已关闭，但未收到 finish_reason 或 [DONE]",
                                    metadata = completion.get(),
                                ),
                            )
                        }
                    }
                }

                val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
                continuation.invokeOnCancellation {
                    if (!terminalSent.get()) diagnostics.onCancelled()
                    eventSource.cancel()
                }
            }

            if (!shouldStop) delay(retryCount * 1_000L)
        }
        close()
    }.buffer(Channel.UNLIMITED)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 120L
        const val FINISH_REASON_GRACE_MILLIS = 250L
    }
}

fun ModelConfig.supportsOpenAiPromptCacheInstrumentation(): Boolean {
    val host = runCatching { URI(baseUrl).host }.getOrNull()
    return host.equals("api.openai.com", ignoreCase = true)
}
