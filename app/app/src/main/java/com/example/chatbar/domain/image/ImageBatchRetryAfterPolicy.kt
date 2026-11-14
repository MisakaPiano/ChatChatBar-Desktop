package com.example.chatbar.domain.image

/** Respect the full server cooldown; the runner stops rather than shorten a wait above its limit. */
object ImageBatchRetryAfterPolicy {
    fun delayMillis(header: String?, nowMillis: Long = System.currentTimeMillis()): Long {
        val seconds = header?.trim()?.toLongOrNull()
        if (seconds != null) return seconds.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
        val date = runCatching { java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        return date?.let { (it - nowMillis).coerceAtLeast(0) } ?: 10000L
    }
}
