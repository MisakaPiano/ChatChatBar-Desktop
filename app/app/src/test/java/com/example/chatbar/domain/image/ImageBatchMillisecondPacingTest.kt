package com.example.chatbar.domain.image

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ImageBatchMillisecondPacingTest {
    @Test fun oldWholeSecondPacingRemainsReadable() {
        val old = Json.decodeFromString<ImageBatchPacing>("{\"fixedSeconds\":2,\"minSeconds\":1,\"maxSeconds\":3}")
        assertEquals(2000L, old.sampleMillis())
        assertNull(old.fixedMillis)
    }
    @Test fun fractionalPacingSurvivesSerializationWithoutChangingLegacyFields() {
        val pacing = ImageBatchPacing(fixedMillis = 1500L)
        assertEquals(pacing, Json.decodeFromString<ImageBatchPacing>(Json.encodeToString(pacing)))
        assertEquals(1500L, pacing.sampleMillis())
        assertEquals(15, pacing.fixedSeconds)
        assertEquals("1.5", imageBatchSecondsText(1500))
        assertEquals("0.001", imageBatchSecondsText(1))
    }
    @Test fun rangeSamplesMillisecondsInsideInclusiveBounds() {
        val pacing = ImageBatchPacing(ImageBatchInterval.RANGE, minMillis = 1500, maxMillis = 2300)
        val random = Random(17)
        val samples = List(100) { pacing.sampleMillis(random) }
        assertTrue(samples.all { it in 1500L..2300L })
        assertTrue(samples.any { it % 1000 != 0L })
        assertTrue(samples.distinct().size > 50)
        assertEquals(1501L, pacing.copy(minMillis = 1501, maxMillis = 1501).sampleMillis(random))
    }
    @Test fun invalidMillisecondsAndReversedRangesAreRejected() {
        listOf(ImageBatchPacing(fixedMillis = -1), ImageBatchPacing(maxMillis = 3_600_001),
            ImageBatchPacing(minMillis = 2000, maxMillis = 1500)).forEach { assertNotNull(it.validationError()) }
    }
    @Test fun nextBatchWaitsFractionalIntervalOnlyAfterPriorSave() = runTest {
        val events = mutableListOf<String>()
        val plan = ImageBatchRunPlan(1, 2, ImageBatchPacing(fixedMillis = 1500), 0, ImageBatchOrigin.STUDIO)
        ImageBatchRunner(sleep = { events += "wait:$it" }).run(plan, ImageBatchControl(),
            quote = { ImageBatchQuote(0, 100) }) { events += "saved:$it"; it }
        assertEquals(listOf("saved:1", "wait:1000", "wait:500", "saved:1"), events)
    }
    @Test fun cheaperEarlierBatchDoesNotAuthorizePriceRiseInLaterBatch() = runTest {
        val plan = ImageBatchRunPlan(1, 2, ImageBatchPacing(ImageBatchInterval.NONE), 10,
            ImageBatchOrigin.STUDIO, confirmedBatchAnlas = listOf(5, 5))
        var quotes = 0
        var requests = 0
        var failure: ImageBatchStopped? = null
        try {
            ImageBatchRunner(sleep = {}).run(plan, ImageBatchControl(), quote = {
                ImageBatchQuote(if (quotes++ == 0) 3 else 6, 100)
            }) { requests++; it }
        } catch (error: ImageBatchStopped) { failure = error }
        assertNotNull(failure)
        assertEquals(1, requests)
    }
    @Test fun unchangedQuotedPriceNeedsOnlyOneRunLevelConfirmation() = runTest {
        val plan = ImageBatchRunPlan(1, 3, ImageBatchPacing(ImageBatchInterval.NONE), 15,
            ImageBatchOrigin.STUDIO, confirmedBatchAnlas = listOf(5, 5, 5))
        var requests = 0
        assertEquals(3, ImageBatchRunner(sleep = {}).run(plan, ImageBatchControl(),
            quote = { ImageBatchQuote(5, 100) }) { requests++; it })
        assertEquals(3, requests)
    }

}
