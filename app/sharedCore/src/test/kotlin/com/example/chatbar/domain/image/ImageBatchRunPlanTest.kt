package com.example.chatbar.domain.image

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ImageBatchRunPlanTest {
    private fun plan(count: Int = 1, total: Int = 3, seconds: Int = 2, allowAnlas: Boolean = false) =
        ImageBatchRunPlan(count, total, ImageBatchPacing(fixedSeconds = seconds), allowAnlas, ImageBatchOrigin.STUDIO)
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
    @Test fun `four images times three batches saves exactly twelve without a partial tail`() = runTest {
        val counts = mutableListOf<Int>(); val states = mutableListOf<ImageBatchProgress>()
        val saved = ImageBatchRunner(sleep = {}).run(plan(4, 3), ImageBatchControl(), free, onProgress = { states += it }) { counts += it; it }
        assertEquals(listOf(4, 4, 4), counts); assertEquals(12, saved); assertEquals(12, states.last().saved)
    }
    @Test fun `invalid ranges counts and overflow are rejected`() {
        for (config in listOf(ImageBatchConfiguration(requestCount = 0), ImageBatchConfiguration(requestCount = 5),
            ImageBatchConfiguration(numberOfBatches = 0), ImageBatchConfiguration(numberOfBatches = 101),
            ImageBatchConfiguration(requestCount = 4, numberOfBatches = 26),
            ImageBatchConfiguration(numberOfBatches = Int.MAX_VALUE), ImageBatchConfiguration(pacing = ImageBatchPacing(minSeconds = 46)),
            ImageBatchConfiguration(pacing = ImageBatchPacing(fixedSeconds = Int.MAX_VALUE)),
            ImageBatchConfiguration(pacing = ImageBatchPacing(minSeconds = -1)))) assertNotNull(config.validationError())
    }
    @Test fun `existing hundred-image capacity stays available at each exact batch size and legacy plans need review`() {
        for ((count, batches) in listOf(1 to 100, 2 to 50, 3 to 33, 4 to 25)) {
            val config = ImageBatchConfiguration(count, batches)
            assertNull(config.validationError())
            assertEquals(count * batches, config.freeze(ImageBatchOrigin.STUDIO).targetTotalImages)
            assertNotNull(config.copy(numberOfBatches = batches + 1).validationError())
        }
        val old = NovelAiStudioDraft(continuousModeEnabled = true, continuousTargetCount = 10,
            v45Settings = NovelAiGenerationSettings(count = 4))
        assertEquals(listOf(4, 4, 4), old.batchConfiguration().freeze(ImageBatchOrigin.STUDIO).batches)
        assertTrue(old.continuousModeEnabled, "Import projection must not silently start or acknowledge a task")
        assertEquals(10, old.continuousTargetCount)
    }
    @Test fun `random is independently sampled inside inclusive bounds`() {
        val random = Random(25); val values = List(40) { ImageBatchPacing(ImageBatchInterval.RANGE, minSeconds = 15, maxSeconds = 45).sampleSeconds(random) }
        assertTrue(values.all { it in 15..45 }); assertTrue(values.distinct().size > 10)
        assertEquals(15, ImageBatchPacing(ImageBatchInterval.RANGE, minSeconds = 15, maxSeconds = 15).sampleSeconds(random))
    }
    @Test fun `fractional waits round trip exactly and run only after each preceding commit`() = runTest {
        assertEquals(1500L, ImageBatchPacing.parseSeconds("1.5"))
        assertEquals(2300L, ImageBatchPacing.parseSeconds("2.3"))
        assertNull(ImageBatchPacing.parseSeconds("1.0001"))
        assertNull(ImageBatchPacing.parseSeconds("NaN"))
        val pacing = ImageBatchPacing(fixedSeconds = 2, fixedMillis = 1500)
        assertEquals(pacing, Json.decodeFromString<ImageBatchPacing>(Json.encodeToString(ImageBatchPacing.serializer(), pacing)))
        val events = mutableListOf<String>()
        ImageBatchRunner(sleep = { events += "wait:$it" }).run(plan(total = 2).copy(pacing = pacing), ImageBatchControl(), free) {
            events += "saved"; it
        }
        assertEquals(listOf("saved", "wait:1000", "wait:500", "saved"), events)
        val range = ImageBatchPacing(ImageBatchInterval.RANGE, minSeconds = 2, maxSeconds = 3, minMillis = 1500, maxMillis = 2300)
        val random = Random(5)
        val samples = List(100) { range.sampleMillis(random) }
        assertTrue(samples.all { it in 1500..2300L })
        assertTrue(samples.any { it % 1000 != 0L })
        assertEquals(7000L, ImageBatchPacing(fixedSeconds = 7).sampleMillis())
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
    @Test fun `disabled Anlas permission cannot switch silently from free to paid`() = runTest {
        var calls = 0
        val result = runCatching { ImageBatchRunner(sleep = {}).run(plan(), ImageBatchControl(), {
            ImageBatchQuote(if (calls == 0) 0 else 3, 100)
        }) { calls++; it } }
        assertTrue(result.exceptionOrNull() is ImageBatchStopped); assertEquals(1, calls)
    }
    @Test fun `one run quote covers unchanged later batches but never another task`() = runTest {
        var calls = 0
        var confirmations = 0
        val control = ImageBatchControl()
        ImageBatchRunner(sleep = {}).run(plan(allowAnlas = true), control, { ImageBatchQuote(3, 100) }, onProgress = {
            it.paymentTicket?.let { ticket -> confirmations++; assertTrue(control.approvePayment(ticket)) }
        }) { calls++; it }
        assertEquals(3, calls); assertEquals(1, confirmations)
        assertFalse(ImageBatchControl().paymentApproved(1, 3, 9))
    }
    @Test fun `higher corresponding batch quote requires confirmation even below the approved run total`() = runTest {
        val control = ImageBatchControl()
        val payment = control.requestPayment(1, 20, listOf(10, 5, 5))
        assertTrue(control.approvePayment(payment.ticket))
        assertTrue(control.paymentApproved(2, 5, 15))
        assertFalse(control.paymentApproved(2, 6, 16))
        assertFalse(control.paymentApproved(1, 10, 21))
        val newer = control.requestPayment(2, 16, listOf(6, 5))
        control.resume()
        assertTrue(control.isPaused, "Pacing resume is not payment approval")
        assertFalse(control.approvePayment(payment.ticket))
        assertTrue(control.approvePayment(newer.ticket))
        assertTrue(control.paymentApproved(2, 6, 16))
        val another = ImageBatchControl()
        another.requestPayment(1, 20, listOf(10, 5, 5))
        assertFalse(another.approvePayment(newer.ticket))
        assertTrue(another.isPaused)
    }
    @Test fun `actual runner pauses for a higher second quote despite savings below the confirmed total`() = runTest {
        val control = ImageBatchControl()
        var quotes = 0; var calls = 0; var approvals = 0
        val states = mutableListOf<ImageBatchProgress>()
        val job = launch {
            ImageBatchRunner(sleep = {}).run(plan(allowAnlas = true), control,
                quote = { ImageBatchQuote(when (++quotes) { 1 -> 10; 2 -> 4; else -> 6 }, 100, followingBatchAnlas = 5) },
                onProgress = { update ->
                    states += update
                    if (approvals == 0) update.paymentTicket?.let { approvals++; control.approvePayment(it) }
                }) { calls++; it }
        }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1, states.last().saved)
        assertEquals(15L, states.last().confirmationEstimatedTotal)
        assertTrue(control.isPaused)
        job.cancelAndJoin()
    }
    @Test fun `delayed pause progress cannot restore an approved payment ticket`() {
        val control = ImageBatchControl()
        val payment = control.requestPayment(1, 20, listOf(10, 10))
        val pending = ImageBatchProgress(paymentTicket = payment.ticket, confirmationEstimatedTotal = 20)
        assertEquals(pending, control.currentProgress(pending))
        control.pause()
        assertTrue(control.approvePayment(payment.ticket))
        assertNull(control.currentProgress(pending).paymentTicket)
        assertNull(control.currentProgress(pending).confirmationEstimatedTotal)
        assertTrue(control.isPaused)
        control.resume()
        assertFalse(control.isPaused)
        assertTrue(control.paymentApproved(1, 10, 20))
        val next = control.requestPayment(2, 21, listOf(11))
        assertNull(control.currentProgress(pending).paymentTicket)
        assertEquals(next.ticket, control.currentProgress(pending.copy(paymentTicket = next.ticket)).paymentTicket)
    }
    @Test fun `missing owner stops before next request preserving committed progress`() = runTest {
        var calls = 0; var valid = true; val states = mutableListOf<ImageBatchProgress>()
        val result = runCatching { ImageBatchRunner(sleep = {}).run(plan(), ImageBatchControl(), free,
            validateOwner = { check(valid) }, onProgress = { states += it }) { calls++; valid = false; it } }
        assertTrue(result.isFailure); assertEquals(1, calls); assertEquals(1, states.last().saved)
    }
    @Test fun `short successful save is failure and no next batch starts`() = runTest {
        var calls = 0
        val result = runCatching { ImageBatchRunner(sleep = {}).run(plan(4, 10), ImageBatchControl(), free) { calls++; it - 1 } }
        assertTrue(result.isFailure); assertEquals(1, calls)
    }
    @Test fun `old studio draft decodes safe defaults without launching anything`() {
        val draft = Json { ignoreUnknownKeys = true }.decodeFromString<NovelAiStudioDraft>("""{"continuousModeEnabled":true,"continuousTargetCount":10}""")
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
    @Test fun `owner changed during quote prevents reservation and dispatch`() = runTest {
        var current = true
        var calls = 0
        val states = mutableListOf<ImageBatchProgress>()
        val result = runCatching {
            ImageBatchRunner().run(plan(total = 1), ImageBatchControl(),
                quote = { yield(); current = false; ImageBatchQuote(0, 100) },
                validateOwner = { check(current) }, onProgress = { states += it }) { calls++; it }
        }
        assertTrue(result.isFailure)
        assertEquals(0, calls)
        assertTrue(states.all { it.estimatedSpent == 0L && it.saved == 0 })
    }
}
