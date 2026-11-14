package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** User generation adapter. Automation must use DesktopNovelAiRuntime.liveSmoke and its fuse. */
internal class DesktopNovelAiGenerationRuntime(
    private val secrets: DesktopSecretStore,
    private val persist: suspend (List<ByteArray>, NovelAiGenerationRecipe) -> NovelAiGenerationHistoryEntry,
    private val client: OkHttpClient = secureNovelAiClient(),
    private val diagnostics: DesktopTransportDiagnosticsOwner? = null,
) {
    private val mutex = Mutex()

    suspend fun generate(
        launchDraft: NovelAiStudioDraft,
        guidance: NovelAiPreparedImageGuidance = NovelAiPreparedImageGuidance.NONE,
        maxRateLimitRetries: Int = 2,
        onIntermediate: (ByteArray, Int, Float) -> Unit = { _, _, _ -> },
        onRetry: (Int, Long) -> Unit = { _, _ -> },
        retryRateLimitsUntilCancelled: Boolean = false,
        requestSize: NovelAiImageSize? = null,
        composeResult: (ByteArray) -> ByteArray = { it },
        promptPlan: NovelAiPromptPlan? = null,
        safeBatchRequest: Boolean = false,
    ): NovelAiGenerationHistoryEntry = mutex.withLock {
        withContext(Dispatchers.IO) {
            var media: DesktopImageDiagnosticRecorder? = null
            try {
                require(maxRateLimitRetries in 0..10)
                val initial = launchDraft.activeSettings
                require(initial.requestValidationError(launchDraft.activeCharacters.size) == null)
                val settings = if (initial.seedMode == NovelAiSeedMode.RANDOM) initial.copy(
                    seedMode = NovelAiSeedMode.FIXED,
                    seed = kotlin.random.Random.nextLong(0, initial.maxAllowedBaseSeed + 1),
                ) else initial
                val plan = promptPlan ?: launchDraft.toPromptPlan()
                val recipe = launchDraft.toRecipe(settings)
                val token = secrets.credentialForCall(DesktopCredentialKey.NovelAiToken)?.takeIf(String::isNotBlank)
                    ?: throw DesktopNovelAiRequestException("请先在应用内安全保存 NovelAI 凭据")
                check(!Json.encodeToString(NovelAiGenerationRecipe.serializer(), recipe).contains(token))
                fun validate(bytes: ByteArray) {
                    check(bytes.size <= DesktopImageEditing.MAX_BYTES)
                    check(!bytes.toString(Charsets.ISO_8859_1).contains(token))
                    DesktopImageEditing.decode(bytes)
                }
                val images = mutableListOf<ByteArray>()
                media = beginDesktopImageDiagnostics(diagnostics, secrets, token, settings.requestModel(guidance.action), settings.count)
                NovelAiImageService(media?.client(client) ?: client, safeErrorsOnly = true).generate(
                    token, plan, requestSize ?: settings.imageSize(), settings, guidance,
                    retryRateLimitsUntilCancelled = retryRateLimitsUntilCancelled && !safeBatchRequest,
                    maxRateLimitRetries = if (safeBatchRequest) 0 else maxRateLimitRetries,
                    safeBatchRequest = safeBatchRequest,
                    onRateLimitRetry = onRetry,
                    readTimeoutSeconds = if (retryRateLimitsUntilCancelled) 120 else 600,
                ).collect { event ->
                    when (event) {
                        is NovelAiImageEvent.Intermediate -> {
                            validate(event.image); onIntermediate(event.image, event.step, event.progress)
                        }
                        is NovelAiImageEvent.Final -> {
                            validate(event.image)
                            media?.validatedImage(event.image.size)
                            val composed = composeResult(event.image)
                            validate(composed)
                            images += composed
                        }
                        is NovelAiImageEvent.Error -> {
                            if (safeBatchRequest && event.httpCode == 429) {
                                throw ImageBatchRateLimited(event.retryAfterMillis ?: 10000L)
                            }
                            throw DesktopNovelAiRequestException(event.message)
                        }
                    }
                }
                check(images.size == settings.count)
                // Validated network output is independent of a later local commit failure/cancellation.
                media?.finish(DesktopUsageOutcome.COMPLETED)
                currentCoroutineContext().ensureActive()
                // Cancellation before commit saves nothing; an entered durable batch completes atomically.
                withContext(NonCancellable) { persist(images, recipe) }
            } catch (_: CancellationException) { media?.finish(DesktopUsageOutcome.CANCELLED); throw CancellationException("NovelAI 请求已取消") }
            catch (limited: ImageBatchRateLimited) { media?.finish(DesktopUsageOutcome.FAILED); throw limited }
            catch (safe: DesktopNovelAiRequestException) { media?.finish(DesktopUsageOutcome.FAILED); throw safe }
            catch (_: Exception) { media?.finish(DesktopUsageOutcome.FAILED); throw DesktopNovelAiRequestException("NovelAI 生成或保存失败；未发布不完整批次") }
        }
    }
}
