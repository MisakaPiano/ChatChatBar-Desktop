package com.example.chatbar.domain.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class FishAudioTtsRequest(
    val text: String,
    @kotlinx.serialization.SerialName("reference_id")
    val referenceId: String,
    val format: String,
    @kotlinx.serialization.SerialName("sample_rate")
    val sampleRate: Int,
    @kotlinx.serialization.SerialName("mp3_bitrate")
    val mp3Bitrate: Int,
    val latency: String
)

object FishAudioRequestFactory {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun listModels(
        baseUrl: String,
        apiKey: String,
        query: FishAudioModelQuery
    ): Request {
        val url = "${baseUrl.trimEnd('/')}/model".toHttpUrl().newBuilder()
            .addQueryParameter("page_size", query.pageSize.coerceAtLeast(1).toString())
            .addQueryParameter("page_number", query.pageNumber.coerceAtLeast(1).toString())
            .addQueryParameter("self", (query.library == FishAudioLibrary.MINE).toString())
            .addQueryParameter("sort_by", query.sort.apiValue)
            .apply {
                query.title.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("title", it) }
                query.tags.map(String::trim).filter(String::isNotEmpty).forEach { addQueryParameter("tag", it) }
                query.languages.map(String::trim).filter(String::isNotEmpty)
                    .forEach { addQueryParameter("language", it) }
            }
            .build()
        return Request.Builder()
            .url(url)
            .header("Authorization", bearer(apiKey))
            .get()
            .build()
    }

    fun tts(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        referenceId: String,
        text: String,
        json: Json = Json { encodeDefaults = true }
    ): Request {
        val payload = FishAudioTtsRequest(
            text = text,
            referenceId = referenceId,
            format = "mp3",
            sampleRate = 44_100,
            mp3Bitrate = 64,
            latency = "normal"
        )
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/tts")
            .header("Authorization", bearer(apiKey))
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .header("model", modelId)
            .post(json.encodeToString(payload).toRequestBody(jsonMediaType))
            .build()
    }

    fun preview(url: String, apiKey: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", bearer(apiKey))
            .get()
            .build()

    private fun bearer(apiKey: String): String {
        val normalized = apiKey.trim()
        require(normalized.isNotEmpty()) { "Fish Audio API Key 未配置" }
        return "Bearer $normalized"
    }
}
