package com.example.chatbar.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRetryPolicyTest {
    @Test
    fun `tts retries explicit temporary rejection only`() {
        listOf(408, 425, 429, 500, 503).forEach {
            assertTrue(VoiceRetryPolicy.isRejectedTtsRetryable(it))
        }
        listOf(null, 200, 400, 401, 402, 403, 404, 422).forEach {
            assertFalse(VoiceRetryPolicy.isRejectedTtsRetryable(it))
        }
    }

    @Test
    fun `retry after supports seconds and HTTP dates`() {
        assertEquals(12_000L, VoiceRetryPolicy.retryAfterMillis("12"))
        assertEquals(60_000L, VoiceRetryPolicy.retryAfterMillis("Thu, 01 Jan 1970 00:01:00 GMT", 0L))
        assertNull(VoiceRetryPolicy.retryAfterMillis("invalid"))
        assertNull(VoiceRetryPolicy.retryAfterMillis("-1"))
        assertNull(VoiceRetryPolicy.retryAfterMillis(Long.MAX_VALUE.toString()))
    }

    @Test
    fun `backoff respects server minimum and adds bounded jitter`() {
        assertTrue(VoiceRetryPolicy.delayMillis(1, null) in 1_000L..1_500L)
        assertTrue(VoiceRetryPolicy.delayMillis(2, null) in 2_000L..2_500L)
        assertTrue(VoiceRetryPolicy.delayMillis(1, 10_000L) in 10_000L..10_500L)
    }
}
