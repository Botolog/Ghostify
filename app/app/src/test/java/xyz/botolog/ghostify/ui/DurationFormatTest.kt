package xyz.botolog.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {

    // ── zero and negative ─────────────────────────────────────────────────

    @Test
    fun format_zeroReturnsZeroColonZeroZero() {
        assertEquals("0:00", DurationFormat.format(0L))
    }

    @Test
    fun format_negativeReturnsZeroColonZeroZero() {
        assertEquals("0:00", DurationFormat.format(-1000L))
        assertEquals("0:00", DurationFormat.format(-1L))
        assertEquals("0:00", DurationFormat.format(Long.MIN_VALUE))
    }

    // ── seconds only ──────────────────────────────────────────────────────

    @Test
    fun format_oneSecond() {
        assertEquals("0:01", DurationFormat.format(1_000L))
    }

    @Test
    fun format_fiftyNineSeconds() {
        assertEquals("0:59", DurationFormat.format(59_000L))
    }

    @Test
    fun format_subSecondTruncatesToZero() {
        assertEquals("0:00", DurationFormat.format(999L))
    }

    // ── minutes and seconds ───────────────────────────────────────────────

    @Test
    fun format_oneMinuteExactly() {
        assertEquals("1:00", DurationFormat.format(60_000L))
    }

    @Test
    fun format_oneMinuteThirtySeconds() {
        assertEquals("1:30", DurationFormat.format(90_000L))
    }

    @Test
    fun format_typicalSongThreeFortyTwo() {
        // 3 min 42 sec = 222 seconds = 222000 ms
        assertEquals("3:42", DurationFormat.format(222_000L))
    }

    @Test
    fun format_nineMinutesFiftyNineSeconds() {
        assertEquals("9:59", DurationFormat.format(599_000L))
    }

    @Test
    fun format_tenMinutesZeroSeconds() {
        assertEquals("10:00", DurationFormat.format(600_000L))
    }

    @Test
    fun format_secondsPaddedWithLeadingZero() {
        assertEquals("3:05", DurationFormat.format(185_000L))
    }

    // ── hours, minutes, seconds ───────────────────────────────────────────

    @Test
    fun format_oneHourExactly() {
        assertEquals("1:00:00", DurationFormat.format(3_600_000L))
    }

    @Test
    fun format_oneHourTwoMinutesThreeSeconds() {
        // 1h 2m 3s = 3723 seconds = 3723000 ms
        assertEquals("1:02:03", DurationFormat.format(3_723_000L))
    }

    @Test
    fun format_twoHoursThirtyMinutesFifteenSeconds() {
        // 2h 30m 15s = 9015 seconds
        assertEquals("2:30:15", DurationFormat.format(9_015_000L))
    }

    @Test
    fun format_tenHours() {
        assertEquals("10:00:00", DurationFormat.format(36_000_000L))
    }

    @Test
    fun format_hoursWithPaddedMinutesAndSeconds() {
        // 1h 00m 01s
        assertEquals("1:00:01", DurationFormat.format(3_601_000L))
    }

    // ── edge cases ────────────────────────────────────────────────────────

    @Test
    fun format_veryLargeValue() {
        // 100h = 360000 seconds
        assertEquals("100:00:00", DurationFormat.format(360_000_000L))
    }

    @Test
    fun format_exactlyOneMillisecond() {
        assertEquals("0:00", DurationFormat.format(1L))
    }

    @Test
    fun format_fiveHundredNinetyNineThousandMilliseconds() {
        // 599999 ms = 599 seconds = 9:59
        assertEquals("9:59", DurationFormat.format(599_999L))
    }
}
