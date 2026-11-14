package com.example.chatbar.domain.image

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ImageBatchRunPlanTest {
    private fun plan(count: Int = 1, total: Int = 3, seconds: Int = 2, budget: Long = 0) =
        ImageBatchRunPlan(count, total, ImageBatchPacing(fixedSeconds = seconds), budget, ImageBatchOrigin.STUDIO)
    private val free: suspend (Int) -> ImageBatchQuote? = { ImageBatchQuote(0, 100) }

    @Test fun `single request has no pacing and saves once`() = runTest {
        val waits = mutableListOf<Long>(); val calls = mutableListOf<Int>()
        ImageBatchRunner(sleep = { waits += it }).run(plan(total = 1), ImageBatchControl(), free) { calls += it; it }
        assertEquals(listOf(1), calls); assertTrue(waits.isEmpty())
    }
    @Test fun `ten singles have exactly nine waits after commit`() = runTest {
        val events = mutableListOf<String>()
        ImageBatchRunner(sleep = { events += "wait" }).run(plan(total = 10, seconds = 1), ImageBatchControl(), free) { events += "saved"; it }
        assertEquals(10, events.count { it == "saved" }); assertEquals(9, events.count { it == "wait" })
        assertEquals("saved", events.first()); assertEquals("saved", events.last())
    }
    @Test fun `ten requested images partition into 4 4 2 and count commits only`() = runTest {
        val counts = mutableListOf<Int>(); val states = mutableListOf<ImageBatchProgress>()
        val saved = ImageBatchRunner(sleep = {}).run(plan(4, 10), ImageBatchControl(), free, onProgress = { states += it }) { counts += it; it }
        assertEquals(listOf(4, 4, 2), counts); assertEquals(10, saved); assertEquals(10, states.last().saved)
    }
    @Test fun `invalid ranges counts budget and overflow are rejected`() {
        for (config in listOf(ImageBatchConfiguration(requestCount = 0), ImageBatchConfiguration(requestCount = 5),
            ImageBatchConfiguration(continuous = true, target = 101), ImageBatchConfiguration(budget = -1),
            ImageBatchConfiguration(budget = Long.MAX_VALUE), ImageBatchConfiguration(pacing = ImageBatchPacing(minSeconds = 46)),
            ImageBatchConfiguration(pacing = ImageBatchPacing(fixedSeconds = Int.MAX_VALUE)),
            ImageBatchConfiguration(pacing = ImageBatchPacing(minSeconds = -1)))) assertNotNull(config.validationError())
    }
    @Test fun `random is independently sampled inside inclusive bounds`() {
        val random = Random(25); val values = List(40) { ImageBatchPacing(ImageBatchInterval.RANGE, minSeconds = 15, maxSeconds = 45).sampleSeconds(random) }
        assertTrue(values.all { it in 15..45 }); assertTrue(values.distinct().size > 10)
        assertEquals(15, ImageBatchPacing(ImageBatchInterval.RANGE, minSeconds = 15, maxSeconds = 15).sampleSeconds(random))
    }
    @Test fun `stop during delay never requests next batch`() = runTest {
        var calls = 0; val waiting = CompletableDeferred<Unit>()
        val job = launch { ImageBatchRunner(sleep = { waiting.complete(Unit); awaitCancellation() })
            .run(plan(), ImageBatchControl(), free) { calls++; it } }
        waiting.await(); job.cancelAndJoin(); assertEquals(1, calls)
    }
    @Test fun `user pause preserves countdown and releases admission to other runs`() = runTest {
        val mutex = Mutex(); val control = ImageBatchControl(); var first = 0; var second = 0
        val paused = CompletableDeferred<Unit>()
        val a = launch { ImageBatchRunner(mutex, sleep = { control.pause(); paused.complete(Unit) })
            .run(plan(seconds = 2), control, free) { first++; it } }
        paused.await(); runCurrent()
        ImageBatchRunner(mutex, sleep = {}).run(plan(total = 1), ImageBatchControl(), free) { second++; it }
        assertEquals(1, first); assertEquals(1, second); a.cancelAndJoin()
    }
    @Test fun `429 requires explicit resume and respects cooldown before another request`() = runTest {
        var requests = 0; var cooled = 0; val control = ImageBatchControl()
        val states = mutableListOf<ImageBatchProgress>()
        val job = launch { ImageBatchRunner(sleep = { cooled++ }).run(plan(total = 1), control, free, onProgress = { states += it }) {
            requests++; if (requests == 1) throw ImageBatchRateLimited(5000); assertEquals(5, cooled); it
        } }
        runCurrent(); assertEquals(1, requests); assertEquals(ImageBatchStage.PAUSED, states.last().stage)
        control.resume(); job.join(); assertEquals(2, requests)
    }
    @Test fun `three 429 results stop without unlimited automatic retry`() = runTest {
        var attempts = 0; val control = ImageBatchControl()
        val job = async { runCatching { ImageBatchRunner(sleep = {}).run(plan(total = 1), control, free) { attempts++; throw ImageBatchRateLimited(1000) } } }
        runCurrent(); control.resume(); runCurrent(); control.resume(); runCurrent()
        assertTrue(job.await().exceptionOrNull() is ImageBatchStopped); assertEquals(3, attempts)
    }
    @Test fun `uncertain failure never automatically retries or reports success`() = runTest {
        var attempts = 0; val states = mutableListOf<ImageBatchProgress>()
        val error = runCatching { ImageBatchRunner(sleep = {}).run(plan(), ImageBatchControl(), free, onProgress = { states += it }) {
            attempts++; throw java.io.IOException("accepted then EOF")
        } }.exceptionOrNull()
        assertNotNull(error); assertEquals(1, attempts); assertTrue(states.all { it.saved == 0 })
    }
    @Test fun `unknown account prevents even first generation`() = runTest {
        var calls = 0
        val result = runCatching { ImageBatchRunner().run(plan(), ImageBatchControl(), { null }) { calls++; it } }
        assertTrue(result.exceptionOrNull() is ImageBatchStopped); assertEquals(0, calls)
    }
    @Test fun `budget is rechecked each batch and cannot switch silently to paid`() = runTest {
        var calls = 0
        val result = runCatching { ImageBatchRunner(sleep = {}).run(plan(budget = 0), ImageBatchControl(), {
            ImageBatchQuote(if (calls == 0) 0 else 3, 100)
        }) { calls++; it } }
        assertTrue(result.exceptionOrNull() is ImageBatchStopped); assertEquals(1, calls)
    }
    @Test fun `cumulative budget includes request reservations`() = runTest {
        var calls = 0
        val result = runCatching { ImageBatchRunner(sleep = {}).run(plan(budget = 4), ImageBatchControl(), { ImageBatchQuote(3, 100) }) { calls++; it } }
        assertTrue(result.exceptionOrNull() is ImageBatchStopped); assertEquals(1, calls)
    }
    @Test fun `missing owner stops before next request preserving committed progress`() = runTest {
        var calls = 0; var valid = true; val states = mutableListOf<ImageBatchProgress>()
        val result = runCatching { ImageBatchRunner(sleep = {}).run(plan(), ImageBatchControl(), free,
            validateOwner = { check(valid) }, onProgress = { states += it }) { calls++; valid = false; it } }
        assertTrue(result.isFailure); assertEquals(1, calls); assertEquals(1, states.last().saved)
    }
    @Test fun `owner changed during account lookup stops before network admission`() = runTest {
        var valid = true; var calls = 0
        val result = runCatching { ImageBatchRunner().run(plan(total = 1), ImageBatchControl(),
            quote = { valid = false; ImageBatchQuote(0, 100) }, validateOwner = { check(valid) }) { calls++; it } }
        assertTrue(result.isFailure); assertEquals(0, calls)
    }
    @Test fun `short successful save is failure and no next batch starts`() = runTest {
        var calls = 0
        val result = runCatching { ImageBatchRunner(sleep = {}).run(plan(4, 10), ImageBatchControl(), free) { calls++; it - 1 } }
        assertTrue(result.isFailure); assertEquals(1, calls)
    }
    @Test fun `old studio draft decodes safe defaults without launching anything`() {
        val draft = Json { ignoreUnknownKeys = true }.decodeFromString<NovelAiStudioDraft>("""{"continuousModeEnabled":true,"continuousTargetCount":10,"updatedAt":1}""")
        assertEquals(ImageBatchPacing(), draft.batchPacing); assertTrue(draft.continuousModeEnabled)
        val copy = Json.decodeFromString<NovelAiStudioDraft>(Json.encodeToString(NovelAiStudioDraft.serializer(), draft))
        assertEquals(draft, copy)
    }
    @Test fun `stale balance and V5 responses do not refund reserved consumption`() {
        val ledger = ImageBatchAccountLedger(); val usage = NovelAiAccountUsage(100, 3, true, 0.1, false)
        ledger.reconcile(usage); ledger.reserve(NovelAiGenerationCost(NovelAiGenerationChargeKind.V5_ALLOWANCE, anlas = 3), 2)
        val stale = ledger.reconcile(usage)
        assertEquals(97L, stale.anlas); assertTrue(stale.v5AllowanceExhausted)
        assertEquals(97L, ledger.reconcile(usage.copy(anlas = 97)).anlas)
    }
    @Test fun `safe retry header keeps full seconds and HTTP date`() {
        assertEquals(120000L, ImageBatchRetryAfterPolicy.delayMillis("120"))
        assertEquals(10000L, ImageBatchRetryAfterPolicy.delayMillis("invalid"))
        assertEquals(60000L, ImageBatchRetryAfterPolicy.delayMillis("Thu, 01 Jan 1970 00:01:00 GMT", 0))
    }
}
