package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.addModelApiAuthorization
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Same official OpenAI embedding wire shape, bounded response and exact indexed correspondence.
 * One method invocation sends at most one provider request. No redirects or hidden retries.
 * Config is hydrated by the protected repository at the final request boundary, never journaled.
 */
internal class DesktopEmbeddingGateway(
    private val config: suspend () -> EmbeddingConfig,
    allowCleartext: () -> Boolean = { false },
    private val beforeRequest: suspend () -> Unit = {},
    private val client: OkHttpClient = ProxyAwareClient.modelApiBuilder(allowCleartext)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build(),
) : DesktopIndexEmbeddingGateway, Closeable {
    override suspend fun embed(batch: DesktopIndexEmbeddingBatch): List<List<Float>> {
        return embedPinned(batch, null)
    }
    fun pinned(expected: EmbeddingConfig): DesktopIndexEmbeddingGateway = DesktopIndexEmbeddingGateway { embedPinned(it, expected) }
    suspend fun verify(expected: EmbeddingConfig) { check(config() == expected) { "向量模型或凭据已变化，请重试" } }

    private suspend fun embedPinned(batch: DesktopIndexEmbeddingBatch, expected: EmbeddingConfig?): List<List<Float>> {
        require(batch.pieces.size in 1..16 && batch.identity.dimensions in 1..65536)
        require(batch.pieces.size.toLong() * batch.identity.dimensions <= 65536)
        val selected = config()
        check(expected == null || selected == expected) { "向量模型或凭据已变化，请重试" }
        check(DesktopRagEmbeddingIdentity.from(selected) == batch.identity) { "向量模型已变化，请重试" }
        val body = buildJsonObject {
            put("model", selected.modelName)
            put("input", buildJsonArray { batch.pieces.forEach { add(it.first) } })
        }.toString()
        val payload = body.toRequestBody("application/json; charset=utf-8".toMediaType())
        // retryOnConnectionFailure alone does not disable OkHttp's HTTP 503/408 follow-up.
        val oneShot = object : RequestBody() {
            override fun contentType() = payload.contentType()
            override fun contentLength() = payload.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) = payload.writeTo(sink)
        }
        val request = Request.Builder().url(selected.baseUrl.trimEnd('/') + "/embeddings")
            .addModelApiAuthorization(selected.apiKey)
            .post(oneShot).build()
        beforeRequest()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    continuation.resumeWithException(IllegalStateException("向量请求未完成；旧索引已保留"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        check(it.isSuccessful) { "向量服务请求失败（HTTP ${it.code}）；旧索引已保留" }
                        val bytes = requireNotNull(it.body).byteStream().use { input -> input.readNBytes(MAX_RESPONSE + 1) }
                        check(bytes.size <= MAX_RESPONSE) { "向量响应超过安全大小" }
                        val data = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("data").jsonArray
                        check(data.size == batch.pieces.size)
                        val indexed = data.map { value ->
                            val row = value.jsonObject
                            row.getValue("index").jsonPrimitive.int to row.getValue("embedding").jsonArray.map { it.jsonPrimitive.float }
                        }
                        check(indexed.map { it.first }.sorted() == batch.pieces.indices.toList()) { "向量响应索引不完整" }
                        indexed.sortedBy { it.first }.map { (_, vector) ->
                            check(vector.size == batch.identity.dimensions && vector.all(Float::isFinite))
                            vector
                        }
                    } }.recoverCatching { error ->
                        // Provider response bodies and credentials never enter status or persisted failures.
                        throw IllegalStateException(if (error is IllegalStateException && error.message?.startsWith("向量") == true)
                            error.message else "向量响应无效；旧索引已保留")
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    suspend fun closeAndDrain() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
        close()
        check(client.dispatcher.executorService.awaitTermination(30, TimeUnit.SECONDS)) { "向量连接尚未排水" }
    }
    companion object { private const val MAX_RESPONSE = 8 * 1024 * 1024 }
}
