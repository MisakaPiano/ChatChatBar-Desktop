package com.example.chatbar.domain.image

import com.example.chatbar.domain.ProxyAwareClient
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class NovelAiAccountUsage(
    val anlas: Long,
    val tier: Int,
    val active: Boolean,
    val v5AllowancePercent: Double?,
    val v5AllowanceExhausted: Boolean
) {
    val isActiveOpus: Boolean get() = active && tier >= OPUS_TIER

    val approximateV5Images: Int?
        get() = if (v5AllowanceExhausted) 0 else v5AllowancePercent
            ?.coerceIn(0.0, 100.0)
            ?.times(V5_IMAGES_PER_PERCENT)
            ?.roundToInt()
            ?.coerceAtLeast(0)

    private companion object {
        const val OPUS_TIER = 3
        const val V5_IMAGES_PER_PERCENT = 17.3
    }
}

enum class NovelAiAccountFailureKind { AUTHENTICATION, RATE_LIMIT, SERVER, HTTP, DNS, TIMEOUT, TLS, CONNECTION, NETWORK, INVALID_RESPONSE }

/** Contains only a fixed category and HTTP status, never a provider body, host, token or raw cause. */
class NovelAiAccountException(
    val kind: NovelAiAccountFailureKind,
    val httpStatus: Int? = null,
    val connectionTrace: com.example.chatbar.domain.network.HttpConnectionTraceSnapshot? = null,
) : IOException(when (kind) {
    NovelAiAccountFailureKind.AUTHENTICATION -> "NovelAI 账户认证未通过，请检查所选账户和 Token"
    NovelAiAccountFailureKind.RATE_LIMIT -> "NovelAI 账户请求受限，请稍后重试"
    NovelAiAccountFailureKind.SERVER -> "NovelAI 账户服务暂时不可用，请稍后重试"
    NovelAiAccountFailureKind.HTTP -> "NovelAI 账户请求被服务拒绝"
    NovelAiAccountFailureKind.DNS -> "无法解析 NovelAI 服务地址，请检查网络与代理"
    NovelAiAccountFailureKind.TIMEOUT -> "NovelAI 账户请求超时，请检查网络或稍后重试"
    NovelAiAccountFailureKind.TLS -> "NovelAI 安全连接未通过，请检查证书、系统时间与代理设置"
    NovelAiAccountFailureKind.CONNECTION -> "无法连接 NovelAI 服务，请检查网络与代理"
    NovelAiAccountFailureKind.NETWORK -> "NovelAI 账户请求发生网络错误，请检查网络与代理"
    NovelAiAccountFailureKind.INVALID_RESPONSE -> "NovelAI 账户响应格式无法识别，请稍后重试"
} + (httpStatus?.let { "（HTTP $it）" } ?: "") + (connectionTrace?.let { "［${it.summary()}］" } ?: ""))

private fun accountFailure(failure: Exception, trace: com.example.chatbar.domain.network.HttpConnectionTraceSnapshot?): NovelAiAccountException = when (failure) {
    // Closing a response may attach a raw suppressed failure to the typed exception.
    is NovelAiAccountException -> NovelAiAccountException(failure.kind, failure.httpStatus, trace)
    is UnknownHostException -> NovelAiAccountException(NovelAiAccountFailureKind.DNS, connectionTrace = trace)
    is InterruptedIOException -> NovelAiAccountException(NovelAiAccountFailureKind.TIMEOUT, connectionTrace = trace)
    is SSLException -> NovelAiAccountException(NovelAiAccountFailureKind.TLS, connectionTrace = trace)
    is ConnectException -> NovelAiAccountException(NovelAiAccountFailureKind.CONNECTION, connectionTrace = trace)
    is IOException -> NovelAiAccountException(NovelAiAccountFailureKind.NETWORK, connectionTrace = trace)
    else -> NovelAiAccountException(NovelAiAccountFailureKind.INVALID_RESPONSE, connectionTrace = trace)
}

