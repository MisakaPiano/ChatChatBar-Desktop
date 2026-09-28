package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.addModelApiAuthorization
import com.example.chatbar.domain.prompt.ConnectionProbePromptAuthority
import com.example.chatbar.domain.rag.EmbeddingService
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

enum class ConnectionProbeStatus { NOT_CONFIGURED, SUCCESS, FAILED }
data class ConnectionProbeResult(val status: ConnectionProbeStatus, val error: String? = null)
data class ModelConnectionProbeResult(val chat: ConnectionProbeResult, val embedding: ConnectionProbeResult)

/** One ordered operation shared by Android and Desktop. No repositories, persistence or retries. */
class ModelConnectionProbe(private val allowCleartextHttp: Boolean) {
    suspend fun run(
        chat: ModelConfig?,
        embedding: EmbeddingConfig?,
        onChatResult: (ConnectionProbeResult) -> Unit = {},
        protect: suspend (suspend () -> Unit) -> Unit = { it() },
    ): ModelConnectionProbeResult {
        val secrets = listOfNotNull(chat?.apiKey, embedding?.apiKey).filter(String::isNotBlank)
        suspend fun probe(configured: Boolean, operation: suspend () -> Unit): ConnectionProbeResult {
            if (!configured) return ConnectionProbeResult(ConnectionProbeStatus.NOT_CONFIGURED)
            return try {
                protect(operation)
                ConnectionProbeResult(ConnectionProbeStatus.SUCCESS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val raw = if (failure is ModelRequestException && failure.isAuthenticationFailure)
                    "Authentication failed (HTTP ${failure.httpStatus})" else failure.message ?: "Request failed"
                val safe = secrets.fold(raw) { text, key -> ModelDiagnosticScrubber(key).text(text, Int.MAX_VALUE) }
                ConnectionProbeResult(ConnectionProbeStatus.FAILED, ModelDiagnosticScrubber("").text(safe, 512))
            }
        }
        val chatResult = probe(chat != null) { completeChat(requireNotNull(chat)) }
        onChatResult(chatResult)
        currentCoroutineContext().ensureActive()
        val embeddingResult = probe(embedding != null) {
            EmbeddingService { allowCleartextHttp }.getEmbedding(ConnectionProbePromptAuthority.EMBEDDING_INPUT, requireNotNull(embedding))
        }
        currentCoroutineContext().ensureActive()
        return ModelConnectionProbeResult(chatResult, embeddingResult)
    }

    private suspend fun completeChat(model: ModelConfig) {
        val body = OpenAiChatRequestSerializer.serialize(
            listOf(ChatApiMessage.text("user", ConnectionProbePromptAuthority.CHAT_INPUT)),
            model.withoutOutputTokenLimit(), stream = false,
            allowCleartextHttp = allowCleartextHttp, disableThinking = true,
        )
        val client = ProxyAwareClient.modelApiBuilder { allowCleartextHttp }
            .retryOnConnectionFailure(false)
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("${model.baseUrl.trimEnd('/')}/chat/completions")
            .addModelApiAuthorization(model.apiKey)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val raw = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ModelRequestException("文本补全请求失败: ${e.message}", cause = e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val text = it.body?.string().orEmpty()
                            if (!continuation.isActive) return
                            if (!it.isSuccessful) continuation.resumeWithException(ModelRequestException(
                                "文本补全失败 (${it.code}): ${text.take(2000)}", it.code,
                                it.header("x-request-id") ?: it.header("x-trace-id"),
                                it.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000L),
                            )) else continuation.resume(text)
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(ModelRequestException("读取文本补全响应失败", cause = error))
                        }
                    }
                }
            })
        }
        OpenAiCompletionResponsePolicy.requireCompletion(raw)
    }
}
