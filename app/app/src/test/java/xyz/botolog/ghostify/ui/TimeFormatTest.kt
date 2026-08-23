package xyz.botolog.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatTest {

    // ── null epochMs ──────────────────────────────────────────────────────

    @Test
    fun formatRelative_nullEpochMsReturnsNever() {
        assertEquals("Never", TimeFormat.formatRelative(null, nowMs = 1_000_000L))
    }

    // ── just now ──────────────────────────────────────────────────────────

    @Test
    fun formatRelative_sameTimestampReturnsJustNow() {
        assertEquals("Just now", TimeFormat.formatRelative(100_000L, nowMs = 100_000L))
    }

    @Test
    fun formatRelative_futureTimestampReturnsJustNow() {
        assertEquals("Just now", TimeFormat.formatRelative(200_000L, nowMs = 100_000L))
    }

    @Test
    fun formatRelative_lessThanOneMinuteReturnsJustNow() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 59_000L // 59 seconds ago
        assertEquals("Just now", TimeFormat.formatRelative(epochMs, nowMs))
    }

    @Test
    fun formatRelative_exactlyOneSecondAgoReturnsJustNow() {
        val nowMs = 1_000_000L
        assertEquals("Just now", TimeFormat.formatRelative(nowMs - 1_000L, nowMs))
    }

    // ── minutes ago ───────────────────────────────────────────────────────

    @Test
    fun formatRelative_oneMinuteAgo() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 60_000L
        assertEquals("1m ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    @Test
    fun formatRelative_fiveMinutesAgo() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 300_000L
        assertEquals("5m ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    @Test
    fun formatRelative_59MinutesAgo() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 3_540_000L // 59 * 60 * 1000
        assertEquals("59m ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    // ── hours ago ─────────────────────────────────────────────────────────

    @Test
    fun formatRelative_oneHourAgo() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 3_600_000L
        assertEquals("1h ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    @Test
    fun formatRelative_sixHoursAgo() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 21_600_000L
        assertEquals("6h ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    @Test
    fun formatRelative_23HoursAgo() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 82_800_000L // 23 * 60 * 60 * 1000
        assertEquals("23h ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    // ── days ago ──────────────────────────────────────────────────────────

    @Test
    fun formatRelative_oneDayAgo() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 86_400_000L
        assertEquals("1d ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    @Test
    fun formatRelative_sixDaysAgo() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 518_400_000L // 6 * 86_400_000
        assertEquals("6d ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    // ── date fallback ─────────────────────────────────────────────────────

    @Test
    fun formatRelative_sevenDaysAgoReturnsFormattedDate() {
        val nowMs = System.currentTimeMillis()
        val epochMs = nowMs - 604_800_000L // 7 days
        val result = TimeFormat.formatRelative(epochMs, nowMs)
        // Should be a formatted date like "Jan 5, 2025" — just verify it's not a relative string
        assertTrue(!result.endsWith("m ago"))
        assertTrue(!result.endsWith("h ago"))
        assertTrue(!result.endsWith("d ago"))
        assertTrue(result != "Just now")
        assertTrue(result != "Never")
    }

    @Test
    fun formatRelative_veryOldDateReturnsFormattedDate() {
        val nowMs = System.currentTimeMillis()
        val epochMs = nowMs - 31_536_000_000L // ~1 year
        val result = TimeFormat.formatRelative(epochMs, nowMs)
        assertTrue(result != "Just now")
        assertTrue(result != "Never")
    }

    // ── boundary conditions ───────────────────────────────────────────────

    @Test
    fun formatRelative_exactlyOnMinuteBoundary() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 60_000L
        assertEquals("1m ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    @Test
    fun formatRelative_exactlyOnHourBoundary() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 3_600_000L
        assertEquals("1h ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    @Test
    fun formatRelative_exactlyOnDayBoundary() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 86_400_000L
        assertEquals("1d ago", TimeFormat.formatRelative(epochMs, nowMs))
    }

    @Test
    fun formatRelative_exactlyOnWeekBoundary() {
        val nowMs = 1_000_000L
        val epochMs = nowMs - 604_800_000L
        val result = TimeFormat.formatRelative(epochMs, nowMs)
        // 7 days = exactly 1 week, should fall into date formatting
        assertTrue(result != "7d ago")
    }
}
