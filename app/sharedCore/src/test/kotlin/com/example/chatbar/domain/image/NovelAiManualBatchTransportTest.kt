package com.example.chatbar.domain.image

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class NovelAiManualBatchTransportTest {
    @Test fun `safe batch returns typed rate limit without legacy retries and retains server cooldown`() = runTest {
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("synthetic")
                .header("Retry-After", "120").body(byteArrayOf().toResponseBody()).build()
        }.build()
        try {
            val settings = NovelAiGenerationSettings()
            val result = NovelAiImageService(client).generate("synthetic", NovelAiPromptPlan("synthetic scene", emptyList()),
                settings.imageSize(), settings, retryRateLimitsUntilCancelled = true, safeBatchRequest = true).toList()
            val failure = assertIs<NovelAiImageEvent.Error>(result.single())
            assertEquals(429, failure.httpCode)
            assertEquals(120000L, failure.retryAfterMillis)
            assertEquals(1, count.get())
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `uncertain response is not automatically resubmitted`() = runTest {
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            count.incrementAndGet()
            throw java.io.EOFException("synthetic response lost")
        }.build()
        try {
            val settings = NovelAiGenerationSettings()
            val result = NovelAiImageService(client, safeErrorsOnly = true).generate("synthetic", NovelAiPromptPlan("synthetic scene", emptyList()),
                settings.imageSize(), settings, safeBatchRequest = true).toList()
            assertIs<NovelAiImageEvent.Error>(result.single())
            assertEquals(1, count.get())
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `server retry after seconds and date are not shortened`() {
        assertEquals(120000L, NovelAiImageService.safeRetryAfterMillis("120"))
        assertEquals(60000L, NovelAiImageService.safeRetryAfterMillis("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertEquals(10000L, NovelAiImageService.safeRetryAfterMillis("invalid"))
    }
}
