package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import kotlin.test.*

class DesktopSamplerAdmissionTest {
    @Test fun `actual generation keeps legacy raw preference and uses existing supported normalization before HTTP`() = runBlocking {
        var requests = 0
        var persisted = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(NovelAiSampler.EULER_ANCESTRAL.apiId,
                body.getValue("parameters").jsonObject.getValue("sampler").jsonPrimitive.content)
            error("Synthetic stop after observing normalized request; no network")
        }.build()
        val secrets = InMemoryDesktopSecretStore().apply {
            save(DesktopCredentialKey.NovelAiToken, "synthetic-only-token")
        }
        val runtime = DesktopNovelAiGenerationRuntime(secrets, { _, _ ->
            persisted++
            error("Synthetic response must not persist")
        }, client)
        for (model in NovelAiImageModel.entries) {
            val raw = NovelAiGenerationSettings(model = model, sampler = NovelAiSampler.DDIM)
            val draft = if (model == NovelAiImageModel.V4_5_FULL)
                NovelAiStudioDraft(basePrompt = "synthetic scene", selectedModel = model, v45Settings = raw)
            else NovelAiStudioDraft(basePrompt = "synthetic scene", selectedModel = model, v5Settings = raw)
            assertFailsWith<DesktopNovelAiRequestException> { runtime.generate(draft, maxRateLimitRetries = 0) }
            assertEquals(raw, if (model == NovelAiImageModel.V4_5_FULL) draft.v45Settings else draft.v5Settings)
        }
        assertEquals(NovelAiImageModel.entries.size, requests)
        assertEquals(0, persisted)
    }
}
