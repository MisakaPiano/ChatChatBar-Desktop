package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskFailureKind
import com.example.chatbar.domain.prompt.AiTaskMessageAssembler
import com.example.chatbar.domain.prompt.AiTaskRefusalPolicy
import com.example.chatbar.domain.prompt.AiTaskEmptyResponseException
import com.example.chatbar.domain.prompt.aiTaskFailureKind
import com.example.chatbar.utils.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.addModelApiAuthorization
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ========================= 数据模型 =========================

/**
 * SSE 流事件
 */
// ========================= 服务 =========================

/**
 * 流式聊天服务 — 通过 OkHttp SSE 与 OpenAI 兼容 API 通信
 *
 * 请求格式:
 * POST {baseUrl}/chat/completions
 * {"model": "...", "messages": [...], "stream": true, ...customParams}
 *
 * SSE 响应:
 * data: {"choices": [{"delta": {"content": "..."}}]}
 * data: [DONE]
 */
class StreamingChatService(
    private val allowCleartextHttp: () -> Boolean = { false }
) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 120L // SSE 需要较长读取超时
        private const val FINISH_REASON_GRACE_MILLIS = 250L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = ProxyAwareClient.modelApiBuilder(allowCleartextHttp)
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .build()

    private val sharedStreamingTransport = OpenAiStreamingTransport(allowCleartextHttp)

    /**
     * 流式聊天补全
     *
     * @param sessionId    会话 ID
     * @param messages    消息列表
     * @param modelConfig 模型配置
     * @param systemPrompt 组装后的 System Prompt
     * @param ragChunks    RAG 召回的知识块列表
     * @return 包含 [StreamEvent] 的 Flow
     */
    fun streamChat(
        sessionId: String,
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        systemPrompt: String = "",
        ragChunks: List<String> = emptyList(),
        promptCacheKey: String? = null,
        maxTokens: Int? = null,
        onReplyCompletion: (ChatReplyCompletion) -> Unit = {}
    ): Flow<StreamEvent> {
        return flow {
            var mainLogId: String? = null
            val diagnostics = object : ProviderTransportDiagnostics {
                override fun onRequest(request: ProviderRequestEvidence) {
                    mainLogId = DebugLogManager.startRequest(
                        sessionId = sessionId,
                        modelName = modelConfig.displayName,
                        apiUrl = request.url,
                        requestBodyJson = request.body,
                        systemPrompt = systemPrompt,
                        ragChunks = ragChunks,
                        secrets = listOf(modelConfig.apiKey),
                    )
                }

                override fun onResponseChunk(data: String, parsed: OpenAiSseChunk?) {
                    mainLogId?.let {
                        DebugLogManager.appendResponseChunk(it, data, parsed?.content, parsed?.reasoningContent)
                    }
                }

                override fun onRetry(retryNumber: Int, message: String) {
                    mainLogId?.let { DebugLogManager.appendResponseChunk(it, message) }
                }

                override fun onCancelled() {
                    mainLogId?.let { DebugLogManager.logError(it, "请求已取消", AiTaskFailureKind.CANCELLED) }
                }
            }

            sharedStreamingTransport.streamMainChat(
                messages = messages,
                modelConfig = modelConfig,
                promptCacheKey = promptCacheKey,
                maxTokens = maxTokens,
                diagnostics = diagnostics,
            ).collect { event ->
                emit(when (event) {
                    is ProviderStreamEvent.ContentDelta -> StreamEvent.Delta(event.text)
                    is ProviderStreamEvent.ReasoningDelta -> StreamEvent.ReasoningDelta(event.text)
                    is ProviderStreamEvent.Usage -> {
                        mainLogId?.let { DebugLogManager.recordPromptCacheUsage(it, event.usage) }
                        StreamEvent.Usage(event.usage)
                    }
                    is ProviderStreamEvent.Completed -> {
                        val completion = ChatReplyCompletion(
                            finishReason = event.metadata.finishReason,
                            refused = event.metadata.refused,
                            transportFailed = event.metadata.transportFailed,
                        )
                        onReplyCompletion(completion)
                        mainLogId?.let {
                            DebugLogManager.recordCompletion(it, completion.finishReason, completion.refused)
                            DebugLogManager.completeRequest(it)
                        }
                        StreamEvent.Done
                    }
                    is ProviderStreamEvent.Error -> {
                        val completion = ChatReplyCompletion(
                            finishReason = event.metadata.finishReason,
                            refused = event.metadata.refused,
                            transportFailed = event.metadata.transportFailed,
                        )
                        onReplyCompletion(completion)
                        mainLogId?.let {
                            DebugLogManager.recordCompletion(it, completion.finishReason, completion.refused)
                            DebugLogManager.logError(it, event.message)
                        }
                        StreamEvent.Error(event.message, cause = event.cause)
                    }
                })
            }
        }.buffer(Channel.UNLIMITED)
    }

    /** One HTTP request per collection. Task confirmation is assembled only at this boundary. */
    fun streamText(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        maxTokens: Int? = null,
        enableThinking: Boolean? = null,
        maxThinkingTokens: Int? = null,
        thinkingBudget: Int? = null,
        disableThinking: Boolean = false,
        readTimeoutSeconds: Long? = null,
        taskContext: AiTaskContext? = null,
        reasoningEffort: String? = null,
        isolatedTaskParameters: Boolean = false,
        responseFormatJson: Boolean = false
    ): Flow<StreamEvent> = callbackFlow {
        val context = taskContext?.forRequest()
        val actualMessages = context?.let { AiTaskMessageAssembler.assemble(messages, it) } ?: messages
        val url = "${modelConfig.baseUrl.trimEnd('/')}/chat/completions"
        val requestBody = buildRequestBody(
            messages = actualMessages,
            modelConfig = modelConfig,
            stream = true,
            maxTokens = maxTokens,
            enableThinkingOverride = enableThinking,
            maxThinkingTokens = maxThinkingTokens,
            thinkingBudget = thinkingBudget,
            disableThinking = disableThinking,
            reasoningEffortOverride = reasoningEffort,
            isolatedTaskParameters = isolatedTaskParameters,
            responseFormatJson = responseFormatJson,
            includeStreamUsage = modelConfig.supportsOpenAiPromptCacheInstrumentation()
        )
        val logId = DebugLogManager.startRequest(
            sessionId = context?.taskId ?: java.util.UUID.randomUUID().toString(),
            modelName = modelConfig.displayName, apiUrl = url, requestBodyJson = requestBody,
            systemPrompt = "", ragChunks = emptyList(), taskContext = context,
            confirmationText = context?.let { AiTaskMessageAssembler.addedText(messages, it) }.orEmpty(),
            secrets = listOf(modelConfig.apiKey)
        )
        val request = Request.Builder().url(url).addModelApiAuthorization(modelConfig.apiKey)
            .addHeader("Content-Type", "application/json").addHeader("Accept", "text/event-stream")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE)).build()
        val lock = Any()
        val progress = currentCoroutineContext()[AiStreamProgress]
        progress?.start(logId, context, modelConfig.displayName.ifBlank { modelConfig.modelName })
        val closed = AtomicBoolean(false)
        val text = StringBuilder()
        var finishReason: String? = null
        var refused = false
        var receivedReasoning = false
        var graceJob: Job? = null
        var inactivityJob: Job? = null
        var lastMeaningfulEvent = System.nanoTime()

        fun fail(eventSource: EventSource, error: Throwable) {
            if (!closed.compareAndSet(false, true)) return
            graceJob?.cancel()
            inactivityJob?.cancel()
            progress?.finish(logId, error.message ?: "请求失败")
            DebugLogManager.logError(logId, error.message ?: error::class.java.simpleName, error.aiTaskFailureKind())
            trySend(StreamEvent.Error(error.message ?: "AI 请求失败", error.aiTaskFailureKind(), error))
            close()
            eventSource.cancel()
        }

        fun complete(eventSource: EventSource) {
            synchronized(lock) {
                if (closed.get()) return
                val content = text.toString()
                val rejection = AiTaskRefusalPolicy.failure(content, finishReason, refused)
                if (rejection != null) {
                    fail(eventSource, rejection)
                    return
                }
                if (finishReason == "length") {
                    fail(eventSource, ModelResponseTruncatedException())
                    return
                }
                if (content.isBlank()) {
                    fail(eventSource, AiTaskEmptyResponseException(receivedReasoning))
                    return
                }
                if (!closed.compareAndSet(false, true)) return
                graceJob?.cancel()
                inactivityJob?.cancel()
                progress?.finish(logId, "输出完成")
                DebugLogManager.completeRequest(logId)
                trySend(StreamEvent.Done)
                close()
                eventSource.cancel()
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                synchronized(lock) {
                    if (closed.get()) return
                    if (data.trim() == "[DONE]") {
                        complete(eventSource)
                        return
                    }
                    val delta = try {
                        OpenAiSseParser.parse(data)
                    } catch (error: Exception) {
                        DebugLogManager.appendResponseChunk(logId, data)
                        fail(eventSource, IllegalArgumentException(
                            "解析 SSE 数据失败: ${error.message}\n原始数据: ${data.take(2000)}",
                            error
                        ))
                        return
                    }
                    finishReason = delta.finishReason ?: finishReason
                    refused = refused || delta.refused
                    if (!delta.content.isNullOrBlank() || !delta.reasoningContent.isNullOrBlank() || delta.finishReason != null) {
                        lastMeaningfulEvent = System.nanoTime()
                    }
                    progress?.append(logId, delta.reasoningContent, delta.content)
                    DebugLogManager.appendResponseChunk(logId, data, delta.content, delta.reasoningContent)
                    DebugLogManager.recordCompletion(logId, finishReason, refused)
                    OpenAiSseParser.parseUsage(data)?.let {
                        DebugLogManager.recordPromptCacheUsage(logId, it)
                        trySend(StreamEvent.Usage(it))
                    }
                    delta.reasoningContent?.takeIf(String::isNotBlank)?.let {
                        receivedReasoning = true
                        trySend(StreamEvent.ReasoningDelta(it))
                    }
                    delta.content?.takeIf(String::isNotEmpty)?.let {
                        text.append(it)
                        trySend(StreamEvent.Delta(it))
                    }
                    if (delta.finishReason != null && graceJob == null) {
                        // Some providers send usage after finish_reason. No extra request or retry.
                        graceJob = launch {
                            delay(FINISH_REASON_GRACE_MILLIS)
                            complete(eventSource)
                        }
                    }
                    // Explicit refusals without a finish reason are terminal as well.
                    if (refused && finishReason == null) complete(eventSource)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                synchronized(lock) {
                    if (closed.get()) return
                    val body = runCatching { response?.body?.string() }.getOrNull()
                    val rejection = AiTaskRefusalPolicy.failure(text.toString(), finishReason, refused)
                    fail(eventSource, rejection ?: ModelRequestException(
                        message = "流式文本补全失败${response?.code?.let { " ($it)" }.orEmpty()}: ${body?.take(2000) ?: t?.message.orEmpty()}",
                        httpStatus = response?.code,
                        traceId = response?.header("x-request-id") ?: response?.header("x-trace-id"),
                        retryAfterMillis = response?.header("Retry-After")?.toRetryAfterMillis(),
                        cause = t
                    ))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                synchronized(lock) {
                    if (closed.get()) return
                    if (finishReason != null) complete(eventSource)
                    else fail(eventSource, ModelRequestException("流式文本补全连接已关闭，但未收到 finish_reason 或 [DONE]"))
                }
            }
        }
        val requestClient = readTimeoutSeconds?.let { client.newBuilder().readTimeout(it, TimeUnit.SECONDS).build() } ?: client
        val eventSource = EventSources.createFactory(requestClient).newEventSource(request, listener)
        // Heartbeats/comments keep socket reads alive without advancing the model output.
        val inactivitySeconds = readTimeoutSeconds ?: READ_TIMEOUT
        inactivityJob = launch {
            while (!closed.get()) {
                delay(200)
                progress?.flush()
                synchronized(lock) {
                    if (!closed.get() && System.nanoTime() - lastMeaningfulEvent >= TimeUnit.SECONDS.toNanos(inactivitySeconds)) {
                        fail(eventSource, ModelRequestException("AI 已 ${inactivitySeconds} 秒未返回正文或思考内容，请重试或切换模型"))
                    }
                }
            }
        }
        awaitClose {
            graceJob?.cancel()
            inactivityJob.cancel()
            if (closed.compareAndSet(false, true)) {
                progress?.finish(logId, "已取消")
                DebugLogManager.logError(logId, "请求已取消", AiTaskFailureKind.CANCELLED)
            }
            eventSource.cancel()
        }
    }.buffer(Channel.UNLIMITED)

    suspend fun describeImage(imageBase64: String, modelConfig: ModelConfig): String =
        compactImageDescription(completeText(
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.IMAGE_DESCRIPTION_PROMPT),
                ChatApiMessage.withImage("user", "", imageBase64)
            ),
            modelConfig = modelConfig.forImageDescriptionRequest(),
            taskContext = AiTaskContext(AiTaskKind.IMAGE_DESCRIPTION)
        ))

    suspend fun describeImageStreaming(
        imageBase64: String,
        modelConfig: ModelConfig,
        onDelta: (String) -> Unit = {}
    ): String = compactImageDescription(completeTextStreaming(
        messages = listOf(
            ChatApiMessage.text("system", PromptTemplates.IMAGE_DESCRIPTION_PROMPT),
            ChatApiMessage.withImage("user", "", imageBase64)
        ),
        modelConfig = modelConfig.forImageDescriptionRequest(),
        onDelta = onDelta,
        taskContext = AiTaskContext(AiTaskKind.IMAGE_DESCRIPTION)
    ))

    suspend fun completeText(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        maxTokens: Int? = null,
        thinkingBudget: Int? = null,
        disableThinking: Boolean = false,
        isolatedTaskParameters: Boolean = false,
        responseFormatJson: Boolean = false,
        readTimeoutSeconds: Long? = null,
        taskContext: AiTaskContext? = null
    ): String {
        val context = taskContext?.forRequest()
        val actualMessages = context?.let { AiTaskMessageAssembler.assemble(messages, it) } ?: messages
        val url = "${modelConfig.baseUrl.trimEnd('/')}/chat/completions"
        val requestBody = buildRequestBody(
            messages = actualMessages, modelConfig = modelConfig, stream = false,
            maxTokens = maxTokens, thinkingBudget = thinkingBudget, disableThinking = disableThinking,
            isolatedTaskParameters = isolatedTaskParameters, responseFormatJson = responseFormatJson
        )
        val logId = DebugLogManager.startRequest(
            sessionId = context?.taskId ?: java.util.UUID.randomUUID().toString(),
            modelName = modelConfig.displayName, apiUrl = url, requestBodyJson = requestBody,
            systemPrompt = "", ragChunks = emptyList(), taskContext = context,
            confirmationText = context?.let { AiTaskMessageAssembler.addedText(messages, it) }.orEmpty(),
            secrets = listOf(modelConfig.apiKey)
        )
        try {
            val body = suspendCancellableCoroutine<String> { continuation ->
                val request = Request.Builder().url(url).addModelApiAuthorization(modelConfig.apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody(JSON_MEDIA_TYPE)).build()
                val requestClient = readTimeoutSeconds?.let { client.newBuilder().readTimeout(it, TimeUnit.SECONDS).build() } ?: client
                val call = requestClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(
                            ModelRequestException("文本补全请求失败: ${e.message}", cause = e)
                        )
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                val raw = response.body?.string().orEmpty()
                                if (!continuation.isActive) return
                                if (!response.isSuccessful) continuation.resumeWithException(ModelRequestException(
                                    "文本补全失败 (${response.code}): ${raw.take(2000)}",
                                    httpStatus = response.code,
                                    traceId = response.header("x-request-id") ?: response.header("x-trace-id"),
                                    retryAfterMillis = response.header("Retry-After")?.toRetryAfterMillis()
                                )) else continuation.resume(raw)
                            } catch (error: IOException) {
                                if (continuation.isActive) continuation.resumeWithException(
                                    ModelRequestException("读取文本补全响应失败: ${error.message}", cause = error)
                                )
                            }
                        }
                    }
                })
            }
            DebugLogManager.appendResponseChunk(logId, body)
            val finishReason = parseFinishReason(body)
            val refused = AiTaskRefusalPolicy.responseRefused(body)
            DebugLogManager.recordCompletion(logId, finishReason, refused)
            OpenAiSseParser.parseUsage(body)?.let { DebugLogManager.recordPromptCacheUsage(logId, it) }
            val content = OpenAiCompletionResponsePolicy.requireCompletion(body) {
                DebugLogManager.appendResponseChunk(logId, "", it)
            }
            DebugLogManager.completeRequest(logId)
            return content
        } catch (error: Throwable) {
            DebugLogManager.logError(logId, error.message ?: error::class.java.simpleName, error.aiTaskFailureKind())
            throw error
        }
    }

    suspend fun completeTextStreaming(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        maxTokens: Int? = null,
        enableThinking: Boolean? = null,
        maxThinkingTokens: Int? = null,
        thinkingBudget: Int? = null,
        reasoningEffort: String? = null,
        onDelta: (String) -> Unit = {},
        onReasoningDelta: (String) -> Unit = {},
        disableThinking: Boolean = false,
        isolatedTaskParameters: Boolean = false,
        responseFormatJson: Boolean = false,
        readTimeoutSeconds: Long? = null,
        taskContext: AiTaskContext? = null
    ): String {
        val text = StringBuilder()
        var completed = false
        streamText(
            messages = messages, modelConfig = modelConfig, maxTokens = maxTokens,
            enableThinking = enableThinking, maxThinkingTokens = maxThinkingTokens,
            thinkingBudget = thinkingBudget, disableThinking = disableThinking,
            readTimeoutSeconds = readTimeoutSeconds, taskContext = taskContext,
            reasoningEffort = reasoningEffort, isolatedTaskParameters = isolatedTaskParameters,
            responseFormatJson = responseFormatJson
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> { text.append(event.text); onDelta(event.text) }
                is StreamEvent.ReasoningDelta -> onReasoningDelta(event.text)
                is StreamEvent.Error -> throw event.asException()
                StreamEvent.Done -> completed = true
                is StreamEvent.Usage -> Unit
            }
        }
        check(completed) { "流式文本补全未正常完成" }
        return text.toString()
    }

    // ========================= 内部方法 =========================

    /** 构建请求 JSON body */
    internal fun buildRequestBody(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        stream: Boolean,
        maxTokens: Int? = null,
        enableThinkingOverride: Boolean? = null,
        maxThinkingTokens: Int? = null,
        thinkingBudget: Int? = null,
        reasoningEffortOverride: String? = null,
        promptCacheKey: String? = null,
        includeStreamUsage: Boolean = false,
        disableThinking: Boolean = false,
        isolatedTaskParameters: Boolean = false,
        responseFormatJson: Boolean = false
    ): String = OpenAiChatRequestSerializer.serialize(
            messages = messages,
            modelConfig = modelConfig,
            stream = stream,
            allowCleartextHttp = allowCleartextHttp(),
            maxTokens = maxTokens,
            enableThinkingOverride = enableThinkingOverride,
            maxThinkingTokens = maxThinkingTokens,
            thinkingBudget = thinkingBudget,
            reasoningEffortOverride = reasoningEffortOverride,
            promptCacheKey = promptCacheKey,
            includeStreamUsage = includeStreamUsage,
            disableThinking = disableThinking,
            isolatedTaskParameters = isolatedTaskParameters,
            responseFormatJson = responseFormatJson,
        )

    private fun compactImageDescription(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseFinishReason(body: String): String? = OpenAiCompletionResponsePolicy.finishReason(body)
}

private fun String.toRetryAfterMillis(): Long? = trim().toLongOrNull()?.times(1000L)