class NovelAiAccountService(
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private fun request(token: String, trace: com.example.chatbar.domain.network.HttpConnectionTrace) = Request.Builder()
            .tag(com.example.chatbar.domain.network.HttpConnectionTrace::class.java, trace)
            .url(SUBSCRIPTION_ENDPOINT)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

    fun fetch(token: String): NovelAiAccountUsage {
        val trace = com.example.chatbar.domain.network.HttpConnectionTrace()
        return try {
            client.newCall(request(token, trace)).execute().use(::parse)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (failure: Exception) { throw accountFailure(failure, trace.snapshot()) }
    }

    suspend fun fetchCancellable(token: String): NovelAiAccountUsage = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val trace = com.example.chatbar.domain.network.HttpConnectionTrace()
        val call = client.newCall(request(token, trace))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(accountFailure(e, trace.snapshot())))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val result = try { Result.success(response.use(::parse)) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { Result.failure(cancelled) }
                catch (failure: Exception) { Result.failure(accountFailure(failure, trace.snapshot())) }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    private fun parse(response: okhttp3.Response): NovelAiAccountUsage {
        if (!response.isSuccessful) {
            throw NovelAiAccountException(when (response.code) {
                401, 403 -> NovelAiAccountFailureKind.AUTHENTICATION
                429 -> NovelAiAccountFailureKind.RATE_LIMIT
                in 500..599 -> NovelAiAccountFailureKind.SERVER
                else -> NovelAiAccountFailureKind.HTTP
            }, response.code)
        }
        val body = response.body?.string().orEmpty()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw NovelAiAccountException(NovelAiAccountFailureKind.INVALID_RESPONSE) }
        val usage = root["usage"] as? JsonObject
        val tier = root["tier"]?.jsonPrimitive?.intOrNull ?: 0
        val expiresAt = root["expiresAt"]?.jsonPrimitive?.longOrNull ?: 0L
        val active = root["active"]?.jsonPrimitive?.booleanOrNull
            ?: (tier > 0 && expiresAt > System.currentTimeMillis() / 1_000L)
        return NovelAiAccountUsage(
            anlas = parseAnlas(root["trainingStepsLeft"]),
            tier = tier,
            active = active,
            v5AllowancePercent = usage?.get("percent")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            v5AllowanceExhausted = usage?.get("isNegative")?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    private fun parseAnlas(value: kotlinx.serialization.json.JsonElement?): Long = when (value) {
        is JsonObject -> {
            val fixed = value["fixedTrainingStepsLeft"]?.jsonPrimitive?.longOrNull ?: 0L
            val purchased = value["purchasedTrainingSteps"]?.jsonPrimitive?.longOrNull ?: 0L
            fixed + purchased
        }
        else -> value?.jsonPrimitive?.longOrNull ?: 0L
    }

    private companion object {
        const val SUBSCRIPTION_ENDPOINT = "https://image.novelai.net/user/subscription"
    }
}

enum class NovelAiGenerationChargeKind { FREE, V5_ALLOWANCE, ANLAS }

data class NovelAiGenerationCost(
    val kind: NovelAiGenerationChargeKind,
    val anlas: Int = 0,
    val encodingAnlas: Int = 0,
    val extraVibeAnlas: Int = 0
)

object NovelAiImageCostEstimator {
    fun estimate(
        settings: NovelAiGenerationSettings,
        account: NovelAiAccountUsage?,
        imageGuidance: NovelAiImageGuidanceDraft = NovelAiImageGuidanceDraft(),
        vibeCacheMisses: Int = 0
    ): NovelAiGenerationCost {
        val imageSize = settings.imageSize()
        val sourcePixels = imageSize.width.toLong() * imageSize.height
        val requestPixels = if (imageGuidance.action == NovelAiGenerationAction.INPAINT) {
            NORMAL_MAX_PIXELS
        } else {
            sourcePixels
        }
        val pixels = max(requestPixels, MIN_PRICED_PIXELS)
        val baseCost = ceil(
            PIXEL_BASE_COST_FACTOR * pixels +
                PIXEL_STEP_COST_FACTOR * pixels * settings.steps
        ).toInt()
        val modelCost = if (settings.model == NovelAiImageModel.V5_FULL) {
            ceil(baseCost * V5_PRICE_MULTIPLIER).toInt()
        } else {
            baseCost
        }.coerceAtLeast(MIN_IMAGE_COST)

        val freeSampleEligible = account?.isActiveOpus == true &&
            settings.count == 1 &&
            (imageGuidance.action == NovelAiGenerationAction.INPAINT || sourcePixels <= NORMAL_MAX_PIXELS) &&
            settings.steps <= FREE_MAX_STEPS &&
            imageGuidance.action != NovelAiGenerationAction.IMAGE_TO_IMAGE &&
            imageGuidance.effectiveReferenceMode(settings.model) == NovelAiReferenceMode.NONE &&
            (settings.model != NovelAiImageModel.V5_FULL ||
                account.v5AllowancePercent != null && !account.v5AllowanceExhausted)
        val preciseCost = if (
            settings.model == NovelAiImageModel.V4_5_FULL &&
            imageGuidance.effectiveReferenceMode(settings.model) == NovelAiReferenceMode.PRECISE &&
            imageGuidance.preciseReference.asset?.isUsable == true
        ) PRECISE_REFERENCE_COST * settings.count else 0
        val activeVibeCount = if (
            imageGuidance.effectiveReferenceMode(settings.model) == NovelAiReferenceMode.VIBE
        ) {
            imageGuidance.vibes.count(NovelAiVibeReferenceDraft::isUsable)
        } else {
            0
        }
        val extraVibeCost = (activeVibeCount - FREE_VIBE_COUNT).coerceAtLeast(0) *
            EXTRA_VIBE_COST * settings.count
        val encodingCost = vibeCacheMisses.coerceAtLeast(0) * VIBE_ENCODING_COST
        if (freeSampleEligible) {
            return NovelAiGenerationCost(
                kind = if (settings.model == NovelAiImageModel.V5_FULL) {
                    NovelAiGenerationChargeKind.V5_ALLOWANCE
                } else {
                    NovelAiGenerationChargeKind.FREE
                },
                anlas = preciseCost + extraVibeCost + encodingCost,
                encodingAnlas = encodingCost,
                extraVibeAnlas = extraVibeCost
            )
        }
        return NovelAiGenerationCost(
            kind = NovelAiGenerationChargeKind.ANLAS,
            anlas = modelCost * settings.count + preciseCost + extraVibeCost + encodingCost,
            encodingAnlas = encodingCost,
            extraVibeAnlas = extraVibeCost
        )
    }

    private const val MIN_PRICED_PIXELS = 65_536L
    private const val NORMAL_MAX_PIXELS = 1_048_576L
    private const val FREE_MAX_STEPS = 28
    private const val MIN_IMAGE_COST = 2
    private const val PIXEL_BASE_COST_FACTOR = 2.951823174884865e-6
    private const val PIXEL_STEP_COST_FACTOR = 5.753298233447344e-7
    private const val V5_PRICE_MULTIPLIER = 1.5
    private const val PRECISE_REFERENCE_COST = 5
    private const val VIBE_ENCODING_COST = 2
    private const val FREE_VIBE_COUNT = 4
    private const val EXTRA_VIBE_COST = 2
}
