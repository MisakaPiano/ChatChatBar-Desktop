package com.example.chatbar.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdateCacheSummaryTest {
    @Test fun `receipt-only scan distinguishes missing packages from retained payloads and dates the snapshot`() {
        val cache = Path.of("cache", "updates")
        val text = desktopUpdateCacheCleanupMessage(cache, 0, 0, 0, 19, 0, true, 4096, "2026-11-16 12:34:56")
        assertTrue(cache.toString() in text)
        assertTrue("2026-11-16 12:34:56" in text)
        assertTrue("19 条" in text)
        assertFalse("保留 19 份" in text)
        assertFalse("尚未确认完成的安装包" in text)
        assertTrue("刷新" in text)
    }

    @Test fun `unconfirmed pending and unknown material remains visible without claiming removal`() {
        val text = desktopUpdateCacheCleanupMessage(Path.of("updates"), 0, 0, 2, 16, 1, false, null, "12:34:56")
        assertTrue("2 份" in text)
        assertTrue("16 条" in text)
        assertTrue("1 项" in text)
        assertTrue("尚无" in text)
        assertTrue("无法读取" in text)
    }
}
