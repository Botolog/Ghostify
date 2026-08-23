package xyz.botolog.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RepeatCycleTest {

    // ── order list ────────────────────────────────────────────────────────

    @Test
    fun order_hasThreeElements() {
        assertEquals(3, RepeatCycle.order.size)
    }

    @Test
    fun order_startsWithOff() {
        assertEquals(RepeatMode.OFF, RepeatCycle.order[0])
    }

    @Test
    fun order_middleIsAll() {
        assertEquals(RepeatMode.ALL, RepeatCycle.order[1])
    }

    @Test
    fun order_endsWithOne() {
        assertEquals(RepeatMode.ONE, RepeatCycle.order[2])
    }

    // ── next() cycle ──────────────────────────────────────────────────────

    @Test
    fun next_fromOffReturnsAll() {
        assertEquals(RepeatMode.ALL, RepeatCycle.next(RepeatMode.OFF))
    }

    @Test
    fun next_fromAllReturnsOne() {
        assertEquals(RepeatMode.ONE, RepeatCycle.next(RepeatMode.ALL))
    }

    @Test
    fun next_fromOneReturnsOff() {
        assertEquals(RepeatMode.OFF, RepeatCycle.next(RepeatMode.ONE))
    }

    @Test
    fun next_fullCycleCompletesLoop() {
        val cycle = mutableListOf(RepeatMode.OFF)
        repeat(3) { cycle.add(RepeatCycle.next(cycle.last())) }
        assertEquals(
            listOf(RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE, RepeatMode.OFF),
            cycle,
        )
    }

    @Test
    fun next_doubleCycleCompletesTwoLoops() {
        val cycle = mutableListOf(RepeatMode.OFF)
        repeat(6) { cycle.add(RepeatCycle.next(cycle.last())) }
        assertEquals(
            listOf(
                RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE,
                RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE,
                RepeatMode.OFF,
            ),
            cycle,
        )
    }

    // ── RepeatMode labels ─────────────────────────────────────────────────

    @Test
    fun repeatModeOff_labelIsOff() {
        assertEquals("Off", RepeatMode.OFF.label)
    }

    @Test
    fun repeatModeAll_labelIsAll() {
        assertEquals("All", RepeatMode.ALL.label)
    }

    @Test
    fun repeatModeOne_labelIsOne() {
        assertEquals("One", RepeatMode.ONE.label)
    }

    @Test
    fun allLabelsAreNonBlank() {
        for (mode in RepeatMode.entries) {
            assertNotNull("Label for $mode should not be null", mode.label)
            assert(mode.label.isNotEmpty()) { "Label for $mode should not be empty" }
        }
    }

    // ── order list matches enum entries ───────────────────────────────────

    @Test
    fun orderContainsAllEnumEntries() {
        assertEquals(RepeatMode.entries.toSet(), RepeatCycle.order.toSet())
    }
}
