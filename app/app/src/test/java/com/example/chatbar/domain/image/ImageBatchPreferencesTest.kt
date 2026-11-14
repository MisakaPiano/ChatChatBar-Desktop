package com.example.chatbar.domain.image

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ImageBatchPreferencesTest {
    @Test fun `four images for three batches always produces twelve with two waits`() = runTest {
        val config = ImageBatchPreferences(true, 4, 3, ImageBatchPacing(fixedSeconds = 1)).configuration()
        val counts = mutableListOf<Int>()
        var waits = 0
        val saved = ImageBatchRunner(sleep = { waits++ }).run(config.freeze(ImageBatchOrigin.CHAT_MANUAL),
            ImageBatchControl(), quote = { ImageBatchQuote(0, 100) }) { counts += it; it }
        assertEquals(listOf(4, 4, 4), counts)
        assertEquals(12, saved)
        assertEquals(2, waits)
    }

    @Test fun `disabled saved plan preserves defaults without forgetting preferences`() {
        val preferences = ImageBatchPreferences(false, 4, 3)
        assertEquals(listOf(1), preferences.configuration().freeze(ImageBatchOrigin.CHAT_MANUAL).batches)
        assertEquals(listOf(4, 4, 4), preferences.configuration(true).freeze(ImageBatchOrigin.CHAT_MANUAL).batches)
        assertFalse(preferences.allowAnlasSpending)
    }

    @Test fun `real estimator rejects four-image free assumption even on Opus`() = runTest {
        val opus = NovelAiAccountUsage(1000, 3, true, 100.0, false)
        val config = ImageBatchPreferences(true, 4, 3).configuration()
        val cost = NovelAiImageCostEstimator.estimate(NovelAiGenerationSettings(count = 4), opus).anlas.toLong()
        assertTrue(cost > 0)
        var attempted = false
        try {
            ImageBatchRunner(sleep = {}).run(config.freeze(ImageBatchOrigin.CHAT_MANUAL), ImageBatchControl(),
                quote = { ImageBatchQuote(cost, 1000) }) { attempted = true; it }
            fail("Paid requests must be blocked when spending is disabled")
        } catch (_: ImageBatchStopped) { }
        assertFalse(attempted)
    }

    @Test fun `default preferences decode from old entity fields and roundtrip independently`() {
        assertEquals(ImageBatchPreferences(), Json.decodeFromString<ImageBatchPreferences>("{}"))
        val one = ImageBatchPreferences(true, 4, 3, allowAnlasSpending = false)
        assertEquals(one, Json.decodeFromString<ImageBatchPreferences>(Json.encodeToString(ImageBatchPreferences.serializer(), one)))
        assertNull(estimatedPaymentError(0, false))
        assertNotNull(estimatedPaymentError(1, false))
        assertNotNull(estimatedPaymentError(null, true))
    }

    @Test fun `batch bounds reject overflow before multiplication`() {
        assertNotNull(ImageBatchConfiguration(numberOfBatches = Int.MAX_VALUE).validationError())
        assertNotNull(ImageBatchConfiguration(numberOfBatches = 0).validationError())
        assertNull(ImageBatchConfiguration(requestCount = 4, numberOfBatches = 25).validationError())
    }
}
