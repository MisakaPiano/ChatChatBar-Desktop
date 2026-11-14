package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.lanSync.LanSyncGroupCloudSchedule.FailureCategory
import com.example.chatbar.domain.syncStore.SyncStoreId
import java.time.Instant
import kotlin.test.*

/** Pure fake monotonic time; no sleeps, timers, HTTP, credentials or retained user data. */
class LanSyncGroupCloudScheduleTest {
    private class Clock(var now: Long = 0) {
        val schedule = LanSyncGroupCloudSchedule({ now }, { 0 })
        val a = SyncStoreId.random(); val b = SyncStoreId.random()
        init { schedule.enabled(setOf(a, b)) }
    }
    @Test fun unseenEnabledRoutesStartImmediatelyAndRemovedRoutesStopWithoutErasingTheirWait() {
        val c = Clock(); val s = c.schedule
        assertTrue(s.due(c.a)); assertTrue(s.due(c.b)); assertEquals(0L, s.nextDelayMillis())
        s.failed(c.a, FailureCategory.TRANSIENT)
        s.enabled(emptySet()); assertNull(s.nextDelayMillis()); assertFalse(s.due(c.a, forceIdle = true))
        s.success(c.a, changed = true) // A late completion from a disabled route is ignored.
        c.now = 1_000; s.enabled(setOf(c.a))
        assertEquals(14_000L, s.nextDelayMillis()); assertFalse(s.due(c.a, forceIdle = true))
        val new = SyncStoreId.random(); s.enabled(setOf(c.a, new))
        assertTrue(s.due(new)); assertEquals(0L, s.nextDelayMillis())
        assertNull(s.state(SyncStoreId.random()))
    }
    @Test fun unchangedSuccessSlowsFromAMinuteToFiveMinutesAndChangedResetsTheSequence() {
        val c = Clock(); val s = c.schedule; s.enabled(setOf(c.a))
        for (expected in listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            s.success(c.a, changed = false)
            assertEquals(expected, s.nextDelayMillis()); assertFalse(s.due(c.a))
            assertTrue(s.due(c.a, forceIdle = true)); assertEquals(0L, s.nextDelayMillis(forceIdle = true))
            c.now += expected; assertTrue(s.due(c.a))
        }
        s.success(c.a, changed = true); assertEquals(60_000L, s.nextDelayMillis())
        c.now += 60_000; s.success(c.a, changed = false); assertEquals(60_000L, s.nextDelayMillis())
    }
    @Test fun transientFailuresReplaceIdleAndGrowToFifteenMinutesWithoutForceBypassingTheFloor() {
        val c = Clock(); val s = c.schedule; s.enabled(setOf(c.a))
        repeat(4) { s.success(c.a, changed = false) }; assertEquals(300_000L, s.nextDelayMillis())
        for (expected in listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)) {
            s.failed(c.a, FailureCategory.TRANSIENT)
            assertEquals(expected, s.nextDelayMillis()); assertEquals(expected, s.nextDelayMillis(forceIdle = true))
            assertFalse(s.due(c.a, forceIdle = true)); c.now += expected - 1
            assertFalse(s.due(c.a, forceIdle = true)); c.now++; assertTrue(s.due(c.a))
        }
        assertEquals(FailureCategory.TRANSIENT, s.state(c.a)?.failure)
        s.success(c.a, changed = false); assertNull(s.state(c.a)?.failure)
        c.now += 60_000; s.failed(c.a, FailureCategory.TRANSIENT)
        assertEquals(15_000L, s.nextDelayMillis())
    }
    @Test fun jitterIsSampledOncePerFailureAndAllQueriesKeepTheSameWait() {
        var now = 50L; var samples = 0
        val s = LanSyncGroupCloudSchedule({ now }, { limit -> samples++; limit })
        val id = SyncStoreId.random(); s.enabled(setOf(id)); s.failed(id, FailureCategory.TRANSIENT)
        assertEquals(18_750L, s.nextDelayMillis()); assertEquals(1, samples)
        repeat(5) {
            assertFalse(s.due(id, forceIdle = true)); assertEquals(18_750L, s.state(id)?.waitMillis)
            assertEquals(18_750L, s.nextDelayMillis())
        }
        assertEquals(1, samples); now += 1_000; assertEquals(17_750L, s.nextDelayMillis())
        repeat(15) { now += requireNotNull(s.nextDelayMillis()); s.failed(id, FailureCategory.TRANSIENT) }
        assertEquals(900_000L, s.nextDelayMillis())
    }
    @Test fun serverRetryAfterSurvivesExplicitWakesShorterErrorsAndOrdinaryBackoffCaps() {
        val c = Clock(); val s = c.schedule; s.enabled(setOf(c.a))
        s.failed(c.a, FailureCategory.TRANSIENT, 3_600_000)
        assertEquals(3_600_000L, s.nextDelayMillis(forceIdle = true))
        c.now += 1_000; s.failed(c.a, FailureCategory.TRANSIENT, 1)
        assertEquals(3_599_000L, s.nextDelayMillis())
        c.now += 3_598_999; assertFalse(s.due(c.a, forceIdle = true))
        c.now++; assertTrue(s.due(c.a, forceIdle = true))
    }
    @Test fun deferringLocalIncompleteWorkPreservesTheErrorAndServerFloorWhileForceOnlyBypassesIdle() {
        val c = Clock(); val s = c.schedule; s.enabled(setOf(c.a))
        s.failed(c.a, FailureCategory.TRANSIENT, 1_800_000); c.now += 1_000
        s.defer(c.a)
        assertEquals(1_799_000L, s.nextDelayMillis(forceIdle = true))
        assertEquals(FailureCategory.TRANSIENT, s.state(c.a)?.failure)
        assertFalse(s.due(c.a, forceIdle = true))
        c.now += 1_799_000; s.defer(c.a)
        assertEquals(60_000L, s.nextDelayMillis()); assertFalse(s.due(c.a))
        assertTrue(s.due(c.a, forceIdle = true))
        assertEquals(FailureCategory.TRANSIENT, s.state(c.a)?.failure)
    }
    @Test fun authenticationAndPermissionRequireConfigurationAndLongIndependentWaits() {
        val c = Clock(); val s = c.schedule
        s.failed(c.a, FailureCategory.AUTHENTICATION); s.failed(c.b, FailureCategory.PERMISSION)
        assertEquals(1_800_000L, s.nextDelayMillis())
        assertTrue(requireNotNull(s.state(c.a)).needsConfiguration); assertTrue(requireNotNull(s.state(c.b)).needsConfiguration)
        assertFalse(s.due(c.a, forceIdle = true)); c.now += 1_800_000
        s.failed(c.a, FailureCategory.AUTHENTICATION)
        assertEquals(3_600_000L, s.state(c.a)?.waitMillis); assertTrue(s.due(c.b))
        s.success(c.b, changed = true); assertFalse(requireNotNull(s.state(c.b)).needsConfiguration)
        assertEquals(60_000L, s.nextDelayMillis())
    }
    @Test fun perRouteProgressAndNextWakeDoNotLetOneFailingTargetDelayAnother() {
        val c = Clock(); val s = c.schedule
        s.success(c.a, changed = false); s.failed(c.b, FailureCategory.TRANSIENT)
        assertEquals(15_000L, s.nextDelayMillis()); assertEquals(0L, s.nextDelayMillis(forceIdle = true))
        assertTrue(s.due(c.a, forceIdle = true)); assertFalse(s.due(c.b, forceIdle = true))
        c.now += 15_000; assertTrue(s.due(c.b)); assertFalse(s.due(c.a))
        s.enabled(setOf(c.a)); assertEquals(45_000L, s.nextDelayMillis())
        assertEquals(FailureCategory.TRANSIENT, s.state(c.b)?.failure); assertFalse(requireNotNull(s.state(c.b)).enabled)
    }
    @Test fun relativeWaitCannotWrapIntoAnImmediateRetryNearTheLongClockLimit() {
        val c = Clock(Long.MAX_VALUE - 5); val s = c.schedule; s.enabled(setOf(c.a))
        s.failed(c.a, FailureCategory.TRANSIENT, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, s.nextDelayMillis()); assertFalse(s.due(c.a, forceIdle = true))
        c.now += 4; assertEquals(Long.MAX_VALUE - 4, s.nextDelayMillis())
        assertFalse(s.due(c.a, forceIdle = true))
    }
    @Test fun retryAfterDeltaSecondsHaveAFixedBudgetAndSaturateInsteadOfWrapping() {
        fun parse(value: String?) = LanSyncGroupCloudSchedule.parseRetryAfterMillis(value, 0)
        assertEquals(0L, parse("0")); assertEquals(120_000L, parse(" \t120\t "))
        assertEquals(Long.MAX_VALUE, parse("9223372036854775807")); assertEquals(Long.MAX_VALUE, parse("9".repeat(128)))
        for (bad in listOf(null, "", " ", "9".repeat(129), "-1", "+1", "1.5", "1, 2", "1\r\nRetry-After: 1", "１２", "1\t2")) {
            assertNull(parse(bad))
        }
    }
    @Test fun normalHttpDateIsConvertedOnceToARelativeWaitAndPastDatesNeverBecomeNegative() {
        val before = Instant.parse("1994-11-06T08:49:30Z").toEpochMilli()
        val date = "Sun, 06 Nov 1994 08:49:37 GMT"
        assertEquals(7_000L, LanSyncGroupCloudSchedule.parseRetryAfterMillis(date, before))
        assertEquals(0L, LanSyncGroupCloudSchedule.parseRetryAfterMillis(date, before + 10_000))
        assertEquals(Long.MAX_VALUE, LanSyncGroupCloudSchedule.parseRetryAfterMillis(date, Long.MIN_VALUE))
        for (bad in listOf("Mon, 06 Nov 1994 08:49:37 GMT", "Sun, 31 Feb 1994 08:49:37 GMT",
            "Sun, 06 Nov 1994 08:49:37 PST", "Sun, 06 Nov 1994 08:49:37 GMT\u0000")) {
            assertNull(LanSyncGroupCloudSchedule.parseRetryAfterMillis(bad, before))
        }
        val c = Clock(); c.schedule.enabled(setOf(c.a))
        val wait = LanSyncGroupCloudSchedule.parseRetryAfterMillis("600", before)
        c.schedule.failed(c.a, FailureCategory.TRANSIENT, wait)
        // A changed wall clock is relevant only when parsing a new response, never to this wait.
        assertEquals(0L, LanSyncGroupCloudSchedule.parseRetryAfterMillis(date, before + 86_400_000))
        assertEquals(600_000L, c.schedule.nextDelayMillis()); c.now += 1_000
        assertEquals(599_000L, c.schedule.nextDelayMillis())
    }
}
