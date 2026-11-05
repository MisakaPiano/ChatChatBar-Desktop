package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.*

class DesktopSamplerAdmissionTest {
    @Test fun `unsupported raw High sampler is refused by actual generation runtime before any HTTP or persistence`() = runBlocking {
        var requests = 0
        var persisted = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            error("Unsupported sampler reached HTTP")
        }.build()
        val secrets = InMemoryDesktopSecretStore().apply {
            save(DesktopCredentialKey.NovelAiToken, "synthetic-only-token")
        }
        val runtime = DesktopNovelAiGenerationRuntime(secrets, { _, _ ->
            persisted++
            error("Unsupported sampler reached persistence")
        }, client)
        for (model in NovelAiImageModel.entries) {
            val raw = NovelAiGenerationSettings(model = model, sampler = NovelAiSampler.DDIM)
            val draft = NovelAiStudioDraft(basePrompt = "synthetic scene", selectedModel = model).withActiveSettings(raw)
            assertFailsWith<DesktopNovelAiRequestException> { runtime.generate(draft, maxRateLimitRetries = 0) }
            assertEquals(raw, draft.activeSettings)
        }
        assertEquals(0, requests)
        assertEquals(0, persisted)
    }
}
