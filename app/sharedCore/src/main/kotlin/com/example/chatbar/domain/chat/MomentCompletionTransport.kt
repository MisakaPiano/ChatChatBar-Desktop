package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.addModelApiAuthorization
import com.example.chatbar.domain.prompt.AuxiliaryMessageAssembler
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/** Debug's original non-streaming request mode, with the same shared serializer and completion policy. */
internal suspend fun completeMomentRequest(client: OkHttpClient, allowCleartext: Boolean,
    messages: List<ChatApiMessage>, model: ModelConfig, diagnostics: ProviderTransportDiagnostics,
    experimentalApiMode: Boolean = false): String {
    val body = OpenAiChatRequestSerializer.serialize(AuxiliaryMessageAssembler.assemble(messages, false),
        model, stream = false, allowCleartextHttp = allowCleartext,
        isolatedTaskParameters = false, disableThinking = false, experimentalApiMode = experimentalApiMode)
    val url = model.chatCompletionsUrl()
    diagnostics.onRequest(ProviderRequestEvidence(url, body))
    val bytes = body.toRequestBody("application/json; charset=utf-8".toMediaType())
    val oneShot = object : RequestBody() {
        override fun contentType() = bytes.contentType()
        override fun contentLength() = bytes.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = bytes.writeTo(sink)
    }
    val request = Request.Builder().url(url).addModelApiAuthorization(model.apiKey).post(oneShot).build()
    val raw = suspendCancellableCoroutine<String> { continuation ->
        val call = client.forIsolatedRepair(AuxiliaryStreamTiming(readTimeoutMillis = 120_000)).newCall(request)
        continuation.invokeOnCancellation { call.cancel(); diagnostics.onCancelled() }
        diagnostics.onAttemptStarted(1)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val content = response.body?.string().orEmpty()
                        if (!continuation.isActive) return
                        if (!response.isSuccessful) continuation.resumeWithException(
                            ModelRequestException("朋友圈文本请求失败 (${response.code})", httpStatus = response.code))
                        else continuation.resume(content)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }
    diagnostics.onResponseChunk(raw)
    return OpenAiCompletionResponsePolicy.requireCompletion(raw, experimentalApiMode = experimentalApiMode).also {
        if (experimentalApiMode) diagnostics.onDecodedContent(it)
    }
}
