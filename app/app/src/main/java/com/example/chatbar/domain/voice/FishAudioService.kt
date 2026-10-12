package com.example.chatbar.domain.voice

import com.example.chatbar.domain.ProxyAwareClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class FishAudioService(
    private val storage: FishAudioStorage,
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    private val ttsSlots = Semaphore(5)

    suspend fun listModels(
        apiKey: String,
        query: FishAudioModelQuery
    ): FishAudioModelPage = withContext(Dispatchers.IO) {
        val request = FishAudioRequestFactory.listModels(baseUrl, apiKey, query)
        execute(request) { body -> json.decodeFromString(FishAudioModelPage.serializer(), body) }
    }

    suspend fun synthesize(
        apiKey: String,
        modelId: String,
        referenceId: String,
        text: String,
        sessionId: String,
        voiceId: String,
        onProgress: (FishAudioDownloadProgress.Downloading) -> Unit = {}
    ): FishAudioStoredAudio = withContext(Dispatchers.IO) {
        ttsSlots.withPermit {
            val request = FishAudioRequestFactory.tts(
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
                referenceId = referenceId,
                text = text,
                json = json
            )
            val response = awaitResponse(request)
            response.use {
                coroutineContext.ensureActive()
                if (!response.isSuccessful) throw httpError(response.code, response.body?.string().orEmpty())
                val body = response.body ?: throw FishAudioApiException(response.code, "Fish Audio 未返回音频")
                storage.persistTtsResponse(sessionId, voiceId, body, onProgress)
            }
        }
    }

    suspend fun downloadPreview(
        apiKey: String,
        modelId: String,
        url: String
    ) = withContext(Dispatchers.IO) {
        val request = FishAudioRequestFactory.preview(url, apiKey)
        val response = awaitResponse(request, "音色预览下载失败")
        response.use {
            if (!response.isSuccessful) throw httpError(response.code, response.body?.string().orEmpty())
            storage.persistPreview(
                modelId,
                response.body ?: throw FishAudioApiException(response.code, "音色预览为空")
            )
        }
    }

    suspend fun synthesizePreview(
        apiKey: String,
        modelId: String,
        referenceId: String,
        text: String,
        previewSessionId: String
    ): File = withContext(Dispatchers.IO) {
        ttsSlots.withPermit {
            val request = FishAudioRequestFactory.tts(
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
                referenceId = referenceId,
                text = text,
                json = json
            )
            val response = awaitResponse(request)
            response.use {
                coroutineContext.ensureActive()
                if (!response.isSuccessful) throw httpError(response.code, response.body?.string().orEmpty())
                val body = response.body ?: throw FishAudioApiException(response.code, "Fish Audio 未返回音频")
                storage.persistGeneratedPreview(previewSessionId, referenceId, body)
            }
        }
    }

    fun clearGeneratedPreviews(previewSessionId: String): Boolean =
        storage.clearGeneratedPreviews(previewSessionId)

    private suspend fun <T> execute(request: Request, decode: (String) -> T): T {
        val response = awaitResponse(request)
        response.use {
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpError(response.code, body)
            return runCatching { decode(body) }.getOrElse { error ->
                throw FishAudioApiException(response.code, "Fish Audio 响应解析失败：${error.message}", error)
            }
        }
    }

    private suspend fun awaitResponse(
        request: Request,
        failurePrefix: String = "Fish Audio 请求失败"
    ): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isActive) return
                continuation.resumeWith(
                    Result.failure(
                        FishAudioApiException(
                            null,
                            "$failurePrefix：${e.message ?: e.javaClass.simpleName}",
                            e
                        )
                    )
                )
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                } else {
                    continuation.resumeWith(Result.success(response))
                }
            }
        })
    }

    private fun httpError(code: Int, body: String): FishAudioApiException {
        val detail = runCatching {
            json.decodeFromString(FishAudioError.serializer(), body).message
        }.getOrNull()?.takeIf(String::isNotBlank) ?: body.take(500).takeIf(String::isNotBlank)
        val summary = when (code) {
            401 -> "认证失败，请检查 API Key"
            402 -> "账户余额不足"
            403 -> "该音色不可用：当前 API Key 无权访问"
            404 -> "音色或接口不存在"
            422 -> "请求参数不合法"
            429 -> "并发或频率超过 Fish Audio 限制"
            in 500..599 -> "Fish Audio 服务暂不可用"
            else -> "请求失败"
        }
        return FishAudioApiException(code, "$summary（HTTP $code）${detail?.let { "：$it" }.orEmpty()}")
    }

    @Serializable
    private data class FishAudioError(
        val status: Int? = null,
        val message: String = ""
    )

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.fish.audio"
    }
}
