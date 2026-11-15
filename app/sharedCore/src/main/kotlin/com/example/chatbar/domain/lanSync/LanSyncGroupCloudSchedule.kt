package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncStoreId
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.random.Random

/**
 * In-memory scheduling only: no timers, requests, credentials, queue deletion or delivery claims.
 * The caller serializes attempts and discards stale completions with its lifecycle generation.
 * forceIdle represents one consumed/debounced wake; keeping it true would defeat idle polling.
 */
class LanSyncGroupCloudSchedule(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Sampled once per failure; returns a value in 0..maximumInclusive. */
    private val jitter: (maximumInclusive: Long) -> Long = { Random.nextLong(it + 1) },
) {
    enum class FailureCategory { TRANSIENT, AUTHENTICATION, PERMISSION }
    data class State(val enabled: Boolean, val waitMillis: Long, val failure: FailureCategory?, val needsConfiguration: Boolean)
    private data class Wait(val started: Long, val duration: Long) {
        // Relative durations avoid overflowing an absolute deadline near Long.MAX_VALUE.
        fun remaining(now: Long): Long = (duration - elapsed(now, started)).coerceAtLeast(0)
    }
    private class Route {
        var unchanged = 0
        var failures = 0
        var idle: Wait? = null
        var error: Wait? = null
        var failure: FailureCategory? = null
        fun remaining(now: Long, forceIdle: Boolean) = maxOf(error?.remaining(now) ?: 0,
            if (forceIdle) 0 else idle?.remaining(now) ?: 0)
    }
    private val lock = Any()
    private var active: Set<SyncStoreId> = emptySet()
    private val routes = linkedMapOf<SyncStoreId, Route>()

    /** Catalog authority: unseen enabled routes need their first check immediately. History stays private. */
    fun enabled(ids: Set<SyncStoreId>) = synchronized(lock) {
        active = ids.toSet()
        ids.forEach { routes.getOrPut(it) { Route() } }
    }
    /** A query, not an in-flight reservation. Inactive/removed routes are never due. */
    fun due(id: SyncStoreId, forceIdle: Boolean = false): Boolean = synchronized(lock) {
        id in active && routes.getValue(id).remaining(clockMillis(), forceIdle) == 0L
    }
    fun nextDelayMillis(forceIdle: Boolean = false): Long? = synchronized(lock) {
        val now = clockMillis()
        active.minOfOrNull { routes.getValue(it).remaining(now, forceIdle) }
    }
    /** Successful actual attempt; changed resets idle progression, otherwise 60/120/240/300 seconds. */
    fun success(id: SyncStoreId, changed: Boolean) = synchronized(lock) {
        if (id !in active) return@synchronized
        val route = routes.getValue(id)
        route.unchanged = if (changed) 0 else minOf(route.unchanged + 1, 4)
        val delay = when (route.unchanged) { 0, 1 -> IDLE_START; 2 -> 120_000L; 3 -> 240_000L; else -> IDLE_CAP }
        route.idle = Wait(clockMillis(), delay)
        route.error = null; route.failure = null; route.failures = 0
    }
    /** Local incomplete work waits without inventing success or clearing an existing error floor. */
    fun defer(id: SyncStoreId, delayMillis: Long = IDLE_START) = synchronized(lock) {
        if (id !in active) return@synchronized
        routes.getValue(id).idle = Wait(clockMillis(), delayMillis.coerceAtLeast(0))
    }
    /** A longer server delay is never truncated to the ordinary exponential-backoff cap. */
    fun failed(id: SyncStoreId, category: FailureCategory, retryAfterMillis: Long? = null) = synchronized(lock) {
        if (id !in active) return@synchronized
        val now = clockMillis(); val route = routes.getValue(id)
        route.failures = if (route.failure == category) minOf(route.failures + 1, 64) else 1
        val cap = if (category == FailureCategory.TRANSIENT) ERROR_CAP else CONFIGURATION_CAP
        val base = grow(if (category == FailureCategory.TRANSIENT) ERROR_START else CONFIGURATION_START, cap, route.failures - 1)
        val extra = minOf(base / 4, cap - base)
        val delay = base + jitter(extra).coerceIn(0, extra)
        route.error = Wait(now, maxOf(delay, retryAfterMillis?.coerceAtLeast(0) ?: 0, route.error?.remaining(now) ?: 0))
        // A prior five-minute idle interval must not suppress this fifteen-second error retry.
        route.idle = null; route.unchanged = 0; route.failure = category
    }
    /** Typed payload-free UI projection; an expired failure remains visible until actual success. */
    fun state(id: SyncStoreId, forceIdle: Boolean = false): State? = synchronized(lock) {
        routes[id]?.let { route -> State(id in active, route.remaining(clockMillis(), forceIdle), route.failure,
            route.failure == FailureCategory.AUTHENTICATION || route.failure == FailureCategory.PERMISSION) }
    }

    companion object {
        private const val IDLE_START = 60_000L
        private const val IDLE_CAP = 300_000L
        private const val ERROR_START = 15_000L
        private const val ERROR_CAP = 900_000L
        private const val CONFIGURATION_START = 1_800_000L
        private const val CONFIGURATION_CAP = 21_600_000L
        private const val RETRY_AFTER_BUDGET = 128
        private val httpDate = Regex("[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT")
        private val httpDateFormat = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
            .withResolverStyle(ResolverStyle.STRICT)

        /**
         * RFC 9110 section 10.2.3: delta seconds or normal IMF-fixdate (RFC 1123/GMT).
         * Convert at response receipt, then pass this relative delay to failed; later wall-clock
         * changes cannot move the monotonic wait. Invalid/oversized input returns null, never text.
         * https://www.rfc-editor.org/rfc/rfc9110.html#name-retry-after
         */
        fun parseRetryAfterMillis(header: String?, nowEpochMillis: Long = System.currentTimeMillis()): Long? {
            if (header == null || header.length !in 1..RETRY_AFTER_BUDGET ||
                header.any { it.code !in 32..126 && it != '\t' }) return null
            val value = header.trim { it == ' ' || it == '\t' }
            if (value.isEmpty()) return null
            if (value.all { it in '0'..'9' }) {
                var seconds = 0L
                for (digit in value) {
                    val number = digit - '0'
                    if (seconds > (Long.MAX_VALUE - number) / 10) return Long.MAX_VALUE
                    seconds = seconds * 10 + number
                }
                return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
            }
            if (!httpDate.matches(value)) return null
            return try {
                val target = LocalDateTime.parse(value, httpDateFormat).toInstant(ZoneOffset.UTC).toEpochMilli()
                elapsed(target, nowEpochMillis)
            } catch (_: DateTimeException) { null }
        }
        private fun grow(start: Long, cap: Long, exponent: Int): Long {
            var delay = start; var steps = exponent
            while (steps-- > 0 && delay < cap) delay = if (delay > cap / 2) cap else delay * 2
            return delay
        }
        private fun elapsed(now: Long, then: Long): Long = when {
            now <= then -> 0
            then < 0 && now > Long.MAX_VALUE + then -> Long.MAX_VALUE
            else -> now - then
        }
    }
}
