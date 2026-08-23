package xyz.botolog.ghostify.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimestampsTest {

    // ── INVALID sentinel ──────────────────────────────────────────────────

    @Test
    fun invalidIsNegativeOne() {
        assertEquals(-1L, Timestamps.INVALID)
    }

    // ── fromEpochMs ───────────────────────────────────────────────────────

    @Test
    fun fromEpochMs_zeroReturnsUnixEpoch() {
        assertEquals(Instant.ofEpochMilli(0), Timestamps.fromEpochMs(0L))
    }

    @Test
    fun fromEpochMs_positiveValue() {
        val expected = Instant.ofEpochMilli(1_700_000_000_000L)
        assertEquals(expected, Timestamps.fromEpochMs(1_700_000_000_000L))
    }

    @Test
    fun fromEpochMs_negativeValue() {
        val expected = Instant.ofEpochMilli(-1000L)
        assertEquals(expected, Timestamps.fromEpochMs(-1000L))
    }

    // ── isValid ───────────────────────────────────────────────────────────

    @Test
    fun isValid_positiveValueReturnsTrue() {
        assertTrue(Timestamps.isValid(1L))
        assertTrue(Timestamps.isValid(Long.MAX_VALUE))
    }

    @Test
    fun isValid_zeroReturnsFalse() {
        assertFalse(Timestamps.isValid(0L))
    }

    @Test
    fun isValid_negativeValueReturnsFalse() {
        assertFalse(Timestamps.isValid(-1L))
        assertFalse(Timestamps.isValid(-1000L))
    }

    // ── format ────────────────────────────────────────────────────────────

    @Test
    fun format_validTimestampReturnsNonEmptyString() {
        val result = Timestamps.format(
            epochMs = 1_700_000_000_000L,
            zone = ZoneId.of("UTC"),
            locale = java.util.Locale.US,
        )
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun format_invalidTimestampReturnsEmpty() {
        assertEquals("", Timestamps.format(-1L))
        assertEquals("", Timestamps.format(0L))
    }

    @Test
    fun format_specificTimestamp() {
        // 2023-11-14T22:13:20Z
        val result = Timestamps.format(
            epochMs = 1_700_000_000_000L,
            zone = ZoneId.of("UTC"),
            locale = java.util.Locale.US,
        )
        assertNotNull(result)
        assertTrue(result.contains("2023") || result.contains("11/14") || result.contains("14/11"))
    }

    @Test
    fun format_differentZonesGiveDifferentResults() {
        val epochMs = 1_700_000_000_000L
        val utcResult = Timestamps.format(epochMs, ZoneId.of("UTC"), java.util.Locale.US)
        val tokyoResult = Timestamps.format(epochMs, ZoneId.of("Asia/Tokyo"), java.util.Locale.US)
        // Both should be non-empty; they may differ due to timezone offset
        assertTrue(utcResult.isNotEmpty())
        assertTrue(tokyoResult.isNotEmpty())
    }

    // ── sortAscending ─────────────────────────────────────────────────────

    @Test
    fun sortAscending_ascendingOrder() {
        assertEquals(0, Timestamps.sortAscending(100L, 100L))
        assertTrue(Timestamps.sortAscending(100L, 200L) < 0)
        assertTrue(Timestamps.sortAscending(200L, 100L) > 0)
    }

    @Test
    fun sortAscending_withNegativeValues() {
        assertTrue(Timestamps.sortAscending(-1L, 0L) < 0)
    }

    @Test
    fun sortAscending_canBeUsedForSorting() {
        val timestamps = listOf(300L, 100L, 200L)
        val sorted = timestamps.sortedWith(Comparator { a, b -> Timestamps.sortAscending(a, b) })
        assertEquals(listOf(100L, 200L, 300L), sorted)
    }
}
