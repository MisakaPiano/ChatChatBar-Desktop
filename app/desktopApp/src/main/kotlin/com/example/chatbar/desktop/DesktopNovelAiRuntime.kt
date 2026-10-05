package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal class DesktopNovelAiRequestException(message: String) : IllegalStateException(message)

/** All production transport policy/serialization lives in shared NovelAiImageService. */
internal class DesktopNovelAiRuntime(
    private val secrets: DesktopSecretStore,
    private val fuse: DesktopNovelAiLiveFuse,
    private val persist: suspend (ByteArray, NovelAiPromptPlan, NovelAiGenerationSettings) -> String,
    private val client: OkHttpClient = secureNovelAiClient(),
) {
    suspend fun liveSmoke(
        userConfirmedCredential: Boolean,
        prompt: NovelAiPromptPlan,
        settings: NovelAiGenerationSettings,
        guidance: NovelAiPreparedImageGuidance = NovelAiPreparedImageGuidance.NONE,
        onProgress: (Int, Float) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        check(userConfirmedCredential) { "等待用户在应用内保存凭据并确认；未发送请求" }
        try {
            fuse.acquire().use { lease ->
                val token = secrets.load(DesktopCredentialKey.NovelAiToken)
                    ?.takeIf { it.isNotBlank() } ?: throw DesktopNovelAiRequestException("请先在应用内安全保存 NovelAI 凭据")
                // Reject forbidden shapes even before the read-only account request.
                DesktopNovelAiLivePolicy.requireFree(settings, guidance, NovelAiAccountUsage(0, 3, true, null, false))
                require(settings.validationError(prompt.characterCaptions.size) == null)
                lease.checkReady()
                val account = try {
                    NovelAiAccountService(client).fetchCancellable(token).also {
                        DesktopNovelAiLivePolicy.requireFree(settings, guidance, it)
                    }
                } catch (failure: Exception) {
                    withContext(NonCancellable) { lease.halt() }
                    throw failure
                }
                currentCoroutineContext().ensureActive()
                val resolved = if (settings.seedMode == NovelAiSeedMode.RANDOM) settings.copy(
                    seed = kotlin.random.Random.nextLong(0, settings.maxAllowedBaseSeed + 1), seedMode = NovelAiSeedMode.FIXED,
                ) else settings
                lease.reserve()
                var success = false
                try {
                    val outputs = mutableListOf<ByteArray>()
                    NovelAiImageService(client, safeErrorsOnly = true).generate(
                        token, prompt, resolved.imageSize(), resolved, guidance,
                        retryRateLimitsUntilCancelled = false, maxRateLimitRetries = 0,
                    ).collect { event ->
                        when (event) {
                            is NovelAiImageEvent.Intermediate -> onProgress(event.step, event.progress)
                            is NovelAiImageEvent.Final -> outputs += event.image
                            is NovelAiImageEvent.Error -> throw DesktopNovelAiRequestException(event.message)
                        }
                    }
                    check(outputs.size == 1) { "返回图片数量不正确" }
                    val bytes = outputs.single()
                    check(bytes.size <= DesktopImageEditing.MAX_BYTES)
                    // No provider-reflected credential may enter an image artifact/metadata.
                    check(!bytes.toString(Charsets.ISO_8859_1).contains(token))
                    DesktopImageEditing.decode(bytes)
                    val after = NovelAiAccountService(client).fetchCancellable(token)
                    check(after.isActiveOpus && after.anlas == account.anlas) { "账户余额或资格出现异常，停止验证" }
                    val path = withContext(NonCancellable) { persist(bytes, prompt, resolved) }
                    success = true
                    path
                } finally { withContext(NonCancellable) { lease.finish(success) } }
            }
        } catch (cancelled: CancellationException) { throw CancellationException("NovelAI 请求已取消") }
        catch (safe: DesktopNovelAiRequestException) { throw safe }
        catch (_: Exception) { throw DesktopNovelAiRequestException("NovelAI 安全验证未完成；已停止，未自动重试") }
    }
}

internal fun secureNovelAiClient(): OkHttpClient = ProxyAwareClient.builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .addInterceptor { chain ->
        val request = chain.request()
        check(request.url.scheme == "https" && request.url.host == "image.novelai.net" && request.url.port == 443)
        check(request.url.encodedPath in setOf("/user/subscription", "/ai/generate-image-stream"))
        chain.proceed(request)
    }.build()
