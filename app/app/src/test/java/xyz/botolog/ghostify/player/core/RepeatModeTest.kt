package xyz.botolog.ghostify.player.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatModeTest {

    // ── next() ───────────────────────────────────────────────────────────

    @Test
    fun nextFromOffReturnsAll() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next())
    }

    @Test
    fun nextFromAllReturnsOne() {
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next())
    }

    @Test
    fun nextFromOneReturnsOff() {
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())
    }

    @Test
    fun nextCycleCompletesFullLoop() {
        val cycle = generateSequence(RepeatMode.OFF) { it.next() }.take(4).toList()
        assertEquals(listOf(RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE, RepeatMode.OFF), cycle)
    }

    // ── fromMedia3 ───────────────────────────────────────────────────────

    @Test
    fun fromMedia3ReturnsOffForZero() {
        assertEquals(RepeatMode.OFF, RepeatMode.fromMedia3(0))
    }

    @Test
    fun fromMedia3ReturnsOneForOne() {
        assertEquals(RepeatMode.ONE, RepeatMode.fromMedia3(1))
    }

    @Test
    fun fromMedia3ReturnsAllForTwo() {
        assertEquals(RepeatMode.ALL, RepeatMode.fromMedia3(2))
    }

    @Test
    fun fromMedia3ReturnsOffForUnknownValues() {
        assertEquals(RepeatMode.OFF, RepeatMode.fromMedia3(-1))
        assertEquals(RepeatMode.OFF, RepeatMode.fromMedia3(99))
        assertEquals(RepeatMode.OFF, RepeatMode.fromMedia3(Int.MAX_VALUE))
    }

    // ── media3Value constants ────────────────────────────────────────────

    @Test
    fun media3ValueConstantsMatchExpected() {
        assertEquals(0, RepeatMode.OFF.media3Value)
        assertEquals(1, RepeatMode.ONE.media3Value)
        assertEquals(2, RepeatMode.ALL.media3Value)
    }
}
