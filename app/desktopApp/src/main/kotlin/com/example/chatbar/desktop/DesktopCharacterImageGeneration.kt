package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.desktop.security.credentialForCall
import com.example.chatbar.domain.image.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient

/** Character candidates are transient; only the editor's explicit apply stages draft assets. */
internal class DesktopCharacterImageGeneration(
    private val secrets: DesktopSecretStore,
    private val client: OkHttpClient = secureNovelAiClient().newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build(),
) {
    suspend fun generate(plan: NovelAiPromptPlan, size: NovelAiImageSize, settings: NovelAiGenerationSettings,
        onPreview: (ByteArray, Float) -> Unit = { _, _ -> }, onStatus: (String) -> Unit = {}): ByteArray =
        withContext(Dispatchers.IO) {
            val token = secrets.credentialForCall(DesktopCredentialKey.NovelAiToken)?.takeIf(String::isNotBlank)
                ?: error("NOVELAI_CREDENTIAL_UNAVAILABLE")
            var final: ByteArray? = null
            NovelAiImageService(client, safeErrorsOnly = true).generate(token, plan, size, settings,
                onRequestStatus = onStatus).collect { event ->
                currentCoroutineContext().ensureActive()
                when (event) {
                    is NovelAiImageEvent.Intermediate -> {
                        DesktopImageEditing.decode(event.image); onPreview(event.image, event.progress)
                    }
                    is NovelAiImageEvent.Final -> {
                        check(final == null) { "MULTIPLE_CHARACTER_IMAGE_FINALS" }
                        DesktopImageEditing.decode(event.image); final = event.image; onPreview(event.image, 1f)
                    }
                    is NovelAiImageEvent.Error -> error("CHARACTER_IMAGE_GENERATION_FAILED")
                }
            }
            currentCoroutineContext().ensureActive()
            checkNotNull(final) { "CHARACTER_IMAGE_FINAL_MISSING" }
        }

    suspend fun closeAndDrain() = withContext(NonCancellable + Dispatchers.IO) {
        client.dispatcher.cancelAll(); client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        check(client.dispatcher.executorService.awaitTermination(30, TimeUnit.SECONDS)) { "角色生图连接尚未排水" }
    }
}
