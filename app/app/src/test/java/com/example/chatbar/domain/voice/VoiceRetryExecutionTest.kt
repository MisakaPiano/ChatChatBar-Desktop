package com.example.chatbar.domain.voice

import com.example.chatbar.domain.chat.ModelRequestException
import com.example.chatbar.domain.chat.ModelResponseTruncatedException
import com.example.chatbar.domain.prompt.AiTaskEmptyResponseException
import com.example.chatbar.domain.prompt.AiTaskFailureKind
import com.example.chatbar.domain.prompt.AiTaskRefusalException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRetryExecutionTest {
    @Test
    fun `auxiliary transport stops after three failures retaining original evidence`() = runTest {
        var calls = 0
        val waits = mutableListOf<Long>()
        val expected = ModelRequestException("busy", 429, "trace", 10_000L)
        val failure = runCatching {
            retryVoiceAuxiliary({ _, wait -> waits += wait }) { calls++; throw expected }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(3, calls)
        assertEquals(2, waits.size)
        assertTrue(waits.all { it in 10_000L..10_500L })
        assertEquals(waits.sum(), testScheduler.currentTime)
    }

    @Test
    fun `output and transport budgets stay independent and stop on success`() = runTest {
        var calls = 0
        val result = retryVoiceAuxiliary({ _, _ -> }) {
            when (++calls) {
                1, 3 -> throw ModelRequestException("reset")
                2 -> throw VoiceOutputException("invalid json")
                else -> "valid"
            }
        }
        assertEquals("valid", result)
        assertEquals(4, calls)
    }

    @Test
    fun `output failures stop after two attempts including empty and truncation`() = runTest {
        listOf(VoiceOutputException("invalid"), ModelResponseTruncatedException(),
            AiTaskEmptyResponseException(false), AiTaskEmptyResponseException(true)).forEach { expected ->
            var calls = 0
            val failure = runCatching {
                retryVoiceAuxiliary({ _, _ -> }) { calls++; throw expected }
            }.exceptionOrNull()
            assertSame(expected, failure)
            assertEquals(2, calls)
        }
    }

    @Test
    fun `terminal auxiliary errors never retry including wrapped cancellation and refusal`() = runTest {
        val cancelled = CancellationException("stop")
        val refused = AiTaskRefusalException(AiTaskFailureKind.REFUSAL)
        listOf(
            ModelRequestException("bad request", 400), ModelRequestException("auth", 401),
            ModelRequestException("forbidden", 403), IllegalArgumentException("bug"),
            cancelled, refused,
            ModelRequestException("wrapped", cause = cancelled),
            ModelRequestException("wrapped", cause = refused)
        ).forEach { expected ->
            var calls = 0
            var retries = 0
            val failure = runCatching {
                retryVoiceAuxiliary({ _, _ -> retries++ }) { calls++; throw expected }
            }.exceptionOrNull()
            assertSame(expected.cause ?: expected, failure)
            assertEquals(1, calls)
            assertEquals(0, retries)
        }
    }

    @Test
    fun `cancellation during auxiliary backoff prevents next request`() = runTest {
        var calls = 0
        val job = launch {
            retryVoiceAuxiliary({ _, _ -> }) { calls++; throw ModelRequestException("busy", 503) }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(1, calls)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `tts rejection retries twice and returns first successful response`() = runTest {
        var calls = 0
        val attempts = mutableListOf<Int>()
        val result = retryRejectedVoiceTts({ _, attempt, _ -> attempts += attempt }) {
            calls++
            if (calls < 3) throw FishAudioApiException(429, "limited", retryAfterMillis = 5_000L)
            "audio"
        }
        assertEquals("audio", result)
        assertEquals(3, calls)
        assertEquals(listOf(1, 2), attempts)
        assertTrue(testScheduler.currentTime in 10_000L..11_000L)
    }

    @Test
    fun `tts permanent failures and uncertain transport never replay`() = runTest {
        listOf(FishAudioApiException(null, "lost connection"), FishAudioApiException(401, "auth"),
            FishAudioApiException(402, "balance"), FishAudioApiException(422, "invalid"),
            IOException("download interrupted"), CancellationException("stop")).forEach { expected ->
            var calls = 0
            var retries = 0
            val failure = runCatching {
                retryRejectedVoiceTts({ _, _, _ -> retries++ }) { calls++; throw expected }
            }.exceptionOrNull()
            assertSame(expected, failure)
            assertEquals(1, calls)
            assertEquals(0, retries)
        }
    }

    @Test
    fun `tts temporary failure exhausts exactly three attempts`() = runTest {
        val expected = FishAudioApiException(503, "unavailable")
        var calls = 0
        val failure = runCatching {
            retryRejectedVoiceTts({ _, _, _ -> }) { calls++; throw expected }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(3, calls)
    }

    @Test
    fun `cancellation during tts backoff prevents synthesis replay`() = runTest {
        var calls = 0
        val job = launch {
            retryRejectedVoiceTts({ _, _, _ -> }) { calls++; throw FishAudioApiException(429, "limited") }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(1, calls)
        assertTrue(job.isCancelled)
    }
}
