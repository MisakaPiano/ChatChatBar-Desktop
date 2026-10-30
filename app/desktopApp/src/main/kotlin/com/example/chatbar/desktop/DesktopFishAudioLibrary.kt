package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.desktop.security.credentialForCall
import com.example.chatbar.desktop.security.DesktopCredentialCall
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.voice.FishAudioApiException
import com.example.chatbar.domain.voice.FishAudioModelPage
import com.example.chatbar.domain.voice.FishAudioModelQuery
import com.example.chatbar.domain.voice.FishAudioRequestFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response

/** Platform credential adapter. No token enters an Entity, library result or UI state. */
internal class DesktopFishAudioCredentials(private val secrets: DesktopSecretStore) {
    fun load(): String? = secrets.load(DesktopCredentialKey.FishAudioApiKey)?.takeIf(String::isNotBlank)

    fun save(value: String) {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Fish Audio API Key 不能为空" }
        secrets.save(DesktopCredentialKey.FishAudioApiKey, normalized)
    }

    fun clear() = secrets.delete(DesktopCredentialKey.FishAudioApiKey)
    suspend fun forCurrentCall(): String? {
        return secrets.credentialForCall(DesktopCredentialKey.FishAudioApiKey)
    }
    fun captureCall(): DesktopCredentialCall = (secrets as? com.example.chatbar.desktop.security.DesktopAccountStore)
        ?.captureCall(DesktopCredentialKey.FishAudioApiKey, com.example.chatbar.desktop.security.DesktopAccountService.FISH)
        ?: DesktopCredentialCall(secrets, DesktopCredentialKey.FishAudioApiKey, "legacy", load())
}

/** Catalog only: querying or binding a voice must never implicitly generate a paid preview. */
internal class DesktopFishAudioLibrary(
    private val credentials: DesktopFishAudioCredentials,
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val baseUrl: String = "https://api.fish.audio",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lifecycle = Any()
    private var closed = false
    private val calls = mutableMapOf<Call, kotlinx.coroutines.CompletableDeferred<Unit>>()

    suspend fun closeAndDrain() = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        val pending = synchronized(lifecycle) {
            closed = true
            calls.map { (call, done) -> call.cancel(); done }
        }
        pending.forEach { it.await() }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        check(client.dispatcher.executorService.awaitTermination(30, TimeUnit.SECONDS)) { "Fish 音色目录尚未安全关闭" }
    }


    suspend fun listModels(query: FishAudioModelQuery): FishAudioModelPage = withContext(Dispatchers.IO) {
        val key = try {
            credentials.load() ?: throw FishAudioApiException(null, "Fish Audio API Key 未配置")
        } catch (error: FishAudioApiException) {
            throw error
        } catch (_: Exception) {
            throw FishAudioApiException(null, "Fish Audio 安全存储不可用")
        }
        try {
            val request = FishAudioRequestFactory.listModels(baseUrl, key, query)
            awaitPage(client.newCall(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FishAudioApiException) {
            throw error
        } catch (_: Exception) {
            throw FishAudioApiException(null, "Fish Audio 音色目录连接失败")
        }
    }

    private fun decodePage(response: Response): FishAudioModelPage = response.use {
        if (!response.isSuccessful) {
            // Never expose remote response text, request URLs or credentials in diagnostics.
            val reason = when (response.code) {
                401 -> "认证失败，请检查 API Key"
                403 -> "当前 API Key 无权访问音色"
                429 -> "请求频率超过限制"
                else -> "音色目录请求失败"
            }
            throw FishAudioApiException(response.code, "$reason（HTTP ${response.code}）")
        }
        val source = response.body?.source()
            ?: throw FishAudioApiException(response.code, "音色目录响应为空")
        if (source.request(MAX_CATALOG_BYTES + 1)) {
            throw FishAudioApiException(response.code, "音色目录响应超过安全大小限制")
        }
        try {
            json.decodeFromString(FishAudioModelPage.serializer(), source.readUtf8())
        } catch (_: Exception) {
            throw FishAudioApiException(response.code, "音色目录响应无法解析")
        }
    }

    // Completion covers body consumption too, so app shutdown joins response ownership.
    private suspend fun awaitPage(call: Call): FishAudioModelPage = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        synchronized(lifecycle) {
            if (closed) {
                continuation.resumeWith(Result.failure(FishAudioApiException(null, "音色目录已关闭")))
                return@suspendCancellableCoroutine
            }
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            calls[call] = done
            fun finish() {
                synchronized(lifecycle) { calls.remove(call) }
                done.complete(Unit)
            }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
                        finally { finish() }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            if (!continuation.isActive) { response.close(); return }
                            val result = runCatching { decodePage(response) }
                            if (continuation.isActive) continuation.resumeWith(result)
                        } finally { finish() }
                    }
                })
            } catch (error: Exception) {
                finish()
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
    }

    private companion object {
        const val MAX_CATALOG_BYTES = 4L * 1024 * 1024
    }
}
