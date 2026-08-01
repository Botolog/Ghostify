package com.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatCycleTest {

    @Test
    fun `cycle is OFF to ALL to ONE`() {
        assertEquals(RepeatMode.ALL, RepeatCycle.next(RepeatMode.OFF))
        assertEquals(RepeatMode.ONE, RepeatCycle.next(RepeatMode.ALL))
    }

    @Test
    fun `cycle wraps ONE back to OFF`() {
        assertEquals(RepeatMode.OFF, RepeatCycle.next(RepeatMode.ONE))
    }

    @Test
    fun `full three-step cycle returns to start`() {
        assertEquals(
            RepeatMode.OFF,
            RepeatCycle.next(RepeatCycle.next(RepeatCycle.next(RepeatMode.OFF))),
        )
    }

    @Test
    fun `labels are human readable`() {
        assertEquals("Off", RepeatMode.OFF.label)
        assertEquals("All", RepeatMode.ALL.label)
        assertEquals("One", RepeatMode.ONE.label)
    }

    @Test
    fun `order contains every mode exactly once`() {
        assertEquals(RepeatMode.entries.toSet(), RepeatCycle.order.toSet())
        assertEquals(3, RepeatCycle.order.size)
    }
}
